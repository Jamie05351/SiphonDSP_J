package app.siphondsp.model

import app.siphondsp.model.NativeBmwDspValues.FIELD_CROSSOVER_FREQ
import app.siphondsp.model.NativeBmwDspValues.FIELD_CROSSOVER_TYPE
import app.siphondsp.model.NativeBmwDspValues.FIELD_MUTE
import app.siphondsp.model.NativeBmwDspValues.INDEX_HIGH_XO_PASS
import app.siphondsp.model.NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED
import app.siphondsp.model.NativeBmwDspValues.MID_UPPER_XO_FIELD_FREQ
import app.siphondsp.model.NativeBmwDspValues.OUTPUT_HIGH_LEFT
import app.siphondsp.model.NativeBmwDspValues.OUTPUT_HIGH_RIGHT
import app.siphondsp.model.NativeBmwDspValues.OUTPUT_MID_LEFT
import app.siphondsp.model.NativeBmwDspValues.OUTPUT_MID_RIGHT
import app.siphondsp.model.NativeBmwDspValues.highOutputIndex
import app.siphondsp.model.NativeBmwDspValues.midUpperXoIndex
import app.siphondsp.model.NativeBmwDspValues.outputIndex

/**
 * The Crossovers page's master 3-way on/off switch -- a UI-level convenience over existing
 * fields, not a schema field of its own (see docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md).
 *
 * Off = Mid's upper corner disabled + `highXoPass` set, which is bit-identical to the old 2-way
 * crossover (`highXoPass` fully silences High on its own). On = the reverse, plus High's
 * per-output mute cleared -- High ships with both `highXoPass` AND its mute set (see
 * `migrateHighBandIfNeeded`), and nothing else in the UI exposes High's mute, so without this
 * the switch would enable a band that stays silent. Turning it back off leaves the mute alone
 * since `highXoPass` alone is enough to silence High.
 */
object ThreeWayCrossover {
    private val midUpperEnabledL = midUpperXoIndex(OUTPUT_MID_LEFT, MID_UPPER_XO_FIELD_ENABLED)
    private val midUpperEnabledR = midUpperXoIndex(OUTPUT_MID_RIGHT, MID_UPPER_XO_FIELD_ENABLED)

    /** The Mid/High corner's primary index (Mid Left's upper freq); see [cornerMirrors]. */
    val cornerIndex = midUpperXoIndex(OUTPUT_MID_LEFT, MID_UPPER_XO_FIELD_FREQ)

    /** Every other index the Mid/High corner frequency is kept equal to -- Mid Right's upper
     *  corner and both High outputs' HPF corner -- the same mirroring the Lowpass/Highpass rows
     *  already do onto their bands' per-output blocks. */
    val cornerMirrors = intArrayOf(
        midUpperXoIndex(OUTPUT_MID_RIGHT, MID_UPPER_XO_FIELD_FREQ),
        highOutputIndex(OUTPUT_HIGH_LEFT, FIELD_CROSSOVER_FREQ),
        highOutputIndex(OUTPUT_HIGH_RIGHT, FIELD_CROSSOVER_FREQ),
    )

    /** High's slope indices. Native Mid's upper lowpass has no type of its own -- it reuses
     *  Mid's FIELD_CROSSOVER_TYPE (see rebuildMidCrossover) -- so High's highpass follows that
     *  same type to keep both sides of the Mid/High corner a matched pair. */
    val typeMirrors = intArrayOf(
        highOutputIndex(OUTPUT_HIGH_LEFT, FIELD_CROSSOVER_TYPE),
        highOutputIndex(OUTPUT_HIGH_RIGHT, FIELD_CROSSOVER_TYPE),
    )

    fun isEnabled(values: FloatArray): Boolean =
        values[INDEX_HIGH_XO_PASS] < .5f && values[midUpperEnabledL] >= .5f

    fun updates(values: FloatArray, enabled: Boolean): Map<Int, Float> {
        if (!enabled) {
            return mapOf(
                INDEX_HIGH_XO_PASS to 1f,
                midUpperEnabledL to 0f,
                midUpperEnabledR to 0f,
            )
        }
        // Re-align every mirror onto the primary corner so Mid's lowpass and High's highpass
        // can't come up at different frequencies (and leave a gap or overlap) if they drifted.
        val corner = values[cornerIndex]
        val midType = values[outputIndex(OUTPUT_MID_LEFT, FIELD_CROSSOVER_TYPE)]
        return buildMap {
            put(INDEX_HIGH_XO_PASS, 0f)
            put(midUpperEnabledL, 1f)
            put(midUpperEnabledR, 1f)
            put(highOutputIndex(OUTPUT_HIGH_LEFT, FIELD_MUTE), 0f)
            put(highOutputIndex(OUTPUT_HIGH_RIGHT, FIELD_MUTE), 0f)
            for (mirror in cornerMirrors) put(mirror, corner)
            for (mirror in typeMirrors) put(mirror, midType)
        }
    }
}
