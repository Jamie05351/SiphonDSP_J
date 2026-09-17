package app.siphondsp.service

import android.media.AudioTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootlessPipelineLifecycleTest {
    private lateinit var service: RootlessAudioProcessorService
    private lateinit var track: AudioTrack
    private lateinit var worker: Thread
    private lateinit var finish: CountDownLatch

    @Before
    fun setUp() {
        service = RootlessAudioProcessorService()
        track = mock(AudioTrack::class.java)
        whenever(track.state).thenReturn(AudioTrack.STATE_INITIALIZED)
        whenever(track.playState).thenReturn(AudioTrack.PLAYSTATE_PLAYING)
        finish = CountDownLatch(1)
        worker = Thread { finish.await() }
        worker.start()
        set("recorderThread", worker)
        set("activeTrack", track)
    }

    @After
    fun tearDown() {
        finish.countDown()
        worker.join(2_000)
    }

    private fun get(name: String): Any? = service.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(service)

    private fun set(name: String, value: Any?) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun start() {
        service.javaClass.getDeclaredMethod("requestAudioRecordRecreation", String::class.java)
            .apply { isAccessible = true }
            .invoke(service, "test")
    }

    @Test fun watchdogDoesNotSpawnDuplicateWorkerWhileRecreationOutstanding() {
        set("recreateRecorderRequested", true)
        val original = get("recorderThread")
        (get("healthWatchdog") as Runnable).run()
        assertSame(original, get("recorderThread"))
    }

    @Test fun watchdogEscalatesWhenRecoveryCannotRestoreProgress() {
        // Recovery-budget accounting belongs to the watchdog health path, not to direct
        // configuration/session recreation requests. Keep presenting a stalled pipeline and
        // emulate the worker acknowledging each recreation without restoring progress.
        repeat(AudioPipelineHealth.MAX_RECOVERIES_PER_WINDOW) {
            set("consecutiveWriteFailures", AudioPipelineHealth.MAX_CONSECUTIVE_FAILURES)
            (get("healthWatchdog") as Runnable).run()
            assertEquals(true, get("recreateRecorderRequested"))
            set("recreateRecorderRequested", false)
            set("recreationInProgress", false)
            set("consecutiveWriteFailures", 0)
        }

        set("consecutiveWriteFailures", AudioPipelineHealth.MAX_CONSECUTIVE_FAILURES)
        (get("healthWatchdog") as Runnable).run()
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
        whenever(track.write(any<FloatArray>(), anyInt(), anyInt(), anyInt())).thenAnswer {
            if (++calls == 4) start()
            check(calls <= 4) { "write loop ignored recreation" }
            0
        }
        service.javaClass.getDeclaredMethod(
            "writeFully",
            AudioTrack::class.java,
            FloatArray::class.java,
            Int::class.javaPrimitiveType,
            StartupAudioDiagnostics::class.java,
        ).apply { isAccessible = true }
            .invoke(service, track, FloatArray(4), 4, null)
        assertEquals(4, calls)
        assertEquals(3, get("consecutiveWriteFailures"))
        assertEquals(true, get("recreateRecorderRequested"))
        verify(track).stop()
        verify(track, never()).release()
    }

    @Test fun repeatedNegativeShortWritesYieldToRecovery() {
        var calls = 0
        whenever(track.write(any<ShortArray>(), anyInt(), anyInt(), anyInt())).thenAnswer {
            if (++calls == 4) start()
            check(calls <= 4) { "write loop ignored recreation" }
            AudioTrack.ERROR_DEAD_OBJECT
        }
        service.javaClass.getDeclaredMethod(
            "writeFully",
            AudioTrack::class.java,
            ShortArray::class.java,
            Int::class.javaPrimitiveType,
            StartupAudioDiagnostics::class.java,
        ).apply { isAccessible = true }
            .invoke(service, track, ShortArray(4), 4, null)
        assertEquals(4, calls)
        assertEquals(3, get("consecutiveWriteFailures"))
        assertEquals(true, get("recreateRecorderRequested"))
    }
}
