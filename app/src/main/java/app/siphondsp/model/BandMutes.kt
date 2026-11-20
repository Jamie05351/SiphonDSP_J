package app.siphondsp.model

import app.siphondsp.model.NativeBmwDspValues.FIELD_MUTE

/**
 * Per-side band mutes for the Signal Generator page, built on the per-output mute flags
 * (`FIELD_MUTE`) the engine already applies to each of the six outputs.
 *
 * Muting a band on one side only lets a band be soloed on the measured side while the other side
 * keeps playing every band, e.g. so a REW acoustic timing reference on that side stays intact.
 * Unlike band isolation (measurement mute) this adds no measurement-bus filter, so the soloed
 * band's phase is exactly what it is in normal playback.
 *
 * Sides are logical channels, the same as the Channels control: Left is the car's left (the
 * deliberate hardware L/R swap at the end of the chain is already accounted for).
 */
object BandMutes {
    enum class Band { LOW, MID, HIGH }

    /** Which side(s) of a band are muted. [label] is the segmented-control text. */
    enum class Side(val label: String) {
        NONE("OFF"),
        BOTH("L+R"),
        LEFT("L"),
        RIGHT("R"),
    }

    /** The (left, right) per-output mute indices of [band]. */
    fun muteIndices(band: Band): Pair<Int, Int> = when (band) {
        Band.LOW -> NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, FIELD_MUTE) to
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, FIELD_MUTE)
        Band.MID -> NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, FIELD_MUTE) to
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, FIELD_MUTE)
        Band.HIGH -> NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, FIELD_MUTE) to
            NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_RIGHT, FIELD_MUTE)
    }

    /** Legacy whole-band mute slot kept in step (Low and Mid only; High never had one). */
    private fun aggregateIndex(band: Band): Int? = when (band) {
        Band.LOW -> NativeBmwDspValues.INDEX_LOW_MUTE
        Band.MID -> NativeBmwDspValues.INDEX_MID_MUTE
        Band.HIGH -> null
    }

    /** Reads the current side state from the per-output flags (what the engine actually runs). */
    fun side(band: Band, value: (Int) -> Float): Side {
        val (l, r) = muteIndices(band)
        val left = value(l) >= 0.5f
        val right = value(r) >= 0.5f
        return when {
            left && right -> Side.BOTH
            left -> Side.LEFT
            right -> Side.RIGHT
            else -> Side.NONE
        }
    }

    /**
     * The config writes for muting [band] on [side]. The legacy whole-band slot is 1 only when
     * both sides are muted, so it never claims a mute the engine isn't applying on both sides.
     */
    fun updates(band: Band, side: Side): Map<Int, Float> {
        val (l, r) = muteIndices(band)
        val left = side == Side.BOTH || side == Side.LEFT
        val right = side == Side.BOTH || side == Side.RIGHT
        val out = linkedMapOf(l to (if (left) 1f else 0f), r to (if (right) 1f else 0f))
        aggregateIndex(band)?.let { out[it] = if (left && right) 1f else 0f }
        return out
    }
}
