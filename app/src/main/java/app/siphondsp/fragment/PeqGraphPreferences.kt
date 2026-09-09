package app.siphondsp.fragment

import android.content.Context
import androidx.core.content.edit
import app.siphondsp.compose.screens.PeqChannelDisplay
import app.siphondsp.compose.screens.PeqGraphMode

/**
 * Typed access to the Parametric EQ graph's `peq_graph_display` SharedPreferences: overlay
 * visibility, which channel(s) to draw, the response mode, and the raw Graph/List mode name.
 *
 * Since the graph is a Compose `PeqGraph` now (roadmap Phase 10c), the persisted enums are the
 * Compose ones — `PeqChannelDisplay` / `PeqGraphMode`. Their names still match the old
 * `ParametricEqSurface` enums (`BOTH`/`LEFT`/`RIGHT`, `MAGNITUDE`/`PHASE`), so an on-disk value
 * from before the port still parses; a dropped mode (`MAGNITUDE_PHASE` / `GROUP_DELAY`) simply
 * falls back to `MAGNITUDE`.
 */
class PeqGraphPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var showIndividualFilters: Boolean
        get() = prefs.getBoolean(KEY_SHOW_OVERLAYS, true)
        set(value) {
            prefs.edit { putBoolean(KEY_SHOW_OVERLAYS, value) }
        }

    var channelDisplay: PeqChannelDisplay
        get() = runCatching {
            PeqChannelDisplay.valueOf(prefs.getString(KEY_CHANNEL, PeqChannelDisplay.BOTH.name)!!)
        }.getOrDefault(PeqChannelDisplay.BOTH)
        set(value) {
            prefs.edit { putString(KEY_CHANNEL, value.name) }
        }

    var responseMode: PeqGraphMode
        get() = runCatching {
            PeqGraphMode.valueOf(prefs.getString(KEY_RESPONSE_MODE, PeqGraphMode.MAGNITUDE.name)!!)
        }.getOrDefault(PeqGraphMode.MAGNITUDE)
        set(value) {
            prefs.edit { putString(KEY_RESPONSE_MODE, value.name) }
        }

    /** Raw persisted channel name, falling back to BOTH -- left unparsed for the private backup
     *  export, which stores whatever string is on disk. */
    val channelDisplayName: String
        get() = prefs.getString(KEY_CHANNEL, PeqChannelDisplay.BOTH.name) ?: PeqChannelDisplay.BOTH.name

    /** Raw persisted Graph/List mode name (null when never set); the screen maps it to its
     *  private display-mode enum with its own default. */
    var listModeName: String?
        get() = prefs.getString(KEY_LIST_MODE, null)
        set(value) {
            prefs.edit { putString(KEY_LIST_MODE, value) }
        }

    /** Single-transaction write used by the private backup restore. */
    fun writeBackupGraphDisplay(showIndividualFilters: Boolean, channelDisplayName: String) {
        prefs.edit {
            putBoolean(KEY_SHOW_OVERLAYS, showIndividualFilters)
            putString(KEY_CHANNEL, channelDisplayName)
        }
    }

    companion object {
        const val PREFS = "peq_graph_display"
        private const val KEY_SHOW_OVERLAYS = "show_individual_filters"
        private const val KEY_CHANNEL = "channel_display"
        private const val KEY_LIST_MODE = "peq_display_mode"
        // Unrelated to KEY_LIST_MODE above (that's the Graph/List toggle) -- this persists the
        // graph's PeqGraphMode (Magnitude / Phase).
        private const val KEY_RESPONSE_MODE = "response_display_mode"
    }
}
