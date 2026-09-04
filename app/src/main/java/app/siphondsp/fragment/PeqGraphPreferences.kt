package app.siphondsp.fragment

import android.content.Context
import app.siphondsp.view.ParametricEqSurface

/**
 * Typed access to the Parametric EQ graph's `peq_graph_display` SharedPreferences: overlay
 * visibility, which channel(s) to draw, the response mode, and the raw Graph/List mode name.
 *
 * Extracted from [ParametricEqualizerFragment], where `getSharedPreferences(...)` plus the
 * `runCatching { Enum.valueOf(pref) }.getOrDefault(...)` dance was repeated at four call sites.
 * Every accessor reproduces its original call verbatim.
 */
class PeqGraphPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var showIndividualFilters: Boolean
        get() = prefs.getBoolean(KEY_SHOW_OVERLAYS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_OVERLAYS, value).apply()
        }

    var channelDisplay: ParametricEqSurface.ChannelDisplay
        get() = runCatching {
            ParametricEqSurface.ChannelDisplay.valueOf(
                prefs.getString(KEY_CHANNEL, ParametricEqSurface.ChannelDisplay.BOTH.name)!!
            )
        }.getOrDefault(ParametricEqSurface.ChannelDisplay.BOTH)
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    var responseMode: ParametricEqSurface.DisplayMode
        get() = runCatching {
            ParametricEqSurface.DisplayMode.valueOf(
                prefs.getString(KEY_RESPONSE_MODE, ParametricEqSurface.DisplayMode.MAGNITUDE.name)!!
            )
        }.getOrDefault(ParametricEqSurface.DisplayMode.MAGNITUDE)
        set(value) {
            prefs.edit().putString(KEY_RESPONSE_MODE, value.name).apply()
        }

    /** Raw persisted channel name, falling back to BOTH -- left unparsed for the private backup
     *  export, which stores whatever string is on disk. */
    val channelDisplayName: String
        get() = prefs.getString(KEY_CHANNEL, ParametricEqSurface.ChannelDisplay.BOTH.name)
            ?: ParametricEqSurface.ChannelDisplay.BOTH.name

    /** Raw persisted Graph/List mode name (null when never set); the fragment maps it to its
     *  private PeqDisplayMode enum with its own default. */
    var listModeName: String?
        get() = prefs.getString(KEY_LIST_MODE, null)
        set(value) {
            prefs.edit().putString(KEY_LIST_MODE, value).apply()
        }

    /** Single-transaction write used by the private backup restore. */
    fun writeBackupGraphDisplay(showIndividualFilters: Boolean, channelDisplayName: String) {
        prefs.edit()
            .putBoolean(KEY_SHOW_OVERLAYS, showIndividualFilters)
            .putString(KEY_CHANNEL, channelDisplayName)
            .apply()
    }

    companion object {
        const val PREFS = "peq_graph_display"
        private const val KEY_SHOW_OVERLAYS = "show_individual_filters"
        private const val KEY_CHANNEL = "channel_display"
        private const val KEY_LIST_MODE = "peq_display_mode"
        // Unrelated to KEY_LIST_MODE above (that's the Graph/List PeqDisplayMode toggle) -- this
        // persists ParametricEqSurface.DisplayMode (Magnitude/Phase/Group Delay).
        private const val KEY_RESPONSE_MODE = "response_display_mode"
    }
}
