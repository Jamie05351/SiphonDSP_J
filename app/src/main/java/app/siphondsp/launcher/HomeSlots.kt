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
            value.startsWith("car:") -> CarFunction.byId(value.removePrefix("car:"))?.let(::Car) ?: Empty
            else -> Empty
        }
    }
}

/**
 * The home screen's nine app slots, saved in the launcher's own preferences. Changes are kept in
 * [slots] for the screen and written straight away.
 *
 * On first run the slots start as the earlier M Launcher's layout. Apps are matched to those names only
 * when exactly one installed app has that name ([fillDefaults]); anything ambiguous or missing
 * stays an empty slot to pick by hand.
 */
class HomeSlots private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_home", Context.MODE_PRIVATE)
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

    /** Swaps two slots (moving an icon onto another one). */
    fun swap(a: Int, b: Int) {
        if (a !in 0 until SLOTS || b !in 0 until SLOTS || a == b) return
        val next = _slots.value.toMutableList()
        next[a] = next[b].also { next[b] = next[a] }
        _slots.value = next
        prefs.edit {
            putString(key(a), next[a].encode())
            putString(key(b), next[b].encode())
        }
    }

    /**
     * Once, the first time the app list is known: fills empty slots from [Defaults] by unique name
     * match. After the user changes any slot, or once this has run, it never runs again.
     */
    fun fillDefaults(apps: List<AppEntry>) {
        if (prefs.getBoolean(KEY_DEFAULTS_DONE, false)) return
        val next = _slots.value.toMutableList()
        Defaults.forEachIndexed { i, name ->
            if (next[i] != SlotTarget.Empty) return@forEachIndexed
            next[i] = when (name) {
                FACTORY -> SlotTarget.Car(CarFunction.SETTINGS)
                else -> apps.filter { normalise(it.label) == normalise(name) }.singleOrNull()
                    ?.let { SlotTarget.App(it.component) } ?: SlotTarget.Empty
            }
        }
        _slots.value = next
        prefs.edit {
            next.forEachIndexed { i, t -> putString(key(i), t.encode()) }
            putBoolean(KEY_DEFAULTS_DONE, true)
        }
    }

    companion object {
        const val SLOTS = 9
        private const val KEY_DEFAULTS_DONE = "defaults_done"
        private const val FACTORY = "\u0000factory"
        private val Defaults = listOf(
            "Waze", "Spotify", "Google Maps", FACTORY, "Drive", "MiXplorer", "KSW Toolkit 3", "Play Store", "Radio",
        )

        private fun key(i: Int) = "slot_$i"
        private fun normalise(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        @Volatile private var instance: HomeSlots? = null

        fun get(context: Context): HomeSlots =
            instance ?: synchronized(this) {
                instance ?: HomeSlots(context.applicationContext).also { instance = it }
            }
    }
}
