package app.siphondsp.dsp

/**
 * Output holder for [BmwResponseCalculator.compute]. All arrays are pre-sized and reused
 * across recomputes -- nothing here is ever reallocated after construction. No [ComplexAcc]
 * or complex type ever leaves the dsp package; everything public is a dB [DoubleArray].
 */
class BmwResponseCurves(val pointCount: Int) {
    val frequencies = DoubleArray(pointCount)

    /** Indexed by [BmwOutputChannel.ordinal]. */
    val sumDb = Array(2) { DoubleArray(pointCount) }
    val lowBranchDb = Array(2) { DoubleArray(pointCount) }
    val midBranchDb = Array(2) { DoubleArray(pointCount) }
    val preSplitDb = Array(2) { DoubleArray(pointCount) }
    /** Wrapped electrical phase of the complete summed output path, degrees in [-180, 180]. */
    val sumPhaseDegrees = Array(2) { DoubleArray(pointCount) }

    var processorEnabled = true
    var lowBranchActive = true
    var midBranchActive = true
    var bothCrossoversBypassed = false

    fun sumDbFor(channel: BmwOutputChannel): DoubleArray = sumDb[channel.ordinal]
    fun lowBranchDbFor(channel: BmwOutputChannel): DoubleArray = lowBranchDb[channel.ordinal]
    fun midBranchDbFor(channel: BmwOutputChannel): DoubleArray = midBranchDb[channel.ordinal]
    fun preSplitDbFor(channel: BmwOutputChannel): DoubleArray = preSplitDb[channel.ordinal]
    fun sumPhaseDegreesFor(channel: BmwOutputChannel): DoubleArray = sumPhaseDegrees[channel.ordinal]
}
