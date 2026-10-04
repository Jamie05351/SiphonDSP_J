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
 *
 * Mid's lowpass and High's highpass are independent: each has its own frequency, and High has
 * its own slope (Mid's lowpass still shares Mid's slope natively, see rebuildMidCrossover).
 */
object ThreeWayCrossover {
    private val midUpperEnabledL = midUpperXoIndex(OUTPUT_MID_LEFT, MID_UPPER_XO_FIELD_ENABLED)
    private val midUpperEnabledR = midUpperXoIndex(OUTPUT_MID_RIGHT, MID_UPPER_XO_FIELD_ENABLED)

    /** Mid's lowpass (Mid Left's upper corner); [midLowpassMirrors] keeps Mid Right equal. */
    val midLowpassIndex = midUpperXoIndex(OUTPUT_MID_LEFT, MID_UPPER_XO_FIELD_FREQ)
    val midLowpassMirrors = intArrayOf(midUpperXoIndex(OUTPUT_MID_RIGHT, MID_UPPER_XO_FIELD_FREQ))

    /** High's highpass (High Left's corner); [highHighpassMirrors] keeps High Right equal. */
    val highHighpassIndex = highOutputIndex(OUTPUT_HIGH_LEFT, FIELD_CROSSOVER_FREQ)
    val highHighpassMirrors = intArrayOf(highOutputIndex(OUTPUT_HIGH_RIGHT, FIELD_CROSSOVER_FREQ))

    /** High's slope (High Left's type); [highTypeMirrors] keeps High Right equal. */
    val highTypeIndex = highOutputIndex(OUTPUT_HIGH_LEFT, FIELD_CROSSOVER_TYPE)
    val highTypeMirrors = intArrayOf(highOutputIndex(OUTPUT_HIGH_RIGHT, FIELD_CROSSOVER_TYPE))

    /** Both Mid upper-corner flags are persisted independently, so a restored/imported array can
     *  have only one set -- native would then play High on that side with the other Mid branch
     *  unbounded. Only the fully consistent state counts as on, so an asymmetric one shows as off
     *  and turning the switch on normalizes it via [updates]. */
    fun isEnabled(values: FloatArray): Boolean =
        values[INDEX_HIGH_XO_PASS] < .5f && values[midUpperEnabledL] >= .5f && values[midUpperEnabledR] >= .5f

    fun updates(values: FloatArray, enabled: Boolean): Map<Int, Float> {
        if (!enabled) {
            return mapOf(
                INDEX_HIGH_XO_PASS to 1f,
                midUpperEnabledL to 0f,
                midUpperEnabledR to 0f,
            )
        }
        // Re-align each Right output onto its Left so neither band comes up with mismatched
        // sides if they drifted (e.g. an imported array); Mid and High stay independent.
        return buildMap {
            put(INDEX_HIGH_XO_PASS, 0f)
            put(midUpperEnabledL, 1f)
            put(midUpperEnabledR, 1f)
            put(highOutputIndex(OUTPUT_HIGH_LEFT, FIELD_MUTE), 0f)
            put(highOutputIndex(OUTPUT_HIGH_RIGHT, FIELD_MUTE), 0f)
            for (mirror in midLowpassMirrors) put(mirror, values[midLowpassIndex])
            for (mirror in highHighpassMirrors) put(mirror, values[highHighpassIndex])
            for (mirror in highTypeMirrors) put(mirror, values[highTypeIndex])
        }
    }
}
