package app.siphondsp.service

import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class RootlessPipelineLifecycleTest {
    private lateinit var service: RootlessAudioProcessorService
    private lateinit var track: AudioTrack
    private lateinit var worker: Thread
    private lateinit var finish: CountDownLatch

    @Before
    fun setUp() {
        service = RootlessAudioProcessorService()
        track = mock(AudioTrack::class.java)
        finish = CountDownLatch(1)
        worker = Thread { finish.await() }
        worker.start()
        set("recorderThread", worker)
        set("activeTrack", track)
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
        repeat(3) {
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
        whenever(track.write(any<ShortArray>(), any(), any(), any())).thenAnswer {
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
