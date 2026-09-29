package app.siphondsp.compose.controls

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** A seat the distances are measured from; the path lines start at its head position. */
enum class ListeningSeat { DRIVER, PASSENGER }

/**
 * Who the alignment is for. [DRIVER] aligns to the driver seat alone. [MULTI] aligns to both front
 * seats: each driver's distance is the average of its driver-seat and passenger-seat paths (the
 * passenger seat is the driver seat mirrored), which times the bands together for both seats but
 * leaves each seat half its left/right gap off centre.
 */
enum class AlignTarget(val seats: List<ListeningSeat>) {
    DRIVER(listOf(ListeningSeat.DRIVER)),
    MULTI(listOf(ListeningSeat.DRIVER, ListeningSeat.PASSENGER)),
}

/** One physical driver: which band's speaker, and which channel (`left` = the Left output). */
data class DriverId(val kind: SpeakerKind, val left: Boolean)

/** Speed of sound (343 m/s) in centimetres per millisecond. */
const val SoundCmPerMs = 34.3f

/** Pure geometry, kept free of Android so it can be unit tested. */
object SpeakerGeometryMath {
    val allDrivers: List<DriverId> =
        SpeakerKind.entries.flatMap { kind -> listOf(true, false).map { DriverId(kind, it) } }

    /**
     * Geometric time alignment: how long this driver must wait so its sound arrives with the sound
     * from the farthest driver. The farthest driver gets 0.
     */
    fun alignDelayMs(distanceCm: Float, allDistancesCm: Collection<Float>): Float {
        val farthest = allDistancesCm.maxOrNull() ?: return 0f
        return ((farthest - distanceCm) / SoundCmPerMs).coerceAtLeast(0f)
    }

    /**
     * The distance [target] aligns [id] to, from the driver-seat paths [driverSeatCm]. The passenger
     * seat is the driver seat mirrored, so its path to a driver is the driver seat's path to the
     * opposite-side driver of the same band.
     */
    fun targetDistanceCm(target: AlignTarget, id: DriverId, driverSeatCm: (DriverId) -> Float): Float =
        target.seats.map { seat ->
            driverSeatCm(if (seat == ListeningSeat.DRIVER) id else id.copy(left = !id.left))
        }.average().toFloat()

    /**
     * Starting distances in cm from the seat to each driver: the E60 driver-seat measurements, with
     * the passenger seat mirrored (left and right swap). Editable in the app; these only seed it.
     */
    fun defaultDistanceCm(seat: ListeningSeat, id: DriverId): Float {
        val fromDriverSeat = when (id.kind) {
            SpeakerKind.TWEETER -> if (id.left) 130f else 78f
            SpeakerKind.MID -> if (id.left) 138f else 92f
            SpeakerKind.WOOFER -> if (id.left) 125f else 87f
        }
        if (seat == ListeningSeat.DRIVER) return fromDriverSeat
        // Mirror: the passenger's left driver is the driver's right one.
        return defaultDistanceCm(ListeningSeat.DRIVER, id.copy(left = !id.left))
    }
}

/**
 * The alignment target and the six driver-seat distances, remembered across launches in SharedPreferences.
 * Reads and writes go through Compose state, so cards and the map update as soon as one changes.
 */
class SpeakerGeometryState internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val overrides = mutableStateMapOf<String, Float>()

    var target by mutableStateOf(readTarget())
        private set

    // Picks up writes made elsewhere (a backup restore) while the page is open. Held here because
    // SharedPreferences only keeps a weak reference to its listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }

    init {
        reload()
    }

    internal fun startListening() = prefs.registerOnSharedPreferenceChangeListener(listener)

    internal fun stopListening() = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private fun reload() {
        val stored = prefs.all.filter { (key, value) -> key.startsWith("cm_") && value is Float }
        overrides.keys.retainAll(stored.keys)
        for ((key, value) in stored) overrides[key] = value as Float
        target = readTarget()
    }

    fun selectTarget(next: AlignTarget) {
        target = next
        prefs.edit().putString(KEY_TARGET, next.name).apply()
    }

    /** The measured path from the driver seat to [id]; the only distances stored. */
    fun distanceCm(id: DriverId): Float =
        overrides[key(id)] ?: SpeakerGeometryMath.defaultDistanceCm(ListeningSeat.DRIVER, id)

    /** The path the current [target] aligns to (the two-seat average in [AlignTarget.MULTI]). */
    fun targetDistanceCm(id: DriverId): Float =
        SpeakerGeometryMath.targetDistanceCm(target, id, ::distanceCm)

    fun setDistanceCm(id: DriverId, cm: Float) {
        val k = key(id)
        overrides[k] = cm
        prefs.edit().putFloat(k, cm).apply()
    }

    fun alignDelayMs(id: DriverId): Float = SpeakerGeometryMath.alignDelayMs(
        targetDistanceCm(id),
        SpeakerGeometryMath.allDrivers.map { targetDistanceCm(it) },
    )

    private fun readTarget(): AlignTarget =
        runCatching { AlignTarget.valueOf(prefs.getString(KEY_TARGET, null) ?: "") }
            .getOrDefault(AlignTarget.DRIVER)

    private fun key(id: DriverId) =
        "cm_${ListeningSeat.DRIVER.name}_${id.kind.name}_${if (id.left) "L" else "R"}"

    companion object {
        /** SharedPreferences name; the backup carries it as `shared_prefs/speaker_geometry.xml`. */
        const val PREFS = "speaker_geometry"
        private const val KEY_TARGET = "target"

        /**
         * Restores the geometry from a backed-up `speaker_geometry.xml`. It goes through the
         * SharedPreferences API rather than copying the file over, because the process caches each
         * prefs file in memory: a copied file would be ignored and overwritten by the next edit.
         * [replace] clears the current values first (a clean restore); otherwise it merges.
         */
        fun restoreFrom(context: Context, backedUp: File, replace: Boolean) {
            val floats = HashMap<String, Float>()
            var target: String? = null
            backedUp.inputStream().use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, null) }
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    val name = parser.getAttributeValue(null, "name") ?: continue
                    when (parser.name) {
                        "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()
                            ?.takeIf { name.startsWith("cm_") }?.let { floats[name] = it }
                        "string" -> if (name == KEY_TARGET) target = parser.nextText()
                    }
                }
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (replace) clear()
                for ((key, value) in floats) putFloat(key, value)
                target?.let { putString(KEY_TARGET, it) }
            }.commit()
        }
    }
}

@Composable
fun rememberSpeakerGeometry(): SpeakerGeometryState {
    val context = LocalContext.current
    val state = remember { SpeakerGeometryState(context) }
    DisposableEffect(state) {
        state.startListening()
        onDispose { state.stopListening() }
    }
    return state
}
