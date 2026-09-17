package app.siphondsp.service

import android.os.SystemClock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded, allocation-light startup telemetry for the rootless read -> DSP -> write pipeline.
 *
 * A fresh instance is created for the initial pipeline and for every recorder recreation. The
 * audio thread updates primitive counters only while [isActive] is true; once the one-second
 * window expires, [finishIfDue] emits one compact summary and all hot-path calls become no-ops.
 */
internal class StartupAudioDiagnostics private constructor(
    val attemptId: Long,
    private val startedAtMs: Long,
    private val reason: String,
    private val sampleRate: Int,
    private val encodingName: String,
    private val bufferSizeBytes: Int,
    private val bufferSamples: Int,
    private val mediaProjectionReady: Boolean,
    private val nativeHandleReadyAtStart: Boolean,
    private val measurementGeneratorAtStart: Boolean,
    private val processorIdleAtStart: Boolean,
) {
    enum class Result {
        MEDIA_PROJECTION,
        AUDIO_RECORD_CREATE,
        AUDIO_RECORD_START,
        AUDIO_RECORD_READ,
        DSP,
        AUDIO_TRACK_CREATE,
        AUDIO_TRACK_PLAY,
        AUDIO_TRACK_WRITE,
        HEALTHY,
        EXPECTED_IDLE,
    }

    @Volatile
    private var finished = false

    private var recorderCreated = false
    private var recorderState = -1
    private var recorderRecordingState = -1
    private var recorderStarted = false
    private var recorderSampleRate = 0

    private var trackCreated = false
    private var trackState = -1
    private var trackPlayState = -1
    private var trackPlaying = false
    private var trackSampleRate = 0

    private var readCalls = 0L
    private var successfulReads = 0L
    private var zeroReads = 0L
    private var firstReadAtMs = -1L
    private var firstReadCount = 0
    private var minReadCount = Int.MAX_VALUE
    private var maxReadCount = 0
    private var lastReadError = 0

    private var dspCalls = 0L
    private var firstDspAtMs = -1L
    private var processedSamples = 0L
    private var nativeHandleSeen = nativeHandleReadyAtStart
    private var bypassSeen = false
    private var dspFailure: String? = null
    private var nonFiniteOutput = false

    private var writeCalls = 0L
    private var successfulWrites = 0L
    private var zeroWrites = 0L
    private var firstWriteAtMs = -1L
    private var firstWriteCount = 0
    private var lastWriteError = 0

    private var nonZeroInput = false
    private var nonZeroOutput = false
    private var expectedIdle = processorIdleAtStart && !measurementGeneratorAtStart

    fun isActive(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        !finished && nowMs - startedAtMs <= WINDOW_MS

    fun recordRecorderCreated(state: Int, recordingState: Int, actualSampleRate: Int) {
        if (!isActive()) return
        recorderCreated = true
        recorderState = state
        recorderRecordingState = recordingState
        recorderSampleRate = actualSampleRate
        Timber.i(
            "%s[%s] AudioRecord created state=%s recordingState=%s sampleRate=%s",
            TAG, attemptId, state, recordingState, actualSampleRate,
        )
    }

    fun recordTrackCreated(state: Int, playState: Int, actualSampleRate: Int) {
        if (!isActive()) return
        trackCreated = true
        trackState = state
        trackPlayState = playState
        trackSampleRate = actualSampleRate
        Timber.i(
            "%s[%s] AudioTrack created state=%s playState=%s sampleRate=%s",
            TAG, attemptId, state, playState, actualSampleRate,
        )
    }

    fun recordRecorderStarted(recordingState: Int) {
        if (!isActive()) return
        recorderRecordingState = recordingState
        recorderStarted = true
        Timber.i("%s[%s] AudioRecord started recordingState=%s", TAG, attemptId, recordingState)
    }

    fun recordTrackStarted(playState: Int) {
        if (!isActive()) return
        trackPlayState = playState
        trackPlaying = true
        Timber.i("%s[%s] AudioTrack started playState=%s", TAG, attemptId, playState)
    }

    fun recordRead(count: Int, generatedInput: Boolean) {
        if (!isActive()) return
        readCalls++
        if (count > 0) {
            successfulReads++
            if (firstReadAtMs < 0) {
                firstReadAtMs = SystemClock.elapsedRealtime()
                firstReadCount = count
            }
            minReadCount = min(minReadCount, count)
            maxReadCount = max(maxReadCount, count)
        } else if (count == 0) {
            zeroReads++
        } else {
            lastReadError = count
        }
        if (generatedInput) expectedIdle = false
    }

    fun recordShortInput(buffer: ShortArray, count: Int) {
        if (!isActive() || nonZeroInput) return
        val limit = min(count, buffer.size)
        for (i in 0 until limit) {
            if (buffer[i].toInt() != 0) {
                nonZeroInput = true
                return
            }
        }
    }

    fun recordFloatInput(buffer: FloatArray, count: Int) {
        if (!isActive() || nonZeroInput) return
        val limit = min(count, buffer.size)
        for (i in 0 until limit) {
            if (buffer[i] != 0f) {
                nonZeroInput = true
                return
            }
        }
    }

    fun recordDspStart(nativeHandleReady: Boolean, bypassUsed: Boolean) {
        if (!isActive()) return
        dspCalls++
        nativeHandleSeen = nativeHandleSeen || nativeHandleReady
        bypassSeen = bypassSeen || bypassUsed
        if (firstDspAtMs < 0) firstDspAtMs = SystemClock.elapsedRealtime()
    }

    fun recordDspSuccess(processCount: Int) {
        if (!isActive()) return
        processedSamples += processCount.toLong()
    }

    fun recordDspFailure(error: Throwable) {
        if (!isActive()) return
        dspFailure = "${error::class.java.simpleName}:${error.message.orEmpty()}"
    }

    fun recordShortOutput(buffer: ShortArray, count: Int) {
        if (!isActive() || nonZeroOutput) return
        val limit = min(count, buffer.size)
        for (i in 0 until limit) {
            if (buffer[i].toInt() != 0) {
                nonZeroOutput = true
                return
            }
        }
    }

    fun recordFloatOutput(buffer: FloatArray, count: Int) {
        if (!isActive()) return
        val limit = min(count, buffer.size)
        for (i in 0 until limit) {
            val sample = buffer[i]
            if (!sample.isFinite()) nonFiniteOutput = true
            if (sample != 0f) nonZeroOutput = true
            if (nonFiniteOutput && nonZeroOutput) return
        }
    }

    fun recordWrite(written: Int) {
        if (!isActive()) return
        writeCalls++
        if (written > 0) {
            successfulWrites++
            if (firstWriteAtMs < 0) {
                firstWriteAtMs = SystemClock.elapsedRealtime()
                firstWriteCount = written
            }
        } else if (written == 0) {
            zeroWrites++
        } else {
            lastWriteError = written
        }
    }

    fun finishIfDue(nowMs: Long = SystemClock.elapsedRealtime(), force: Boolean = false) {
        if (finished || (!force && nowMs - startedAtMs < WINDOW_MS)) return
        finished = true
        val result = classify()
        val firstReadMs = elapsed(firstReadAtMs)
        val firstDspMs = elapsed(firstDspAtMs)
        val firstWriteMs = elapsed(firstWriteAtMs)
        val minRead = if (minReadCount == Int.MAX_VALUE) 0 else minReadCount

        Timber.i(
            "%s[%s] SUMMARY reason=%s sampleRate=%s encoding=%s bufferBytes=%s bufferSamples=%s " +
                "projection=%s nativeHandleStart=%s nativeHandleSeen=%s generator=%s idle=%s " +
                "recorderCreated=%s recorderState=%s recordingState=%s recorderStarted=%s recorderRate=%s " +
                "reads=%s successfulReads=%s zeroReads=%s firstReadMs=%s firstReadCount=%s minRead=%s maxRead=%s readError=%s " +
                "dspCalls=%s processedSamples=%s firstDspMs=%s bypassSeen=%s dspFailure=%s nonFiniteOutput=%s " +
                "trackCreated=%s trackState=%s playState=%s trackPlaying=%s trackRate=%s " +
                "writes=%s successfulWrites=%s zeroWrites=%s firstWriteMs=%s firstWriteCount=%s writeError=%s " +
                "nonZeroInput=%s nonZeroOutput=%s result=%s",
            TAG, attemptId, reason, sampleRate, encodingName, bufferSizeBytes, bufferSamples,
            mediaProjectionReady, nativeHandleReadyAtStart, nativeHandleSeen, measurementGeneratorAtStart, processorIdleAtStart,
            recorderCreated, recorderState, recorderRecordingState, recorderStarted, recorderSampleRate,
            readCalls, successfulReads, zeroReads, firstReadMs, firstReadCount, minRead, maxReadCount, lastReadError,
            dspCalls, processedSamples, firstDspMs, bypassSeen, dspFailure, nonFiniteOutput,
            trackCreated, trackState, trackPlayState, trackPlaying, trackSampleRate,
            writeCalls, successfulWrites, zeroWrites, firstWriteMs, firstWriteCount, lastWriteError,
            nonZeroInput, nonZeroOutput, result,
        )
    }

    private fun elapsed(eventMs: Long): Long = if (eventMs < 0) -1 else eventMs - startedAtMs

    internal fun classify(): Result = when {
        !mediaProjectionReady -> Result.MEDIA_PROJECTION
        !recorderCreated -> Result.AUDIO_RECORD_CREATE
        !trackCreated -> Result.AUDIO_TRACK_CREATE
        !recorderStarted -> Result.AUDIO_RECORD_START
        !trackPlaying -> Result.AUDIO_TRACK_PLAY
        successfulReads == 0L && expectedIdle -> Result.EXPECTED_IDLE
        successfulReads == 0L -> Result.AUDIO_RECORD_READ
        dspCalls == 0L || dspFailure != null || nonFiniteOutput -> Result.DSP
        successfulWrites == 0L -> Result.AUDIO_TRACK_WRITE
        else -> Result.HEALTHY
    }

    companion object {
        const val TAG = "AudioStartup"
        const val WINDOW_MS = 1_000L
        private val nextAttemptId = AtomicLong(0L)

        fun begin(
            reason: String,
            sampleRate: Int,
            encodingName: String,
            bufferSizeBytes: Int,
            bufferSamples: Int,
            mediaProjectionReady: Boolean,
            nativeHandleReady: Boolean,
            measurementGeneratorActive: Boolean,
            processorIdle: Boolean,
        ): StartupAudioDiagnostics {
            val probe = StartupAudioDiagnostics(
                attemptId = nextAttemptId.incrementAndGet(),
                startedAtMs = SystemClock.elapsedRealtime(),
                reason = reason,
                sampleRate = sampleRate,
                encodingName = encodingName,
                bufferSizeBytes = bufferSizeBytes,
                bufferSamples = bufferSamples,
                mediaProjectionReady = mediaProjectionReady,
                nativeHandleReadyAtStart = nativeHandleReady,
                measurementGeneratorAtStart = measurementGeneratorActive,
                processorIdleAtStart = processorIdle,
            )
            Timber.i(
                "%s[%s] BEGIN reason=%s sampleRate=%s encoding=%s bufferBytes=%s bufferSamples=%s " +
                    "projection=%s nativeHandle=%s generator=%s idle=%s",
                TAG, probe.attemptId, reason, sampleRate, encodingName, bufferSizeBytes, bufferSamples,
                mediaProjectionReady, nativeHandleReady, measurementGeneratorActive, processorIdle,
            )
            return probe
        }

        fun logImmediateFailure(reason: String, result: Result, detail: String) {
            val id = nextAttemptId.incrementAndGet()
            Timber.e("%s[%s] SUMMARY reason=%s result=%s detail=%s", TAG, id, reason, result, detail)
        }
    }
}
