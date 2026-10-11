package app.siphondsp.dsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** The three speakers the factory amplifier splits each side into. */
enum class FactoryDriver { WOOFER, MID, TWEETER }

/**
 * A measured response *through the factory amplifier* -- the factory EQ, crossover and time
 * alignment that sit after this app and cannot be switched off are all baked in. Captured with
 * this app bypassed, so it is the "plant" a graph multiplies this app's output by.
 *
 * Either one driver ([driver] set: a near-field sweep, on-axis, no cabin) or a whole side at the
 * listening seat ([driver] null: that side's seat sweep -- every driver, off-axis, cabin).
 * [phaseRad] is unwrapped with [arrivalMs] taken out; [evaluate] puts the arrival back.
 */
class FactoryDriverResponse(
    val side: BmwOutputChannel,
    val driver: FactoryDriver?,
    val arrivalMs: Double,
    val minHz: Double,
    val maxHz: Double,
    private val freqHz: DoubleArray,
    private val magDb: DoubleArray,
    private val phaseRad: DoubleArray,
) {
    init {
        require(freqHz.size >= 2) { "$side $driver: needs at least 2 points" }
        require(freqHz.size == magDb.size && freqHz.size == phaseRad.size) {
            "$side $driver: freqHz, magDb and phaseDeg differ in length"
        }
        for (i in 1 until freqHz.size) require(freqHz[i] > freqHz[i - 1]) { "$side $driver: freqHz must ascend" }
        require(minHz < maxHz) { "$side $driver: minHz must be below maxHz" }
    }

    /** True where this driver was actually measured; outside it there is no data to draw. */
    fun covers(frequencyHz: Double): Boolean = frequencyHz in minHz..maxHz

    /**
     * Sets [out] to the driver's complex response at [frequencyHz] (log-frequency linear
     * interpolation of dB and unwrapped phase, arrival re-applied). Returns false, leaving
     * [out] untouched, outside [minHz]..[maxHz].
     */
    internal fun evaluate(frequencyHz: Double, out: ComplexAcc): Boolean {
        if (!covers(frequencyHz)) return false
        val f = frequencyHz.coerceIn(freqHz.first(), freqHz.last())
        var hi = freqHz.binarySearch(f)
        val db: Double
        val phase: Double
        if (hi >= 0) {
            db = magDb[hi]
            phase = phaseRad[hi]
        } else {
            hi = -hi - 1
            val lo = hi - 1
            val t = ln(f / freqHz[lo]) / ln(freqHz[hi] / freqHz[lo])
            db = magDb[lo] + t * (magDb[hi] - magDb[lo])
            phase = phaseRad[lo] + t * (phaseRad[hi] - phaseRad[lo])
        }
        val angle = phase - 2.0 * PI * frequencyHz * arrivalMs / 1000.0
        val mag = 10.0.pow(db / 20.0)
        out.re = mag * cos(angle)
        out.im = mag * sin(angle)
        return true
    }
}

/**
 * The factory amplifier's measured response, loaded from the JSON the
 * `scripts/export_factory_baseline.py` exporter writes from REW sweeps.
 *
 * [drivers]: one near-field response per driver. Near-field timing is trusted within a side only.
 * [seatSums]: per side, the sweep at the listening [seat]. Each side is ONE signal into the
 * amplifier, so this app's output x that sweep is exactly what the side does at that seat. The
 * near-field drivers cannot stand in for it (on-axis and cabin-free, they miss the seat badly
 * around the crossovers), so without a seat sweep no sum is drawn.
 */
class FactoryBaseline(
    val source: String,
    val drivers: List<FactoryDriverResponse>,
    val seatSums: List<FactoryDriverResponse> = emptyList(),
    val seat: String = "",
) {

    fun driver(side: BmwOutputChannel, driver: FactoryDriver): FactoryDriverResponse? =
        drivers.firstOrNull { it.side == side && it.driver == driver }

    fun seatSum(side: BmwOutputChannel): FactoryDriverResponse? = seatSums.firstOrNull { it.side == side }

    val hasSeatSums: Boolean get() = seatSums.isNotEmpty()

    companion object {
        const val FORMAT = "siphondsp-factory-baseline"
        const val VERSION = 1

        /** Parses the exporter's JSON. Throws [IllegalArgumentException] naming what is wrong. */
        fun parse(json: String): FactoryBaseline {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }
                .getOrElse { throw IllegalArgumentException("not a JSON object: ${it.message}") }
            require(root.string("format") == FORMAT) { "format is not $FORMAT" }
            val version = root["version"]?.jsonPrimitive?.double?.toInt()
            require(version == VERSION) { "unsupported version $version (this app reads $VERSION)" }
            val list = root["drivers"]?.jsonArray ?: throw IllegalArgumentException("no drivers")
            val drivers = list.map { parseCurve(it.jsonObject, seatSum = false) }
            val seen = HashSet<Pair<BmwOutputChannel, FactoryDriver?>>()
            drivers.forEach { require(seen.add(it.side to it.driver)) { "${it.side} ${it.driver} appears twice" } }
            val sums = (root["seatSums"] as? JsonArray)?.map { parseCurve(it.jsonObject, seatSum = true) }.orEmpty()
            val sumSides = HashSet<BmwOutputChannel>()
            sums.forEach { require(sumSides.add(it.side)) { "seat sum ${it.side} appears twice" } }
            return FactoryBaseline(root.string("source") ?: "", drivers, sums, root.string("seat") ?: "")
        }

        private fun parseCurve(o: JsonObject, seatSum: Boolean): FactoryDriverResponse {
            val side = enumOrThrow<BmwOutputChannel>(o.string("side"), "side")
            val driver = if (seatSum) null else enumOrThrow<FactoryDriver>(o.string("driver"), "driver")
            fun num(key: String) = o[key]?.jsonPrimitive?.double
                ?: throw IllegalArgumentException("$side $driver: missing $key")
            fun arr(key: String) = (o[key] as? JsonArray)?.map { it.jsonPrimitive.double }?.toDoubleArray()
                ?: throw IllegalArgumentException("$side $driver: missing $key")
            return FactoryDriverResponse(
                side = side,
                driver = driver,
                arrivalMs = num("arrivalMs"),
                minHz = num("minHz"),
                maxHz = num("maxHz"),
                freqHz = arr("freqHz"),
                magDb = arr("magDb"),
                phaseRad = arr("phaseDeg").map { it * PI / 180.0 }.toDoubleArray(),
            )
        }

        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

        private inline fun <reified E : Enum<E>> enumOrThrow(value: String?, key: String): E =
            enumValues<E>().firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("$key '$value' is not one of ${enumValues<E>().joinToString()}")
    }
}
