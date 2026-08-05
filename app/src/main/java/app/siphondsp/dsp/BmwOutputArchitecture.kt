package app.siphondsp.dsp

/**
 * Pure-Kotlin mirror of `NativeBmwRouting::RoutingMatrix` (see NativeBmwRouting.h), kept only
 * so the routing math itself (identity/reconstruction/no-crossfeed) is unit-testable on the
 * JVM without a native test harness. Not used by the live audio path -- that's entirely
 * native (NativeBmwDspProcessor::processFrame) -- and not used by the response graph either
 * (BmwResponseCalculator models the four bands directly without a routing indirection, since
 * a divergence there is a display-only concern, not an audible one). Keep this in exact
 * lockstep with NativeBmwRouting.h's RoutingMatrix if that ever changes.
 */
enum class BmwRoutingOutput { LOW_LEFT, LOW_RIGHT, MID_LEFT, MID_RIGHT }

data class BmwRoutingCoefficients(val fromFrontLeft: Float, val fromFrontRight: Float)

object BmwOutputRouting {
    /** Unity same-side, zero crossfeed -- the pre-routing-matrix topology. */
    val IDENTITY: Map<BmwRoutingOutput, BmwRoutingCoefficients> = mapOf(
        BmwRoutingOutput.LOW_LEFT to BmwRoutingCoefficients(1f, 0f),
        BmwRoutingOutput.LOW_RIGHT to BmwRoutingCoefficients(0f, 1f),
        BmwRoutingOutput.MID_LEFT to BmwRoutingCoefficients(1f, 0f),
        BmwRoutingOutput.MID_RIGHT to BmwRoutingCoefficients(0f, 1f),
    )

    /** Mirrors `RoutingMatrix::process`. */
    fun route(
        frontLeft: Float,
        frontRight: Float,
        matrix: Map<BmwRoutingOutput, BmwRoutingCoefficients> = IDENTITY,
    ): Map<BmwRoutingOutput, Float> = BmwRoutingOutput.entries.associateWith { output ->
        val coefficients = matrix.getValue(output)
        val sum = frontLeft * coefficients.fromFrontLeft + frontRight * coefficients.fromFrontRight
        if (sum.isFinite()) sum else 0f
    }

    /** Mirrors `sumToStereo`: Low L + Mid L to Left, Low R + Mid R to Right. */
    fun reconstruct(outputs: Map<BmwRoutingOutput, Float>): Pair<Float, Float> {
        val left = outputs.getValue(BmwRoutingOutput.LOW_LEFT) + outputs.getValue(BmwRoutingOutput.MID_LEFT)
        val right = outputs.getValue(BmwRoutingOutput.LOW_RIGHT) + outputs.getValue(BmwRoutingOutput.MID_RIGHT)
        return (if (left.isFinite()) left else 0f) to (if (right.isFinite()) right else 0f)
    }
}
