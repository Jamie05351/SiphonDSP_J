package app.siphondsp.dsp

import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel

/** Which of the three independently-editable PEQ banks a band or graph node belongs to. */
enum class BmwPeqBank { FULL, LOW, MID }

/**
 * The physically-perceived output channel, as heard through the car's speakers. Native's
 * `processFrame` ends with `l = oR; r = oL;` -- the physical left output is fed by the
 * internally-computed "right" processing chain, and vice versa. Every consumer of this
 * package speaks in [BmwOutputChannel] terms so "Left" always means what the user hears
 * on the left, never the internal chain name.
 */
enum class BmwOutputChannel { LEFT, RIGHT }

object BmwSignalChain {
    const val VALUE_COUNT = 35

    /**
     * True when [output] is fed by native's internal "left" processing chain (the one
     * driven by *L*-suffixed config indices and `ParametricEqChannel.LEFT`-tagged bands),
     * given the `l=oR; r=oL` swap at the end of processFrame.
     */
    fun internalIsLeftChainFor(output: BmwOutputChannel): Boolean = output == BmwOutputChannel.RIGHT

    /** Whether a PEQ band's channel tag routes it into the internal chain feeding [output]. */
    fun bandAppliesTo(band: ParametricEqBand, output: BmwOutputChannel): Boolean {
        if (band.channel == ParametricEqChannel.LEFT_RIGHT) return true
        val internalLeft = internalIsLeftChainFor(output)
        return if (internalLeft) band.channel == ParametricEqChannel.LEFT else band.channel == ParametricEqChannel.RIGHT
    }
}
