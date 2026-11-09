package app.siphondsp.audio

import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Live spectrum for the PEQ graph overlay.
 *
 * The real-time audio thread (RootlessAudioProcessorService.runRecorderLoop) is the producer:
 * captureDry() and publishWet() copy the buffer before and after processing into a frame of a
 * lock-free triple buffer and return. They never lock, block or allocate, so the analyser can
 * never stall the audio thread. All FFT work happens on a dedicated background thread instead.
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
    // 16384-point window at 48 kHz -> ~2.9 Hz/bin, which finally puts real resolution in the
    // bottom two octaves where the filters do the most work (2048 gave 23 Hz/bin -- barely a
    // handful of points below 200 Hz). The FFT runs on the MIN_PRIORITY analyzer thread only
    // while a graph is showing the overlay, so the extra cost never touches the audio path.
    private const val FFT_LEN = 16384
    private const val HOP_LEN = 2048
    const val FLOOR_DB = -80f
    const val CEILING_DB = 0f
    const val LEVEL_FLOOR_DB = -80f

    // Fractional-octave magnitude smoothing (1/12 oct: half-width +/- 1/24 oct). Applied in the
    // frequency domain per frame so the trace reads as an RTA-style envelope instead of raw FFT
    // grass, while still keeping enough detail to see individual filter action.
    private val SMOOTH_LO = 2.0.pow(-1.0 / 24.0)
    private val SMOOTH_HI = 2.0.pow(1.0 / 24.0)

    private val fft = FFT(FFT_LEN).apply { initHannWindow(FFT_LEN) }

    // --- Producer side (audio thread) ---
    // One matched dry/wet pair. FRAME_CAPACITY samples is the analyser's whole input window, so
    // a longer buffer is cut to that (it only ever looks at the latest audio anyway).
    private class Frame {
        val dry = FloatArray(FRAME_CAPACITY)
        val wet = FloatArray(FRAME_CAPACITY)
        var length = 0
        var sampleRate = 48000
    }
    private const val FRAME_CAPACITY = FFT_LEN * 2

    // Triple buffer. The producer fills frames[back], then swaps it with the middle slot and
    // marks that fresh; the consumer swaps its frames[front] with a fresh middle. The middle
    // slot index and the fresh bit share one atomic, so neither side ever waits for the other,
    // and getAndSet() publishes the frame's contents along with it. A newer buffer replaces one
    // the consumer hasn't taken yet, as the old single-slot mailbox did.
    private const val SLOT_MASK = 0b011
    private const val FRESH = 0b100
    // Allocated on the first acquire() (main thread), before any producer can see isActive.
    @Volatile private var frames: Array<Frame>? = null
    private val middle = AtomicInteger(1)
    private var back = 0   // producer (audio thread) only
    private var front = 2  // consumer (analyser thread) only
    // Set by captureDry() for the buffer being processed; publishWet() publishes only after it.
    private var dryCaptured = false

    @Volatile
    private var activationCount = 0
    val isActive: Boolean get() = activationCount > 0

    /** Copies one buffer's dry (pre-DSP) interleaved samples; call before processing it. */
    fun captureDry(interleaved: FloatBuffer, length: Int) {
        val frame = producerFrame() ?: return
        val n = length.coerceIn(0, minOf(FRAME_CAPACITY, interleaved.limit()))
        for (i in 0 until n) frame.dry[i] = interleaved.get(i)
        frame.length = n
        dryCaptured = true
    }

    fun captureDry(interleaved: ShortBuffer, length: Int) {
        val frame = producerFrame() ?: return
        val n = length.coerceIn(0, minOf(FRAME_CAPACITY, interleaved.limit()))
        for (i in 0 until n) frame.dry[i] = interleaved.get(i) / 32768f
        frame.length = n
        dryCaptured = true
    }

    /**
     * Copies the same buffer's wet (post-DSP) samples and publishes the pair. Does nothing unless
     * [captureDry] ran for this buffer.
     */
    fun publishWet(interleaved: FloatBuffer, sampleRate: Int) {
        val frame = takeDryFrame() ?: return
        for (i in 0 until frame.length) frame.wet[i] = interleaved.get(i)
        publish(frame, sampleRate)
    }

    fun publishWet(interleaved: ShortBuffer, sampleRate: Int) {
        val frame = takeDryFrame() ?: return
        for (i in 0 until frame.length) frame.wet[i] = interleaved.get(i) / 32768f
        publish(frame, sampleRate)
    }

    private fun producerFrame(): Frame? {
        if (activationCount <= 0) return null
        return frames?.get(back)
    }

    private fun takeDryFrame(): Frame? {
        if (!dryCaptured) return null
        dryCaptured = false
        return frames?.get(back)
    }

    private fun publish(frame: Frame, sampleRate: Int) {
        frame.sampleRate = sampleRate
        back = middle.getAndSet(back or FRESH) and SLOT_MASK
    }

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
        if (frames == null) frames = Array(3) { Frame() }
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
        val all = frames ?: return 0
        if ((middle.get() and FRESH) == 0) return 0
        front = middle.getAndSet(front) and SLOT_MASK
        val frame = all[front]
        val n = frame.length.coerceAtMost(dryDest.size).coerceAtMost(wetDest.size)
        System.arraycopy(frame.dry, 0, dryDest, 0, n)
        System.arraycopy(frame.wet, 0, wetDest, 0, n)
        analyzedSampleRate = frame.sampleRate
        return n
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

        // Per-frame scratch, reused so the 8x larger window doesn't turn into 8x the garbage:
        // one windowed-sample buffer, one linear power spectrum, and a prefix sum of that power
        // used for the O(n) fractional-octave smoothing pass.
        private val windowScratch = DoubleArray(FFT_LEN)
        private val powerScratch = DoubleArray(FFT_LEN / 2 + 1)
        private val powerPrefix = DoubleArray(FFT_LEN / 2 + 2)

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
            fft.applyWindowInto(analyzeBuffer, windowScratch)
            fft.computePowerSpectrumInto(windowScratch, powerScratch)
            val n = powerScratch.size
            // Prefix sum of linear power -> any [lo, hi] bin band averages in O(1).
            powerPrefix[0] = 0.0
            for (i in 0 until n) powerPrefix[i + 1] = powerPrefix[i] + powerScratch[i]
            for (bin in 0 until n) {
                val lo = floor(bin * SMOOTH_LO).toInt().coerceIn(0, n - 1)
                val hi = ceil(bin * SMOOTH_HI).toInt().coerceIn(lo, n - 1)
                val avgPower = (powerPrefix[hi + 1] - powerPrefix[lo]) / (hi - lo + 1)
                val db = (10.0 * log10(avgPower.coerceAtLeast(1e-18))).toFloat()
                val prev = workingMagnitudeDb[bin]
                // Rise fast (transients read immediately), decay slower (readable peaks).
                workingMagnitudeDb[bin] = if (db > prev) prev + (db - prev) * 0.6f else prev * 0.90f + db * 0.10f
            }
            publishedMagnitudeDb = workingMagnitudeDb.copyOf()
        }

        fun magnitudeDbAt(frequencyHz: Double, sampleRate: Int): Float {
            val snapshot = publishedMagnitudeDb
            // Linear-interpolate between adjacent bins. On the plot's log x-axis the deep bass
            // spreads only a handful of bins across many pixels, so nearest-bin snapping there
            // visibly stair-steps the trace; interpolation smooths it without faking resolution.
            val exactBin = (frequencyHz * FFT_LEN / sampleRate).toFloat()
            if (exactBin <= 0f) return snapshot[0].coerceIn(FLOOR_DB, CEILING_DB)
            val lo = exactBin.toInt()
            if (lo >= snapshot.size - 1) return snapshot[snapshot.size - 1].coerceIn(FLOOR_DB, CEILING_DB)
            val frac = exactBin - lo
            val interpolated = snapshot[lo] + (snapshot[lo + 1] - snapshot[lo]) * frac
            return interpolated.coerceIn(FLOOR_DB, CEILING_DB)
        }

        fun reset() {
            analyzeWritePos = 0
            workingMagnitudeDb.fill(FLOOR_DB)
            publishedMagnitudeDb = FloatArray(workingMagnitudeDb.size) { FLOOR_DB }
        }
    }
}
