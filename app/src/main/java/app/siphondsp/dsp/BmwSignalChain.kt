package app.siphondsp.dsp

import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel

/** Which of the three independently-editable PEQ banks a band or graph node belongs to. */
enum class BmwPeqBank { FULL, LOW, MID }

/**
 * The physically-perceived output channel, as heard through the car's speakers.
 *
 * **Critical:** Native's `processFrame` ends with `l = oR; r = oL;` -- the physical
 * left output is fed by the internally-computed "right" processing chain, and vice versa.
 *
 * Every consumer of this package speaks in [BmwOutputChannel] terms so "Left" always
 * means what the user hears on the left, never the internal chain name.
 *
 * @property LEFT   Physical left speaker (fed by internal "right" chain)
 * @property RIGHT  Physical right speaker (fed by internal "left" chain)
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
 * The native processor swaps L/R at the end of its signal chain for hardware routing.
 * This class hides that swap from UI/graph code.
 *
 * Example:
 * ```
 * val leftBand = ParametricEqBand(channel = ParametricEqChannel.LEFT)
 * // UI edits the "left" band
 * // Internally, it feeds the RIGHT output (due to the swap)
 * val appliesToRight = bandAppliesTo(leftBand, BmwOutputChannel.RIGHT)  // true
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
     * Due to the `l=oR; r=oL` swap at the end of native's processFrame:
     * - Physical LEFT (user hears on left) is fed by internal "RIGHT" chain
     * - Physical RIGHT (user hears on right) is fed by internal "LEFT" chain
     *
     * This is the **inverse relationship**: internal left → physical right.
     *
     * @param output Physical output channel
     * @return True if [output] is fed by the internal "left" processing chain
     *
     * Example:
     * ```
     * internalIsLeftChainFor(BmwOutputChannel.LEFT)   // false (fed by internal RIGHT)
     * internalIsLeftChainFor(BmwOutputChannel.RIGHT)  // true  (fed by internal LEFT)
     * ```
     */
    fun internalIsLeftChainFor(output: BmwOutputChannel): Boolean = 
        output == BmwOutputChannel.RIGHT

    /**
     * Determines whether a parametric EQ band applies to the given physical output.
     *
     * Accounts for the internal L/R swap when routing:
     * - A band tagged with [ParametricEqChannel.LEFT] targets the internal "left" chain,
     *   which -- due to the swap -- feeds the physical RIGHT output
     * - A band tagged with [ParametricEqChannel.RIGHT] targets the internal "right" chain,
     *   which feeds the physical LEFT output
     * - A band tagged with [ParametricEqChannel.LEFT_RIGHT] applies to both
     *
     * Internally, the native processor swaps the outputs; this method's return value already
     * reflects that swap, so callers can compare directly against a physical [BmwOutputChannel]
     * without re-deriving the mapping themselves.
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
     * bandAppliesTo(leftBand, BmwOutputChannel.LEFT)    // false (swapped: LEFT band feeds physical RIGHT)
     * bandAppliesTo(leftBand, BmwOutputChannel.RIGHT)   // true  (swapped match)
     *
     * bandAppliesTo(rightBand, BmwOutputChannel.LEFT)   // true  (swapped match)
     * bandAppliesTo(rightBand, BmwOutputChannel.RIGHT)  // false (swapped: RIGHT band feeds physical LEFT)
     *
     * bandAppliesTo(bothBand, BmwOutputChannel.LEFT)    // true  (always matches)
     * bandAppliesTo(bothBand, BmwOutputChannel.RIGHT)   // true  (always matches)
     * ```
     */
    fun bandAppliesTo(band: ParametricEqBand, output: BmwOutputChannel): Boolean {
        // If band applies to both, always match
        if (band.channel == ParametricEqChannel.LEFT_RIGHT) return true
        
        // Determine which internal chain feeds this physical output
        val internalLeft = internalIsLeftChainFor(output)
        
        // Match the band's channel tag against the internal chain
        return if (internalLeft) {
            band.channel == ParametricEqChannel.LEFT
        } else {
            band.channel == ParametricEqChannel.RIGHT
        }
    }
}
