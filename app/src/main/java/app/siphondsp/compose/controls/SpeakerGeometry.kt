package app.siphondsp.compose.controls

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Where the listener sits; the path lines and the distances are measured from here. */
enum class ListeningSeat { DRIVER, PASSENGER }

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
 * The seat choice and the six measured distances, remembered across launches in SharedPreferences.
 * Reads and writes go through Compose state, so cards and the map update as soon as one changes.
 */
class SpeakerGeometryState internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val overrides = mutableStateMapOf<String, Float>().apply {
        for ((key, value) in prefs.all) if (key.startsWith("cm_") && value is Float) put(key, value)
    }

    var seat by mutableStateOf(readSeat())
        private set

    fun selectSeat(next: ListeningSeat) {
        seat = next
        prefs.edit().putString(KEY_SEAT, next.name).apply()
    }

    fun distanceCm(id: DriverId, forSeat: ListeningSeat = seat): Float =
        overrides[key(forSeat, id)] ?: SpeakerGeometryMath.defaultDistanceCm(forSeat, id)

    fun setDistanceCm(id: DriverId, cm: Float, forSeat: ListeningSeat = seat) {
        val k = key(forSeat, id)
        overrides[k] = cm
        prefs.edit().putFloat(k, cm).apply()
    }

    fun alignDelayMs(id: DriverId): Float = SpeakerGeometryMath.alignDelayMs(
        distanceCm(id),
        SpeakerGeometryMath.allDrivers.map { distanceCm(it) },
    )

    private fun readSeat(): ListeningSeat =
        runCatching { ListeningSeat.valueOf(prefs.getString(KEY_SEAT, null) ?: "") }
            .getOrDefault(ListeningSeat.DRIVER)

    private fun key(seat: ListeningSeat, id: DriverId) =
        "cm_${seat.name}_${id.kind.name}_${if (id.left) "L" else "R"}"

    private companion object {
        const val PREFS = "speaker_geometry"
        const val KEY_SEAT = "seat"
    }
}

@Composable
fun rememberSpeakerGeometry(): SpeakerGeometryState {
    val context = LocalContext.current
    return remember { SpeakerGeometryState(context) }
}
