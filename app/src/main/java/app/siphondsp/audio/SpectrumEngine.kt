package app.siphondsp.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Live spectrum for the PEQ graph overlay.
 *
 * The real-time audio thread (RootlessAudioProcessorService.runRecorderLoop) is the producer:
 * publish() takes a short-held lock to copy the just-processed buffer into a scratch array and
 * returns immediately. It never blocks on I/O and never allocates once the scratch buffer has
 * grown to the session's buffer size, so it cannot stall audio processing the way holding a lock
 * across disk/JNI calls would. All FFT work happens on a dedicated background thread instead.
 *
 * Consumers (PEQ graph views) reference-count activation with acquire()/release() so the analyzer
 * thread only runs while at least one graph is actually showing the overlay.
 */
object SpectrumEngine {
    private const val FFT_LEN = 2048
    private const val HOP_LEN = 1024
    const val FLOOR_DB = -80f
    const val CEILING_DB = 0f
    const val LEVEL_FLOOR_DB = -80f

    private val fft = FFT(FFT_LEN).apply { initHannWindow(FFT_LEN) }

    // --- Producer side (audio thread) ---
    private val feedLock = Any()
    private var feedScratch: FloatArray? = null
    private var feedLength = 0
    private var feedSampleRate = 48000

    @Volatile
    private var activationCount = 0
    val isActive: Boolean get() = activationCount > 0

    fun publish(interleavedStereo: FloatArray, length: Int, sampleRate: Int) {
        if (activationCount <= 0) return
        synchronized(feedLock) {
            var dest = feedScratch
            if (dest == null || dest.size < length) {
                dest = FloatArray(length)
                feedScratch = dest
            }
            System.arraycopy(interleavedStereo, 0, dest, 0, length)
            feedLength = length
            feedSampleRate = sampleRate
        }
    }

    // --- Consumer side (background analyzer thread) ---
    private val analyzeBuffer = FloatArray(FFT_LEN)
    private var analyzeWritePos = 0
    private val workingMagnitudeDb = FloatArray(FFT_LEN / 2 + 1) { FLOOR_DB }
    // Published as an immutable snapshot so the UI thread never observes a half-updated array.
    @Volatile private var publishedMagnitudeDb = workingMagnitudeDb.copyOf()
    @Volatile private var analyzedSampleRate = 48000
    private var thread: Thread? = null
    @Volatile private var running = false

    // Per-channel level meters, computed on this same background analyzer thread from the
    // still-interleaved buffer inside feedAnalysis() before it gets mono-downmixed -- zero
    // additional audio-thread cost, zero additional allocation.
    @Volatile private var publishedLeftPeakDb = LEVEL_FLOOR_DB
    @Volatile private var publishedLeftRmsDb = LEVEL_FLOOR_DB
    @Volatile private var publishedRightPeakDb = LEVEL_FLOOR_DB
    @Volatile private var publishedRightRmsDb = LEVEL_FLOOR_DB

    @Synchronized
    fun acquire() {
        activationCount++
        if (thread == null) {
            running = true
            thread = Thread({ runLoop() }, "SiphonDSP-Spectrum").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    fun release() {
        if (activationCount == 0) return
        activationCount--
        if (activationCount == 0) {
            running = false
            thread?.interrupt()
            // Wait for the loop to actually exit before returning: it only ever sleeps for 16ms
            // at a time, so this is a short, bounded wait — but skipping it would let a quick
            // release()+acquire() start a second thread while the old one is still touching
            // analyzeBuffer/analyzeWritePos, which neither thread synchronizes on.
            try {
                thread?.join(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            thread = null
            // Only swap in a fresh array (a new allocation, not a mutation of the buffer the
            // analyzer thread may still briefly be touching) — avoids racing with computeFrame().
            publishedMagnitudeDb = FloatArray(workingMagnitudeDb.size) { FLOOR_DB }
            publishedLeftPeakDb = LEVEL_FLOOR_DB
            publishedLeftRmsDb = LEVEL_FLOOR_DB
            publishedRightPeakDb = LEVEL_FLOOR_DB
            publishedRightRmsDb = LEVEL_FLOOR_DB
        }
    }

    private fun runLoop() {
        val drainBuffer = FloatArray(FFT_LEN * 2)
        while (running) {
            val consumed = drainInto(drainBuffer)
            if (consumed > 0) feedAnalysis(drainBuffer, consumed)
            try {
                Thread.sleep(16L)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun drainInto(dest: FloatArray): Int {
        synchronized(feedLock) {
            val len = feedLength
            if (len <= 0) return 0
            val src = feedScratch ?: return 0
            val n = len.coerceAtMost(dest.size)
            System.arraycopy(src, 0, dest, 0, n)
            feedLength = 0
            analyzedSampleRate = feedSampleRate
            return n
        }
    }

    /**
     * Downmixes interleaved stereo to mono and runs a hop-based STFT as the ring buffer fills.
     * Also accumulates per-channel peak/RMS over this block for the gain meters, while the
     * interleaved L/R samples are still available (before the mono downmix).
     */
    private fun feedAnalysis(interleaved: FloatArray, length: Int) {
        var leftPeak = 0f
        var rightPeak = 0f
        var leftSumSq = 0.0
        var rightSumSq = 0.0
        var pairCount = 0
        var i = 0
        while (i + 1 < length) {
            val left = interleaved[i]
            val right = interleaved[i + 1]
            analyzeBuffer[analyzeWritePos++] = (left + right) * 0.5f
            val absLeft = abs(left)
            val absRight = abs(right)
            if (absLeft > leftPeak) leftPeak = absLeft
            if (absRight > rightPeak) rightPeak = absRight
            leftSumSq += (left * left).toDouble()
            rightSumSq += (right * right).toDouble()
            pairCount++
            i += 2
            if (analyzeWritePos == FFT_LEN) {
                computeFrame()
                System.arraycopy(analyzeBuffer, HOP_LEN, analyzeBuffer, 0, FFT_LEN - HOP_LEN)
                analyzeWritePos = FFT_LEN - HOP_LEN
            }
        }
        if (pairCount > 0) {
            updateLevels(
                leftPeak, rightPeak,
                sqrt(leftSumSq / pairCount).toFloat(),
                sqrt(rightSumSq / pairCount).toFloat(),
            )
        }
    }

    private fun updateLevels(leftPeak: Float, rightPeak: Float, leftRms: Float, rightRms: Float) {
        publishedLeftPeakDb = smoothLevel(publishedLeftPeakDb, amplitudeToDbFs(leftPeak))
        publishedRightPeakDb = smoothLevel(publishedRightPeakDb, amplitudeToDbFs(rightPeak))
        publishedLeftRmsDb = smoothLevel(publishedLeftRmsDb, amplitudeToDbFs(leftRms))
        publishedRightRmsDb = smoothLevel(publishedRightRmsDb, amplitudeToDbFs(rightRms))
    }

    // Rise fast (transients read immediately), decay slower (readable peaks) -- same shape as
    // the spectrum bin smoothing above.
    private fun smoothLevel(prev: Float, db: Float): Float =
        if (db > prev) prev + (db - prev) * 0.6f else prev * 0.90f + db * 0.10f

    private fun amplitudeToDbFs(amplitude: Float): Float {
        if (amplitude <= 1e-6f) return LEVEL_FLOOR_DB
        return (20.0 * log10(amplitude.toDouble())).toFloat().coerceIn(LEVEL_FLOOR_DB, 0f)
    }

    private fun computeFrame() {
        val windowed = fft.applyWindow(analyzeBuffer)
        val power = fft.computePowerSpectrum(windowed)
        for (bin in power.indices) {
            val db = (10.0 * log10(power[bin].coerceAtLeast(1e-18))).toFloat()
            val prev = workingMagnitudeDb[bin]
            // Rise fast (transients read immediately), decay slower (readable peaks).
            workingMagnitudeDb[bin] = if (db > prev) prev + (db - prev) * 0.6f else prev * 0.90f + db * 0.10f
        }
        publishedMagnitudeDb = workingMagnitudeDb.copyOf()
    }

    /** Smoothed magnitude in dB, clamped to [FLOOR_DB, CEILING_DB], at the given frequency. */
    fun magnitudeDbAt(frequencyHz: Double): Float {
        val snapshot = publishedMagnitudeDb
        val bin = (frequencyHz * FFT_LEN / analyzedSampleRate).toInt().coerceIn(0, snapshot.size - 1)
        return snapshot[bin].coerceIn(FLOOR_DB, CEILING_DB)
    }

    /**
     * Fills [out] with `[leftPeakDb, leftRmsDb, rightPeakDb, rightRmsDb]` in dBFS.
     * Allocation-free; [out] must have size >= 4.
     */
    fun channelLevelsInto(out: FloatArray) {
        out[0] = publishedLeftPeakDb
        out[1] = publishedLeftRmsDb
        out[2] = publishedRightPeakDb
        out[3] = publishedRightRmsDb
    }
}
