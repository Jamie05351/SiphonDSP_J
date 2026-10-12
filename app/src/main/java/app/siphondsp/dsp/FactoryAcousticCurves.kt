package app.siphondsp.dsp

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * What the speakers actually do: this app's output for a side ([BmwResponseCurves.sumDb] /
 * [BmwResponseCurves.sumPhase], i.e. exactly what leaves for the factory amplifier) multiplied
 * by the [FactoryBaseline] response measured after it.
 *
 * The bands are summed back to one L/R pair before the amplifier, so every driver on a side
 * sees the whole side's curve -- a Low-band edit reaches the mid and tweeter too, through the
 * factory crossover. That is why the multiplication is by the side sum, not by a branch.
 *
 *  - per driver: x that driver's near-field response. NaN outside its measured band.
 *  - the side's sum: x that side's seat sweep ([FactoryBaseline.seatSums]) -- exact at that seat,
 *    because the side is one signal into the amplifier. NaN when the file has no seat sweep: the
 *    near-field drivers are not summed into a stand-in (on-axis, no cabin -- wrong at crossovers).
 *
 * All arrays are pre-sized and reused.
 */
class FactoryAcousticCurves(val pointCount: Int) {
    val frequencies = DoubleArray(pointCount)

    /** [BmwOutputChannel.ordinal] x [FactoryDriver.ordinal] x point. NaN where not measured. */
    val driverDb = Array(2) { Array(FactoryDriver.entries.size) { DoubleArray(pointCount) } }
    val driverPhase = Array(2) { Array(FactoryDriver.entries.size) { DoubleArray(pointCount) } }

    /** The side at the listening seat. NaN where the seat sweep does not reach, or without one. */
    val sumDb = Array(2) { DoubleArray(pointCount) }
    val sumPhase = Array(2) { DoubleArray(pointCount) }

    /**
     * Group delay (ms), same indexing and NaNs as the dB arrays. Built as a SUM of group delays
     * (that of a product): the measured part analytically from its unwrapped phase and known
     * arrival ([FactoryDriverResponse.groupDelayMs]), plus this app's own
     * ([BmwResponseCurves.groupDelayMsFor], what the DSP-only view shows). Never differentiated
     * from the combined wrapped phase, where an arrival of several ms turns more than pi between
     * neighbouring points of a sparse log axis and aliases.
     */
    val driverGroupDelayMs = Array(2) { Array(FactoryDriver.entries.size) { DoubleArray(pointCount) } }
    val sumGroupDelayMs = Array(2) { DoubleArray(pointCount) }

    private val electrical = ComplexAcc()
    private val plant = ComplexAcc()

    fun driverDbFor(side: BmwOutputChannel, driver: FactoryDriver): DoubleArray = driverDb[side.ordinal][driver.ordinal]
    fun driverPhaseFor(side: BmwOutputChannel, driver: FactoryDriver): DoubleArray = driverPhase[side.ordinal][driver.ordinal]
    fun sumDbFor(side: BmwOutputChannel): DoubleArray = sumDb[side.ordinal]
    fun sumPhaseFor(side: BmwOutputChannel): DoubleArray = sumPhase[side.ordinal]

    /** Recomputes everything from [curves] (already computed) and [baseline]. */
    fun compute(curves: BmwResponseCurves, baseline: FactoryBaseline) {
        require(curves.pointCount == pointCount) { "point counts differ: ${curves.pointCount} vs $pointCount" }
        curves.frequencies.copyInto(frequencies)
        for (side in BmwOutputChannel.entries) {
            val s = side.ordinal
            val eDb = curves.sumDbFor(side)
            val ePhase = curves.sumPhaseFor(side)
            val seat = baseline.seatSum(side)
            val appDelayMs = curves.groupDelayMsFor(side)
            for (i in 0 until pointCount) {
                val f = frequencies[i]
                val mag = 10.0.pow(eDb[i] / 20.0)
                for (driver in FactoryDriver.entries) {
                    val response = baseline.driver(side, driver)
                    applyPlant(mag, ePhase[i], f, response, driverDb[s][driver.ordinal], driverPhase[s][driver.ordinal], i)
                    driverGroupDelayMs[s][driver.ordinal][i] = (response?.groupDelayMs(f) ?: Double.NaN) + appDelayMs[i]
                }
                applyPlant(mag, ePhase[i], f, seat, sumDb[s], sumPhase[s], i)
                sumGroupDelayMs[s][i] = (seat?.groupDelayMs(f) ?: Double.NaN) + appDelayMs[i]
            }
        }
    }

    /** Writes (this app's output x [response]) at point [i] into [db] / [phase]; NaN where [response] has no data. */
    private fun applyPlant(
        mag: Double,
        appPhase: Double,
        frequencyHz: Double,
        response: FactoryDriverResponse?,
        db: DoubleArray,
        phase: DoubleArray,
        i: Int,
    ) {
        if (response == null || !response.evaluate(frequencyHz, plant)) {
            db[i] = Double.NaN
            phase[i] = Double.NaN
            return
        }
        electrical.re = mag * cos(appPhase)
        electrical.im = mag * sin(appPhase)
        electrical.mul(plant.re, plant.im)
        db[i] = electrical.magnitudeDb()
        phase[i] = electrical.phase()
    }

    /** Group delay (ms) of the side at the seat. NaN where the sum is NaN. */
    fun groupDelayMsFor(side: BmwOutputChannel): DoubleArray = sumGroupDelayMs[side.ordinal]

    /** Group delay (ms) of one driver. NaN outside its measured band. */
    fun driverGroupDelayMsFor(side: BmwOutputChannel, driver: FactoryDriver): DoubleArray =
        driverGroupDelayMs[side.ordinal][driver.ordinal]
}
