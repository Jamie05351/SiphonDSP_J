package app.siphondsp.service

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Per-buffer telemetry from the recorder worker for the Native truth screen: how long each
 * engine.process*() call took against the buffer's own duration (its real-time budget), and how
 * many samples each AudioRecord read returned.
 *
 * One writer -- the worker -- and lock-free readers. Every field is an atomic updated in place,
 * so recording costs the audio thread a few atomic operations per buffer and allocates nothing.
 * A reader may see one buffer's update half applied; for these counters that's harmless.
 *
 * The underrun history is separate: it's sampled from the health watchdog and read by the UI,
 * both on the main thread, never by the worker.
 */
internal class AudioThreadStats {
    private val processCalls = AtomicLong()
    private val processNanosTotal = AtomicLong()
    private val processMaxNanos = AtomicLong()
    // Maximum over the current and the previous RECENT_WINDOW_NANOS window, so the screen shows
    // whether the worst case is happening now rather than once, long ago.
    private val windowMaxNanos = AtomicLong()
    private val previousWindowMaxNanos = AtomicLong()
    private val windowStartNanos = AtomicLong()
    private val budgetNanos = AtomicLong()
    private val budgetBuckets = AtomicLongArray(BUDGET_BUCKET_COUNT)

    private val readCalls = AtomicLong()
    private val readSum = AtomicLong()
    private val readSumSquares = AtomicLong()
    private val readMin = AtomicLong(Long.MAX_VALUE)
    private val readMax = AtomicLong()
    private val readRequested = AtomicLong()
    private val shortReads = AtomicLong()

    /** Worker: start over for a new pipeline. */
    fun reset() {
        processCalls.set(0)
        processNanosTotal.set(0)
        processMaxNanos.set(0)
        windowMaxNanos.set(0)
        previousWindowMaxNanos.set(0)
        windowStartNanos.set(System.nanoTime())
        budgetNanos.set(0)
        for (i in 0 until BUDGET_BUCKET_COUNT) budgetBuckets.set(i, 0)
        readCalls.set(0)
        readSum.set(0)
        readSumSquares.set(0)
        readMin.set(Long.MAX_VALUE)
        readMax.set(0)
        readRequested.set(0)
        shortReads.set(0)
        synchronized(underrunLock) {
            underrunTotal = 0
            lastTrackUnderruns = -1
            underrunSamples = 0
        }
    }

    /** Worker: one engine.process*() call of [frames] stereo frames at [sampleRate] took [nanos]. */
    fun recordProcess(nanos: Long, frames: Int, sampleRate: Int) {
        processCalls.incrementAndGet()
        processNanosTotal.addAndGet(nanos)
        if (nanos > processMaxNanos.get()) processMaxNanos.set(nanos)
        val now = System.nanoTime()
        if (now - windowStartNanos.get() >= RECENT_WINDOW_NANOS) {
            previousWindowMaxNanos.set(windowMaxNanos.get())
            windowMaxNanos.set(0)
            windowStartNanos.set(now)
        }
        if (nanos > windowMaxNanos.get()) windowMaxNanos.set(nanos)
        if (frames <= 0 || sampleRate <= 0) return
        val budget = frames * 1_000_000_000L / sampleRate
        budgetNanos.set(budget)
        // Quarters of the budget; the last bucket is "over budget" -- a buffer that can't have
        // been written out in time unless the AudioTrack's own buffer absorbed it.
        val bucket = if (budget <= 0) BUDGET_BUCKET_COUNT - 1
            else (nanos * 4 / budget).coerceIn(0L, BUDGET_BUCKET_COUNT - 1L).toInt()
        budgetBuckets.incrementAndGet(bucket)
    }

    /** Worker: an AudioRecord read asked for [requested] samples and returned [count] (> 0). */
    fun recordRead(count: Int, requested: Int) {
        readCalls.incrementAndGet()
        readSum.addAndGet(count.toLong())
        readSumSquares.addAndGet(count.toLong() * count)
        if (count < readMin.get()) readMin.set(count.toLong())
        if (count > readMax.get()) readMax.set(count.toLong())
        readRequested.set(requested.toLong())
        if (count < requested) shortReads.incrementAndGet()
    }

    // --- Underruns: main thread only (health watchdog writes, UI reads) ----------------------

    private val underrunLock = Any()
    private var underrunTotal = 0L
    private var lastTrackUnderruns = -1
    private val underrunSampleTimes = LongArray(UNDERRUN_HISTORY)
    private val underrunSampleTotals = LongArray(UNDERRUN_HISTORY)
    private var underrunSamples = 0

    /**
     * Watchdog: the current AudioTrack reports [trackUnderruns] since it was created. The worker
     * calls [retireTrack] before replacing a track; a smaller number than last time is the
     * fallback for one replaced without it, and counts as a new track's whole count.
     */
    fun sampleUnderruns(trackUnderruns: Int, nowMs: Long) = synchronized(underrunLock) {
        addTrackUnderruns(trackUnderruns)
        val slot = underrunSamples % UNDERRUN_HISTORY
        underrunSampleTimes[slot] = nowMs
        underrunSampleTotals[slot] = underrunTotal
        underrunSamples++
    }

    /**
     * Worker: the current AudioTrack, with [finalTrackUnderruns] since it was created, is about to
     * be released. Its underruns since the last sample are counted now -- they can't be read
     * after release -- and the next sample is taken as a new track's whole count, whatever it is.
     */
    fun retireTrack(finalTrackUnderruns: Int) = synchronized(underrunLock) {
        addTrackUnderruns(finalTrackUnderruns)
        lastTrackUnderruns = -1
    }

    private fun addTrackUnderruns(trackUnderruns: Int) {
        underrunTotal += when {
            lastTrackUnderruns < 0 -> trackUnderruns
            trackUnderruns >= lastTrackUnderruns -> trackUnderruns - lastTrackUnderruns
            else -> trackUnderruns
        }
        lastTrackUnderruns = trackUnderruns
    }

    /** Underruns since the worker started, and within the last minute of watchdog samples. */
    fun underruns(nowMs: Long): Pair<Long, Long> = synchronized(underrunLock) {
        // Baseline: the total at the newest sample taken more than a minute ago. With none that
        // old, the minute reaches back past the oldest sample kept: 0 if history is complete
        // since the worker started, else the oldest stored total.
        val stored = minOf(underrunSamples, UNDERRUN_HISTORY)
        var baseline = if (underrunSamples <= UNDERRUN_HISTORY) 0L
            else underrunSampleTotals[underrunSamples.mod(UNDERRUN_HISTORY)]
        for (i in 0 until stored) {
            val slot = (underrunSamples - 1 - i).mod(UNDERRUN_HISTORY)
            if (nowMs - underrunSampleTimes[slot] > 60_000L) {
                baseline = underrunSampleTotals[slot]
                break
            }
        }
        underrunTotal to (underrunTotal - baseline)
    }

    // --- Readers -----------------------------------------------------------------------------

    val processCallCount: Long get() = processCalls.get()
    val processMeanMicros: Double
        get() = processCalls.get().let { calls ->
            if (calls == 0L) 0.0 else processNanosTotal.get() / calls / 1000.0
        }
    val processRecentMaxMicros: Double
        get() = maxOf(windowMaxNanos.get(), previousWindowMaxNanos.get()) / 1000.0
    val processMaxMicros: Double get() = processMaxNanos.get() / 1000.0
    val budgetMicros: Double get() = budgetNanos.get() / 1000.0
    val budgetBucketCounts: List<Long> get() = List(BUDGET_BUCKET_COUNT) { budgetBuckets.get(it) }

    val readCallCount: Long get() = readCalls.get()
    val readRequestedSamples: Int get() = readRequested.get().toInt()
    val readMean: Double
        get() = readCalls.get().let { calls -> if (calls == 0L) 0.0 else readSum.get().toDouble() / calls }
    val readStdDev: Double
        get() {
            val calls = readCalls.get()
            if (calls == 0L) return 0.0
            val mean = readSum.get().toDouble() / calls
            val variance = readSumSquares.get().toDouble() / calls - mean * mean
            return Math.sqrt(variance.coerceAtLeast(0.0))
        }
    val readMinSamples: Int get() = readMin.get().let { if (it == Long.MAX_VALUE) 0 else it.toInt() }
    val readMaxSamples: Int get() = readMax.get().toInt()
    val shortReadCount: Long get() = shortReads.get()

    companion object {
        const val BUDGET_BUCKET_COUNT = 5
        private const val RECENT_WINDOW_NANOS = 10_000_000_000L
        // Watchdog samples every 2 s; 40 covers the last minute with room to spare.
        private const val UNDERRUN_HISTORY = 40
    }
}
