package app.siphondsp.fragment

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.children
import androidx.recyclerview.widget.RecyclerView
import app.siphondsp.R
import app.siphondsp.adapter.RoundedRipplePreferenceGroupAdapter
import app.siphondsp.model.NativeBmwDspValues

/** Inline, expandable controls for the native BMW processor -- just the Measurements / routing
 *  rows now (LPF/HPF passthrough, band mutes, channel isolation, measurement mute). Gain
 *  structure, Delay/polarity, Subsonic/crossovers, Tilt and the Output all-pass sections all
 *  live on their own dedicated workspace screens. */
class NativeBmwDspCardFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var menuPreferences: SharedPreferences
    // Guards re-entrant onSharedPreferenceChanged callbacks fired by our own editor.put*()
    // calls below (writeValuesToMenu runs while onStart()'s listener is already registered) --
    // without this a menu refresh would immediately re-save and re-broadcast every key it just wrote.
    private var updatingMenu = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = MENU_PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        menuPreferences = requireContext().getSharedPreferences(MENU_PREFS, Context.MODE_PRIVATE)
        val values = NativeBmwDspValues.load(requireContext())
        writeValuesToMenu(values)
        setPreferencesFromResource(R.xml.dsp_native_bmw_preferences, rootKey)
        configureSectionCards(preferenceScreen)
    }

    private fun configureSectionCards(group: PreferenceGroup) {
        val sectionSpacing = resources.getDimensionPixelSize(R.dimen.bmw_dsp_section_spacing)
        group.children.forEach { preference ->
            if (preference is PreferenceCategory) {
                preference.layoutResource = R.layout.preference_bmw_section_header
                preference.extras.putInt(RoundedRipplePreferenceGroupAdapter.EXTRA_GROUP_TOP_MARGIN_PX, sectionSpacing)
                val rows = buildList { add(preference); addAll(preference.children.toList()) }
                rows.forEachIndexed { index, row ->
                    val background = when {
                        rows.size == 1 -> R.drawable.ripple_group_single
                        index == 0 -> R.drawable.ripple_group_top
                        index == rows.lastIndex -> R.drawable.ripple_group_bottom
                        else -> R.drawable.ripple_group_middle
                    }
                    row.extras.putInt(RoundedRipplePreferenceGroupAdapter.EXTRA_GROUP_BACKGROUND_RES, background)
                }
            }
            if (preference is PreferenceGroup) configureSectionCards(preference)
        }
    }

    override fun onStart() {
        super.onStart()
        menuPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        menuPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (updatingMenu) return
        if (key in MUTE_KEYS) {
            val values = NativeBmwDspValues.load(requireContext())
            val muted = menuPreferences.getBoolean(key, false)
            val muteValue = if (muted) 1f else 0f
            if (key == "bmw_mute_low_band") {
                values[NativeBmwDspValues.INDEX_LOW_MUTE] = muteValue
                values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_MUTE)] = muteValue
                values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_MUTE)] = muteValue
            } else {
                values[NativeBmwDspValues.INDEX_MID_MUTE] = muteValue
                values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_MUTE)] = muteValue
                values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_MUTE)] = muteValue
            }
            NativeBmwDspValues.save(requireContext(), values)
            NativeBmwDspValues.broadcast(requireContext(), values)
            return
        }
        val index = KEY_TO_INDEX[key] ?: return
        val values = NativeBmwDspValues.load(requireContext())
        values[index] = when {
            index in BOOLEAN_INDEXES -> if (menuPreferences.getBoolean(key, false)) 1f else 0f
            index in LIST_INDEXES -> menuPreferences.getString(key, "0")?.toFloatOrNull() ?: 0f
            else -> menuPreferences.getFloat(key, values[index])
        }
        NativeBmwDspValues.save(requireContext(), values)
        NativeBmwDspValues.broadcast(requireContext(), values)
    }

    private fun writeValuesToMenu(values: FloatArray) {
        updatingMenu = true
        val editor = menuPreferences.edit()
        KEY_TO_INDEX.forEach { (key, index) ->
            when {
                index in BOOLEAN_INDEXES -> editor.putBoolean(key, values[index] >= .5f)
                index in LIST_INDEXES -> editor.putString(key, values[index].toInt().toString())
                else -> editor.putFloat(key, values[index])
            }
        }
        editor.putBoolean("bmw_mute_low_band", values[NativeBmwDspValues.INDEX_LOW_MUTE] >= .5f)
        editor.putBoolean("bmw_mute_mid_band", values[NativeBmwDspValues.INDEX_MID_MUTE] >= .5f)
        editor.apply()
        updatingMenu = false
    }

    override fun onCreateRecyclerView(
        inflater: android.view.LayoutInflater,
        parent: android.view.ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
        itemAnimator = null
        isNestedScrollingEnabled = false
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        RoundedRipplePreferenceGroupAdapter(preferenceScreen)

    companion object {
        private const val MENU_PREFS = "native_bmw_dsp_menu"
        // Only the "Measurements / routing" category (indices 1-4) renders inline here. Gain
        // structure, Delay/polarity, Subsonic/crossovers, Tilt and Output all-pass have all
        // relocated to dedicated screens. The master enable switch (index 0) was removed
        // entirely -- NativeBmwDspValues.load() forces it on unconditionally now.
        private val BOOLEAN_INDEXES = setOf(1, 2)
        private val LIST_INDEXES = setOf(3, 4)
        private val KEY_TO_INDEX = linkedMapOf(
            "bmw_lpf_passthrough" to 1,
            "bmw_hpf_passthrough" to 2,
            "bmw_channel_isolation" to 3,
            "bmw_measurement_mute" to 4,
        )
        private val MUTE_KEYS = setOf("bmw_mute_low_band", "bmw_mute_mid_band")

        fun newInstance() = NativeBmwDspCardFragment()
    }
}
