package app.siphondsp.service

import app.siphondsp.model.DspStage
import app.siphondsp.model.debug.EngineStatus
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * The first buffers of every stream start, for Native DSP Truth: what came in, what went out,
 * and what the engine was set to at the time. Diagnostic only: it reads the loop's buffers and
 * changes nothing in them.
 *
 * A start is each time the audio loop (re)starts its AudioTrack: the service starting, a resume
 * after the idle suspend, or a pipeline rebuild. For its first [BUFFERS] buffers (about a second
 * at the usual 80 ms block) the loop records each one's input and output peak and whether the
 * engine processed it or it went out as captured. After the first buffer, the engine's own
 * status (master volume, headroom, the convolver IR running or not) and the PEQ preamp.
 *
 * Written by the audio thread into preallocated slots (no allocation per buffer; one small
 * status read per start); read by the screen, which may catch a slot mid-write -- harmless for a
 * diagnostic, the next refresh shows it whole.
 */
object StreamStartLog {
    const val STARTS = 6
    const val BUFFERS = 14

    private class Slot {
        @Volatile var seq = 0L
        var atMs = 0L
        var reason = ""
        var sampleRate = 0
        var bufferSamples = 0
        var float = true
        var count = 0
        val tMs = IntArray(BUFFERS)
        val samples = IntArray(BUFFERS)
        val inPeakDb = FloatArray(BUFFERS)
        val outPeakDb = FloatArray(BUFFERS)
        val processed = BooleanArray(BUFFERS)
        val generated = BooleanArray(BUFFERS)
        // From the engine, after the first buffer.
        var statusRead = false
        var volumeDb = 0f
        var muted = false
        var headroomDb = 0f
        var convolverRunning = false
        var neutral = false
        var preampDb = 0f
        var peqEnabled = false
        var preEqRunning = false
    }

    private val slots = Array(STARTS) { Slot() }
    @Volatile private var next = 0
    @Volatile private var active: Slot? = null
    private var seqCounter = 0L

    /** Audio thread: whether the current buffer is still being recorded (peaks are worth taking). */
    val recording: Boolean get() = active?.let { it.count < BUFFERS } ?: false

    /** Audio thread: a stream (re)starts now. */
    fun begin(reason: String, sampleRate: Int, bufferSamples: Int, float: Boolean, nowMs: Long) {
        val slot = slots[next]
        next = (next + 1) % STARTS
        slot.count = 0
        // A reused slot: nothing of the start it held may show as this one's engine reading.
        slot.statusRead = false
        slot.volumeDb = 0f
        slot.muted = false
        slot.headroomDb = 0f
        slot.convolverRunning = false
        slot.neutral = false
        slot.preampDb = 0f
        slot.peqEnabled = false
        slot.preEqRunning = false
        slot.atMs = nowMs
        slot.reason = reason
        slot.sampleRate = sampleRate
        slot.bufferSamples = bufferSamples
        slot.float = float
        slot.seq = ++seqCounter
        active = slot
    }

    /** Audio thread: one buffer of the active start (no-op once [BUFFERS] are in). */
    fun record(nowMs: Long, samples: Int, inPeakDb: Float, outPeakDb: Float, processed: Boolean, generated: Boolean) {
        val slot = active ?: return
        val i = slot.count
        if (i >= BUFFERS) {
            active = null
            return
        }
        slot.tMs[i] = (nowMs - slot.atMs).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        slot.samples[i] = samples
        slot.inPeakDb[i] = inPeakDb
        slot.outPeakDb[i] = outPeakDb
        slot.processed[i] = processed
        slot.generated[i] = generated
        slot.count = i + 1
        // Kept active past the last buffer for one more try at the engine's status (see
        // recordEngine); then it's left unknown.
        if (slot.count >= BUFFERS && slot.statusRead) active = null
    }

    /** Audio thread, after the active start's first buffer: what the engine was running. */
    fun recordEngine(status: EngineStatus?, peqEnabled: Boolean, preampDb: Float) {
        val slot = active ?: return
        if (slot.statusRead) return
        if (status == null) {
            // No reading (no handle, or nothing installed yet): try again after the next buffer;
            // past the last one the start stays "no status", never another start's values.
            if (slot.count >= BUFFERS) active = null
            return
        }
        slot.peqEnabled = peqEnabled
        slot.preampDb = preampDb
        // The preamp only counts while the engine runs the Pre EQ (measurement neutral bypasses
        // it while the saved PEQ stays enabled).
        slot.preEqRunning = status.isActive(DspStage.PRE_EQ)
        slot.volumeDb = status.masterVolumeDb
        slot.muted = status.masterMute
        slot.headroomDb = status.headroomDb
        slot.convolverRunning = status.isActive(DspStage.CONVOLVER)
        slot.neutral = status.measurementNeutral
        slot.statusRead = true
        if (slot.count >= BUFFERS) active = null
    }

    /** Whether the active start still wants the engine's status. */
    val wantsEngine: Boolean get() = active?.let { !it.statusRead && it.count >= 1 } ?: false

    /** The peak of the first [count] samples, dBFS (-120 for silence). */
    fun peakDb(buffer: ShortBuffer, count: Int): Float {
        var m = 0
        for (i in 0 until minOf(count, buffer.limit())) m = max(m, abs(buffer.get(i).toInt()))
        return toDb(m / 32768f)
    }

    fun peakDb(buffer: FloatBuffer, count: Int): Float {
        var m = 0f
        for (i in 0 until minOf(count, buffer.limit())) {
            val v = abs(buffer.get(i))
            if (v > m || v.isNaN()) m = if (v.isNaN()) Float.POSITIVE_INFINITY else v
        }
        return toDb(m)
    }

    private fun toDb(linear: Float): Float = if (linear <= 1e-6f) -120f else (20f * log10(linear))

    // ---- reader side ---------------------------------------------------------------------

    data class Buffer(
        val tMs: Int,
        val samples: Int,
        val inPeakDb: Float,
        val outPeakDb: Float,
        val processed: Boolean,
        val generated: Boolean,
    ) {
        /** Output over input in dB; null while either is silent. */
        val gainDb: Float? get() = if (inPeakDb <= -119f || outPeakDb <= -119f) null else outPeakDb - inPeakDb
    }

    data class Start(
        val atMs: Long,
        val reason: String,
        val sampleRate: Int,
        val bufferSamples: Int,
        val float: Boolean,
        val engineRead: Boolean,
        val volumeDb: Float,
        val muted: Boolean,
        val headroomDb: Float,
        val convolverRunning: Boolean,
        val neutral: Boolean,
        val peqEnabled: Boolean,
        val preampDb: Float,
        val preEqRunning: Boolean,
        val buffers: List<Buffer>,
    ) {
        /** The level change the engine's own gains should give (volume + headroom + the preamp
         *  while the Pre EQ runs), dB. */
        val expectedGainDb: Float get() = volumeDb + headroomDb + if (peqEnabled && preEqRunning) preampDb else 0f

        /**
         * A buffer that came out louder than the settings allow: more than [TOLERANCE_DB] over the
         * expected gain (EQ boosts, the convolver and Deep bass can add some, so a little is
         * normal), or passed through unprocessed while the volume cuts.
         */
        fun suspicious(b: Buffer): Boolean {
            // Non-finite or over full scale out is always wrong (a NaN reads as +inf here, and
            // inf - inf would hide it from the gain test below).
            if (!b.outPeakDb.isFinite() || b.outPeakDb > 0.5f) return true
            val audible = b.outPeakDb > -119f
            // Raw out with the volume cutting, or with nothing known about the engine.
            if (!b.processed) return audible && (!engineRead || volumeDb < -3f || muted)
            if (!engineRead) return false
            // Anything audible while the engine reports master mute.
            if (muted) return audible
            val gain = b.gainDb ?: return false
            return gain > expectedGainDb + TOLERANCE_DB
        }
    }

    const val TOLERANCE_DB = 12f

    /** The recorded starts, newest first. */
    fun snapshot(): List<Start> {
        val out = ArrayList<Start>(STARTS)
        for (k in 1..STARTS) {
            val slot = slots[(next - k + STARTS * 2) % STARTS]
            if (slot.seq == 0L) continue
            val n = slot.count.coerceIn(0, BUFFERS)
            out += Start(
                atMs = slot.atMs,
                reason = slot.reason,
                sampleRate = slot.sampleRate,
                bufferSamples = slot.bufferSamples,
                float = slot.float,
                engineRead = slot.statusRead,
                volumeDb = slot.volumeDb,
                muted = slot.muted,
                headroomDb = slot.headroomDb,
                convolverRunning = slot.convolverRunning,
                neutral = slot.neutral,
                peqEnabled = slot.peqEnabled,
                preampDb = slot.preampDb,
                preEqRunning = slot.preEqRunning,
                buffers = List(n) { i ->
                    Buffer(slot.tMs[i], slot.samples[i], slot.inPeakDb[i], slot.outPeakDb[i], slot.processed[i], slot.generated[i])
                },
            )
        }
        return out
    }
}
