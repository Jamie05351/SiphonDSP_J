package app.siphondsp.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise real service command/recovery guards without constructing native DSP or projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RootlessPipelineLifecycleTest {
    private lateinit var service: RootlessAudioProcessorService
    private lateinit var worker: Thread
    private val finish = CountDownLatch(1)
    private val recorder: AudioRecord = mock()
    private val track: AudioTrack = mock()

    @Before fun setUp() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        service = Robolectric.buildService(RootlessAudioProcessorService::class.java).get()
        val ready = CountDownLatch(1)
        worker = Thread {
            ready.countDown()
            finish.await()
        }
        worker.start()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        whenever(recorder.state).thenReturn(AudioRecord.STATE_INITIALIZED)
        whenever(recorder.recordingState).thenReturn(AudioRecord.RECORDSTATE_RECORDING)
        whenever(track.state).thenReturn(AudioTrack.STATE_INITIALIZED)
        whenever(track.playState).thenReturn(AudioTrack.PLAYSTATE_PLAYING)
        set("recorderThread", worker)
        set("activeRecorder", recorder)
        set("activeTrack", track)
        set("pipelineStartedAt", SystemClock.elapsedRealtime() - 20_000)
        set("flowExpectedSince", SystemClock.elapsedRealtime() - 20_000)
        markFlow()
    }

    @After fun tearDown() {
        finish.countDown()
        worker.join(2_000)
        assertFalse(worker.isAlive)
    }

    private fun set(name: String, value: Any) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun get(name: String): Any? =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun start() = service.onStartCommand(Intent(RootlessAudioProcessorService.ACTION_START), 0, 1)

    private fun markFlow() {
        val now = SystemClock.elapsedRealtime()
        set("lastSuccessfulRead", now)
        set("lastSuccessfulProcess", now)
        set("lastSuccessfulWrite", now)
    }

    @Test fun repeatedHealthyStartLeavesTheExistingPipelineAlone() {
        assertEquals(Service.START_NOT_STICKY, start())
        assertSame(worker, get("recorderThread"))
        assertEquals(false, get("recreateRecorderRequested"))
        verify(recorder, never()).stop()
        verify(track, never()).stop()
    }

    @Test fun legitimateStartupDoesNotCreateAnotherWorker() {
        set("pipelineStartedAt", SystemClock.elapsedRealtime())
        set("lastSuccessfulRead", -1L)
        set("lastSuccessfulProcess", -1L)
        set("lastSuccessfulWrite", -1L)
        start()
        assertSame(worker, get("recorderThread"))
        assertEquals(false, get("recreateRecorderRequested"))
    }

    @Test fun staleWorkerRequestsRecreationAndUnblocksBothObjectsWithoutReleasingThem() {
        set("lastSuccessfulWrite", -1L)
        start()
        assertSame(worker, get("recorderThread"))
        assertEquals(true, get("recreateRecorderRequested"))
        assertEquals(true, get("expectingReadInterruption"))
        verify(recorder).stop()
        verify(track).stop()
        verify(recorder, never()).release()
        verify(track, never()).release()
        // Repeated START while the request is pending must not issue another recovery.
        start()
        verify(recorder).stop()
        verify(track).stop()
    }

    @Test fun expectedIdleDoesNotRecoverStaleFlow() {
        set("isProcessorIdle", true)
        set("lastSuccessfulRead", -1L)
        set("lastSuccessfulProcess", -1L)
        set("lastSuccessfulWrite", -1L)
        start()
        assertEquals(false, get("recreateRecorderRequested"))
        verify(recorder, never()).stop()
    }

    @Test fun configurationRecreationUnblocksIdleReadInsteadOfWaitingForever() {
        set("isProcessorIdle", true)
        service.requestAudioRecordRecreation("blocklist changed")
        assertEquals(true, get("recreateRecorderRequested"))
        verify(recorder).stop()
        verify(track).stop()
        verify(recorder, never()).release()
    }

    @Test fun stoppingIgnoresStartAndRecreationRequests() {
        set("isServiceDisposing", true)
        set("lastSuccessfulWrite", -1L)
        start()
        service.requestAudioRecordRecreation()
        service.restartRecording()
        assertSame(worker, get("recorderThread"))
        assertEquals(false, get("recreateRecorderRequested"))
        verify(recorder, never()).stop()
        verify(track, never()).stop()
    }

    @Test fun generatorCanBeHealthyWithoutAnySuccessfulCaptureRead() {
        set("measGenActive", true)
        set("isProcessorIdle", true)
        set("lastSuccessfulRead", -1L)
        start()
        assertEquals(false, get("recreateRecorderRequested"))
        assertEquals(AudioPipelineHealth.State.HEALTHY,
            (get("lastHealth") as AudioPipelineHealth.Result).state)
    }

    @Test fun watchdogRecoversWithoutAnotherStartCommand() {
        set("lastSuccessfulProcess", -1L)
        (get("healthWatchdog") as Runnable).run()
        assertEquals(true, get("recreateRecorderRequested"))
        verify(recorder).stop()
        verify(track).stop()
        // Cancel only the scheduled check; onDestroy would require the full native service.
        (get("healthHandler") as android.os.Handler).removeCallbacks(get("healthWatchdog") as Runnable)
    }

    @Test fun timedOutRecreationStopsInsteadOfStartingADuplicatePipeline() {
        set("recreationInProgress", true)
        set("recreationSince", SystemClock.elapsedRealtime() - 15_000)
        start()
        assertEquals(true, get("isServiceDisposing"))
        assertSame(worker, get("recorderThread"))
        assertEquals(AudioPipelineHealth.State.FAILED,
            (get("lastHealth") as AudioPipelineHealth.Result).state)
        verify(recorder, never()).release()
    }

    @Test fun repeatedRecoveriesAreBounded() {
        repeat(3) {
            set("lastSuccessfulWrite", -1L)
            start()
            assertEquals(true, get("recreateRecorderRequested"))
            // Emulate worker acknowledgement/completion, with no restored output progress.
            set("recreateRecorderRequested", false)
        }
        start()
        assertEquals(true, get("isServiceDisposing"))
        assertSame(worker, get("recorderThread"))
    }

    @Test fun watchdogDetectsUnexpectedWorkerExit() {
        finish.countDown()
        worker.join(2_000)
        (get("healthWatchdog") as Runnable).run()
        assertEquals(true, get("isServiceDisposing"))
        assertEquals(AudioPipelineHealth.State.FAILED,
            (get("lastHealth") as AudioPipelineHealth.Result).state)
    }

    @Test fun repeatedZeroFloatWritesYieldToRecoveryInsteadOfSpinningForever() {
        var calls = 0
        whenever(track.write(any<FloatArray>(), any(), any(), any())).thenAnswer {
            if (++calls == 4) start()
            check(calls <= 4) { "write loop ignored recreation" }
            0
        }
        service.javaClass.getDeclaredMethod("writeFully", AudioTrack::class.java,
            FloatArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(service, track, FloatArray(4), 4)
        assertEquals(4, calls)
        assertEquals(3, get("consecutiveWriteFailures"))
        assertEquals(true, get("recreateRecorderRequested"))
        verify(track).stop()
        verify(track, never()).release()
    }

    @Test fun repeatedNegativeShortWritesYieldToRecovery() {
        var calls = 0
        whenever(track.write(any<ShortArray>(), any(), any(), any())).thenAnswer {
            if (++calls == 4) start()
            check(calls <= 4) { "write loop ignored recreation" }
            AudioTrack.ERROR_DEAD_OBJECT
        }
        service.javaClass.getDeclaredMethod("writeFully", AudioTrack::class.java,
            ShortArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(service, track, ShortArray(4), 4)
        assertEquals(4, calls)
        assertEquals(3, get("consecutiveWriteFailures"))
        assertEquals(true, get("recreateRecorderRequested"))
    }
}
