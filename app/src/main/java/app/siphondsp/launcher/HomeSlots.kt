package app.siphondsp.launcher

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** What one home-screen slot opens: nothing yet, an installed app, or one of the car's functions. */
@Immutable
sealed interface SlotTarget {
    data object Empty : SlotTarget
    data class App(val component: ComponentName) : SlotTarget
    data class Car(val function: CarFunction) : SlotTarget

    fun encode(): String = when (this) {
        Empty -> ""
        is App -> "app:" + component.flattenToString()
        is Car -> "car:" + function.id
    }

    companion object {
        fun decode(value: String?): SlotTarget = when {
            value == null -> Empty
            value.startsWith("app:") -> ComponentName.unflattenFromString(value.removePrefix("app:"))?.let(::App) ?: Empty
            // A blocked function (the KSW EQ) is never a slot, even from a restored backup.
            value.startsWith("car:") -> CarFunction.byId(value.removePrefix("car:"))?.takeUnless { it.blocked }?.let(::Car) ?: Empty
            else -> Empty
        }
    }
}

/**
 * The home screen's app slots, one per home tile in the tile row's order (Navigation, Music, File
 * Explorer, Drive, KSW Toolkit, Settings, DSP), saved in the launcher's own preferences. Changes
 * are kept in [slots] for the screen and written straight away.
 *
 * On first run each slot starts as the app for its tile. Apps are matched to those names only when
 * exactly one installed app has that name ([fillDefaults]); anything ambiguous or missing stays an
 * empty slot to pick by hand. Slots saved by the old nine-icon grid meant nothing about the tiles,
 * so they are cleared once and filled again from the defaults.
 */
class HomeSlots private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("launcher_home", Context.MODE_PRIVATE).also { prefs ->
        if (prefs.getInt(KEY_LAYOUT, 1) < LAYOUT) {
            prefs.edit {
                for (i in 0 until OLD_SLOTS) remove(key(i))
                remove(KEY_DEFAULTS_DONE)
                putInt(KEY_LAYOUT, LAYOUT)
            }
        }
    }
    private val _slots = MutableStateFlow(List(SLOTS) { SlotTarget.decode(prefs.getString(key(it), null)) })
    val slots: StateFlow<List<SlotTarget>> = _slots.asStateFlow()

    fun set(index: Int, target: SlotTarget) {
        if (index !in 0 until SLOTS) return
        _slots.value = _slots.value.toMutableList().also { it[index] = target }
        prefs.edit {
            putString(key(index), target.encode())
            putBoolean(KEY_DEFAULTS_DONE, true)
        }
    }

    /** Replaces every slot at once (restoring a backup); missing slots are left empty. */
    fun setAll(targets: List<SlotTarget>) {
        val next = List(SLOTS) { targets.getOrElse(it) { SlotTarget.Empty } }
        _slots.value = next
        prefs.edit {
            next.forEachIndexed { i, target -> putString(key(i), target.encode()) }
            putBoolean(KEY_DEFAULTS_DONE, true)
        }
    }

    /** Swaps two slots (moving an icon onto another one). */
    fun swap(a: Int, b: Int) {
        if (a !in 0 until SLOTS || b !in 0 until SLOTS || a == b) return
        val next = _slots.value.toMutableList()
        next[a] = next[b].also { next[b] = next[a] }
        _slots.value = next
        prefs.edit {
            putString(key(a), next[a].encode())
            putString(key(b), next[b].encode())
            putBoolean(KEY_DEFAULTS_DONE, true)
        }
    }

    /**
     * Fills still-empty slots from [Defaults] by unique name match, each time the app list changes:
     * at boot the list can start partial, so apps that turn up later still land in their slot. It
     * stops for good once the user changes any slot, or once every default slot is filled.
     */
    fun fillDefaults(apps: List<AppEntry>) {
        if (prefs.getBoolean(KEY_DEFAULTS_DONE, false)) return
        val next = _slots.value.toMutableList()
        Defaults.forEachIndexed { i, name ->
            if (next[i] != SlotTarget.Empty) return@forEachIndexed
            next[i] = when (name) {
                NONE -> SlotTarget.Empty
                // Only on a head unit that has the KSW settings; elsewhere the slot stays free.
                FACTORY -> if (CarFunction.SETTINGS.isAvailable(context)) SlotTarget.Car(CarFunction.SETTINGS) else SlotTarget.Empty
                else -> apps.filter { normalise(it.label) == normalise(name) }.singleOrNull()
                    ?.let { SlotTarget.App(it.component) } ?: SlotTarget.Empty
            }
        }
        if (next == _slots.value) return
        _slots.value = next
        prefs.edit {
            next.forEachIndexed { i, t -> putString(key(i), t.encode()) }
            if (next.indices.all { next[it] != SlotTarget.Empty || Defaults[it] == NONE }) putBoolean(KEY_DEFAULTS_DONE, true)
        }
    }

    companion object {
        /** One per home tile. */
        const val SLOTS = 7
        private const val OLD_SLOTS = 9
        private const val KEY_DEFAULTS_DONE = "defaults_done"
        // 2: one slot per home tile; before, nine free slots in a grid.
        private const val KEY_LAYOUT = "layout"
        private const val LAYOUT = 2
        private const val FACTORY = "\u0000factory"
        private const val NONE = ""
        // In tile order; the DSP tile opens J_DSP's settings while its slot is empty.
        private val Defaults = listOf("Waze", "Spotify", "MiXplorer", "Drive", "KSW Toolkit 3", FACTORY, NONE)

        private fun key(i: Int) = "slot_$i"
        private fun normalise(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        @Volatile private var instance: HomeSlots? = null

        fun get(context: Context): HomeSlots =
            instance ?: synchronized(this) {
                instance ?: HomeSlots(context.applicationContext).also { instance = it }
            }
    }
}
