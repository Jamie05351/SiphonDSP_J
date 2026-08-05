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

/** Inline, expandable controls for the native BMW processor. */
class NativeBmwDspCardFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var menuPreferences: SharedPreferences
    private var updatingMenu = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = MENU_PREFS
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        menuPreferences = requireContext().getSharedPreferences(MENU_PREFS, Context.MODE_PRIVATE)
        val values = NativeBmwDspValues.load(requireContext())
        writeValuesToMenu(values)
        setPreferencesFromResource(R.xml.dsp_native_bmw_preferences, rootKey)
        findPreference<androidx.preference.Preference>("route_reset_stereo")?.setOnPreferenceClickListener {
            val values = NativeBmwDspValues.load(requireContext())
            NativeBmwDspValues.DEFAULTS.copyInto(values, NativeBmwDspValues.INDEX_ROUTING,
                NativeBmwDspValues.INDEX_ROUTING, NativeBmwDspValues.INDEX_ROUTING + NativeBmwDspValues.ROUTING_VALUE_COUNT)
            NativeBmwDspValues.save(requireContext(), values)
            NativeBmwDspValues.broadcast(requireContext(), values)
            writeValuesToMenu(values)
            true
        }
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
        if (key == "allpass_output") {
            writeAllPassToMenu(NativeBmwDspValues.load(requireContext()))
            return
        }
        if (key in ALL_PASS_KEYS) {
            val values = NativeBmwDspValues.load(requireContext())
            val output = menuPreferences.getString("allpass_output", "0")?.toIntOrNull()?.coerceIn(0, 3) ?: 0
            val section = if (key!!.startsWith("allpass_1")) 0 else 1
            val base = NativeBmwDspValues.INDEX_ALL_PASS +
                (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
            values[base + when {
                key.endsWith("enabled") -> 0
                key.endsWith("order") -> 1
                key.endsWith("frequency") -> 2
                else -> 3
            }] = when {
                key.endsWith("enabled") -> if (menuPreferences.getBoolean(key, false)) 1f else 0f
                key.endsWith("order") -> menuPreferences.getString(key, "2")?.toFloatOrNull() ?: 2f
                key.endsWith("frequency") -> menuPreferences.getInt(key, 150).toFloat()
                else -> menuPreferences.getInt(key, 71) / 100f
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
            index in ROUTING_INDEXES -> menuPreferences.getInt(key, (values[index] * 100f).toInt()) / 100f
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
                index in ROUTING_INDEXES -> editor.putInt(key, (values[index] * 100f).toInt())
                else -> editor.putFloat(key, values[index])
            }
        }
        editor.apply()
        writeAllPassToMenu(values)
        updatingMenu = false
    }

    private fun writeAllPassToMenu(values: FloatArray) {
        updatingMenu = true
        val output = menuPreferences.getString("allpass_output", "0")?.toIntOrNull()?.coerceIn(0, 3) ?: 0
        val editor = menuPreferences.edit()
        repeat(2) { section ->
            val base = NativeBmwDspValues.INDEX_ALL_PASS +
                (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
            val prefix = "allpass_${section + 1}_"
            editor.putBoolean(prefix + "enabled", values[base] >= .5f)
            editor.putString(prefix + "order", values[base + 1].toInt().toString())
            editor.putInt(prefix + "frequency", values[base + 2].toInt())
            editor.putInt(prefix + "q", (values[base + 3] * 100f).toInt())
        }
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
        // Gain structure, Delay/polarity, Subsonic/crossovers and Tilt have all
        // relocated to dedicated screens (GainLimiterFragment and CrossoverTiltFragment)
        // -- only the "Measurements / routing" category (indices 1-4) still renders inline here.
        // The master enable switch (index 0) was removed from this screen entirely --
        // it was redundant with the app-level on/off, and NativeBmwDspValues.load()
        // now forces index 0 on unconditionally, so there's nothing left to bind here.
        private val BOOLEAN_INDEXES = setOf(1, 2)
        private val LIST_INDEXES = setOf(3, 4)
        private val ROUTING_INDEXES = (NativeBmwDspValues.INDEX_ROUTING until
            NativeBmwDspValues.INDEX_ROUTING + NativeBmwDspValues.ROUTING_VALUE_COUNT).toSet()
        private val KEY_TO_INDEX = linkedMapOf(
            "bmw_lpf_passthrough" to 1,
            "bmw_hpf_passthrough" to 2,
            "bmw_channel_isolation" to 3,
            "bmw_measurement_mute" to 4,
            "route_low_left_fl" to 46, "route_low_left_fr" to 47,
            "route_low_right_fl" to 48, "route_low_right_fr" to 49,
            "route_mid_left_fl" to 50, "route_mid_left_fr" to 51,
            "route_mid_right_fl" to 52, "route_mid_right_fr" to 53,
        )
        private val ALL_PASS_KEYS = setOf(
            "allpass_1_enabled", "allpass_1_order", "allpass_1_frequency", "allpass_1_q",
            "allpass_2_enabled", "allpass_2_order", "allpass_2_frequency", "allpass_2_q",
        )

        fun newInstance() = NativeBmwDspCardFragment()
    }
}
