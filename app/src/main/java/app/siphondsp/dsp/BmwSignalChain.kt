package app.siphondsp.dsp

import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel

/** Which of the three independently-editable PEQ banks a band or graph node belongs to. */
enum class BmwPeqBank { FULL, LOW, MID }

/**
 * The physically-perceived output channel, as heard through the car's speakers.
 *
 * **Two swaps, not one:** native's `processFrame` ends with `l = oR; r = oL;` -- a
 * deliberate, required correction for this vehicle's factory-reversed speaker wiring
 * harness (see the comment on that line in `NativeBmwDspProcessor.cpp`). Because the
 * code-level swap and the physical wiring fault cancel each other out, the internal
 * "left"/"right" processing chain (`LowLeft`/`MidLeft` vs `LowRight`/`MidRight`,
 * addressed via [NativeBmwDspValues.outputIndex]) already corresponds directly to what
 * the user hears on that same physical side -- no additional swap is needed anywhere
 * downstream, including here.
 *
 * Every consumer of this package speaks in [BmwOutputChannel] terms so "Left" always
 * means what the user hears on the left, which is also the internal "left" chain.
 *
 * @property LEFT   Physical left speaker (fed directly by internal "left" chain)
 * @property RIGHT  Physical right speaker (fed directly by internal "right" chain)
 */
enum class BmwOutputChannel { LEFT, RIGHT }

/**
 * BMW multi-band DSP signal chain abstraction and helpers.
 *
 * This singleton provides:
 * - Configuration contract size verification ([VALUE_COUNT])
 * - Physical-vs-internal channel mapping ([internalIsLeftChainFor])
 * - PEQ band routing logic ([bandAppliesTo])
 *
 * See [BmwOutputChannel]'s doc comment for why the internal chain maps directly (not
 * swapped) onto the physical side the user hears.
 *
 * Example:
 * ```
 * val leftBand = ParametricEqBand(channel = ParametricEqChannel.LEFT)
 * // UI edits the "left" band
 * // Internally, it feeds the LEFT output directly
 * val appliesToLeft = bandAppliesTo(leftBand, BmwOutputChannel.LEFT)  // true
 * ```
 */
object BmwSignalChain {
    /**
     * Configuration array size for native BMW DSP values.
     *
     * Must stay in sync with native NativeBmwDspProcessor::kConfigSize.
     * Keep every graph and response-model entry point on the same canonical
     * configuration contract as the engine and UI.
     *
     * History:
     * - Original: 35 values (pre-dual-band compressor)
     * - Extended: 46 values (added routing matrix)
     * - Current: 139 values (extended routing, all-pass, compressor per output)
     *
     * The dual-band compressor extension grew this array from 35 to 42+ values.
     * Leaving the response model at 35 caused setSystemState() and BMW update
     * broadcasts to reject otherwise valid configurations, leaving the PEQ graph
     * on its empty, flat 0 dB state. Always verify this is updated when native
     * config changes.
     *
     * @see NativeBmwDspValues.SIZE
     */
    const val VALUE_COUNT = NativeBmwDspValues.SIZE

    /**
     * Determines which internal processing chain feeds the given physical output.
     *
     * The internal chain maps directly onto the physical side the user hears -- see
     * [BmwOutputChannel]'s doc comment for why no swap is needed here.
     *
     * @param output Physical output channel
     * @return True if [output] is fed by the internal "left" processing chain
     *
     * Example:
     * ```
     * internalIsLeftChainFor(BmwOutputChannel.LEFT)   // true  (fed by internal LEFT, directly)
     * internalIsLeftChainFor(BmwOutputChannel.RIGHT)  // false (fed by internal RIGHT, directly)
     * ```
     */
    fun internalIsLeftChainFor(output: BmwOutputChannel): Boolean =
        output == BmwOutputChannel.LEFT

    /**
     * Determines whether a parametric EQ band applies to the given physical output.
     *
     * The internal chain maps directly onto the physical side the user hears (see
     * [BmwOutputChannel]'s doc comment), so this is a plain, unswapped match:
     * - A band tagged with [ParametricEqChannel.LEFT] applies to the physical LEFT output
     * - A band tagged with [ParametricEqChannel.RIGHT] applies to the physical RIGHT output
     * - A band tagged with [ParametricEqChannel.LEFT_RIGHT] applies to both
     *
     * @param band The parametric EQ band model (contains channel tag)
     * @param output Physical output channel to check against
     * @return True if [band] should be applied to [output]
     *
     * Example:
     * ```
     * val leftBand = ParametricEqBand(channel = ParametricEqChannel.LEFT)
     * val rightBand = ParametricEqBand(channel = ParametricEqChannel.RIGHT)
     * val bothBand = ParametricEqBand(channel = ParametricEqChannel.LEFT_RIGHT)
     *
     * bandAppliesTo(leftBand, BmwOutputChannel.LEFT)    // true  (direct match)
     * bandAppliesTo(leftBand, BmwOutputChannel.RIGHT)   // false
     *
     * bandAppliesTo(rightBand, BmwOutputChannel.LEFT)   // false
     * bandAppliesTo(rightBand, BmwOutputChannel.RIGHT)  // true  (direct match)
     *
     * bandAppliesTo(bothBand, BmwOutputChannel.LEFT)    // true  (always matches)
     * bandAppliesTo(bothBand, BmwOutputChannel.RIGHT)   // true  (always matches)
     * ```
     */
    fun bandAppliesTo(band: ParametricEqBand, output: BmwOutputChannel): Boolean {
        // If band applies to both, always match
        if (band.channel == ParametricEqChannel.LEFT_RIGHT) return true

        // The internal chain maps directly onto the physical output -- no swap needed.
        val internalLeft = internalIsLeftChainFor(output)

        // Match the band's channel tag against the internal chain
        return if (internalLeft) {
            band.channel == ParametricEqChannel.LEFT
        } else {
            band.channel == ParametricEqChannel.RIGHT
        }
    }
}
