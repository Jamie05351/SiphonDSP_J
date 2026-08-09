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
 * Each tick publishes a matched pair of buffers from the same instant: the dry (pre-DSP) signal
 * and the wet (post-DSP) signal. Both are analyzed into independent traces so a graph can show
 * the two together -- the gap between them at any frequency is what the active filters are
 * actually doing to the live audio, not just a single post-processing snapshot with nothing to
 * compare it to.
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
    private var feedDryScratch: FloatArray? = null
    private var feedWetScratch: FloatArray? = null
    private var feedLength = 0
    private var feedSampleRate = 48000

    @Volatile
    private var activationCount = 0
    val isActive: Boolean get() = activationCount > 0

    /** Publishes one tick's dry (pre-DSP) and wet (post-DSP) interleaved buffers as a pair. */
    fun publish(dryInterleaved: FloatArray, wetInterleaved: FloatArray, length: Int, sampleRate: Int) {
        if (activationCount <= 0) return
        synchronized(feedLock) {
            feedDryScratch = growIfNeeded(feedDryScratch, length).also {
                System.arraycopy(dryInterleaved, 0, it, 0, length)
            }
            feedWetScratch = growIfNeeded(feedWetScratch, length).also {
                System.arraycopy(wetInterleaved, 0, it, 0, length)
            }
            feedLength = length
            feedSampleRate = sampleRate
        }
    }

    private fun growIfNeeded(existing: FloatArray?, length: Int): FloatArray =
        if (existing == null || existing.size < length) FloatArray(length) else existing

    // --- Consumer side (background analyzer thread) ---
    private val dryChannel = SpectrumChannel()
    private val wetChannel = SpectrumChannel()
    @Volatile private var analyzedSampleRate = 48000
    private var thread: Thread? = null
    @Volatile private var running = false

    // Per-channel level meters, computed on this same background analyzer thread from the wet
    // (post-DSP) buffer -- these reflect actual output level, not the dry comparison signal.
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
            // the channels, which neither thread synchronizes on.
            try {
                thread?.join(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            thread = null
            dryChannel.reset()
            wetChannel.reset()
            publishedLeftPeakDb = LEVEL_FLOOR_DB
            publishedLeftRmsDb = LEVEL_FLOOR_DB
            publishedRightPeakDb = LEVEL_FLOOR_DB
            publishedRightRmsDb = LEVEL_FLOOR_DB
        }
    }

    private fun runLoop() {
        val dryDrain = FloatArray(FFT_LEN * 2)
        val wetDrain = FloatArray(FFT_LEN * 2)
        while (running) {
            val consumed = drainInto(dryDrain, wetDrain)
            if (consumed > 0) {
                dryChannel.feed(dryDrain, consumed, fft)
                wetChannel.feed(wetDrain, consumed, fft)
                updateLevelsFromInterleaved(wetDrain, consumed)
            }
            try {
                Thread.sleep(16L)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun drainInto(dryDest: FloatArray, wetDest: FloatArray): Int {
        synchronized(feedLock) {
            val len = feedLength
            if (len <= 0) return 0
            val dry = feedDryScratch ?: return 0
            val wet = feedWetScratch ?: return 0
            val n = len.coerceAtMost(dryDest.size).coerceAtMost(wetDest.size)
            System.arraycopy(dry, 0, dryDest, 0, n)
            System.arraycopy(wet, 0, wetDest, 0, n)
            feedLength = 0
            analyzedSampleRate = feedSampleRate
            return n
        }
    }

    private fun updateLevelsFromInterleaved(interleaved: FloatArray, length: Int) {
        var leftPeak = 0f
        var rightPeak = 0f
        var leftSumSq = 0.0
        var rightSumSq = 0.0
        var pairCount = 0
        var i = 0
        while (i + 1 < length) {
            val left = interleaved[i]
            val right = interleaved[i + 1]
            val absLeft = abs(left)
            val absRight = abs(right)
            if (absLeft > leftPeak) leftPeak = absLeft
            if (absRight > rightPeak) rightPeak = absRight
            leftSumSq += (left * left).toDouble()
            rightSumSq += (right * right).toDouble()
            pairCount++
            i += 2
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
    // the spectrum bin smoothing below.
    private fun smoothLevel(prev: Float, db: Float): Float =
        if (db > prev) prev + (db - prev) * 0.6f else prev * 0.90f + db * 0.10f

    private fun amplitudeToDbFs(amplitude: Float): Float {
        if (amplitude <= 1e-6f) return LEVEL_FLOOR_DB
        return (20.0 * log10(amplitude.toDouble())).toFloat().coerceIn(LEVEL_FLOOR_DB, 0f)
    }

    /** Smoothed magnitude in dB of the post-DSP ("wet") signal, clamped to [FLOOR_DB, CEILING_DB]. */
    fun magnitudeDbAt(frequencyHz: Double): Float = wetChannel.magnitudeDbAt(frequencyHz, analyzedSampleRate)

    /** Smoothed magnitude in dB of the pre-DSP ("dry") signal, clamped to [FLOOR_DB, CEILING_DB]. */
    fun dryMagnitudeDbAt(frequencyHz: Double): Float = dryChannel.magnitudeDbAt(frequencyHz, analyzedSampleRate)

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

    /** One trace's hop-buffer + smoothed magnitude state. There is one of these per dry/wet side. */
    private class SpectrumChannel {
        private val analyzeBuffer = FloatArray(FFT_LEN)
        private var analyzeWritePos = 0
        private val workingMagnitudeDb = FloatArray(FFT_LEN / 2 + 1) { FLOOR_DB }
        // Published as an immutable snapshot so the UI thread never observes a half-updated array.
        @Volatile private var publishedMagnitudeDb = workingMagnitudeDb.copyOf()

        /** Downmixes interleaved stereo to mono and runs a hop-based STFT as the ring buffer fills. */
        fun feed(interleaved: FloatArray, length: Int, fft: FFT) {
            var i = 0
            while (i + 1 < length) {
                analyzeBuffer[analyzeWritePos++] = (interleaved[i] + interleaved[i + 1]) * 0.5f
                i += 2
                if (analyzeWritePos == FFT_LEN) {
                    computeFrame(fft)
                    System.arraycopy(analyzeBuffer, HOP_LEN, analyzeBuffer, 0, FFT_LEN - HOP_LEN)
                    analyzeWritePos = FFT_LEN - HOP_LEN
                }
            }
        }

        private fun computeFrame(fft: FFT) {
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

        fun magnitudeDbAt(frequencyHz: Double, sampleRate: Int): Float {
            val snapshot = publishedMagnitudeDb
            val bin = (frequencyHz * FFT_LEN / sampleRate).toInt().coerceIn(0, snapshot.size - 1)
            return snapshot[bin].coerceIn(FLOOR_DB, CEILING_DB)
        }

        fun reset() {
            analyzeWritePos = 0
            workingMagnitudeDb.fill(FLOOR_DB)
            publishedMagnitudeDb = FloatArray(workingMagnitudeDb.size) { FLOOR_DB }
        }
    }
}
