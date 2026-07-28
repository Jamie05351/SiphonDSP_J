package app.siphondsp.view

/**
 * Peak-hold decay for a single level meter channel. Purely a view-side/UI-thread concern --
 * the actual level computation happens on SpectrumEngine's background analyzer thread; this
 * class only tracks how the hold marker decays over time. The clock is injected via [update]'s
 * `nowMs` parameter so decay behavior is testable without a real Looper.
 */
class PeakHoldMeter(
    private val holdMs: Long = 1200L,
    private val decayDbPerSecond: Float = 24f,
    private val floorDb: Float = -80f,
) {
    var peakDb: Float = floorDb
        private set
    var rmsDb: Float = floorDb
        private set
    var holdDb: Float = floorDb
        private set

    private var holdSetAtMs: Long = 0L

    fun update(instantPeakDb: Float, instantRmsDb: Float, nowMs: Long) {
        peakDb = instantPeakDb
        rmsDb = instantRmsDb
        if (instantPeakDb >= holdDb) {
            holdDb = instantPeakDb
            holdSetAtMs = nowMs
        } else {
            val elapsedSinceHold = nowMs - holdSetAtMs
            if (elapsedSinceHold > holdMs) {
                val decaySeconds = (elapsedSinceHold - holdMs) / 1000f
                val decayed = holdDb - decayDbPerSecond * decaySeconds
                holdDb = decayed.coerceAtLeast(instantPeakDb).coerceAtLeast(floorDb)
            }
        }
    }

    fun reset() {
        peakDb = floorDb
        rmsDb = floorDb
        holdDb = floorDb
        holdSetAtMs = 0L
    }

    companion object {
        /** Maps a dB value onto a 0..1 fraction of [floorDb]..[ceilingDb], clamped. */
        fun fractionFor(db: Float, floorDb: Float, ceilingDb: Float): Float {
            val span = ceilingDb - floorDb
            if (span <= 0f) return 0f
            return ((db - floorDb) / span).coerceIn(0f, 1f)
        }
    }
}
