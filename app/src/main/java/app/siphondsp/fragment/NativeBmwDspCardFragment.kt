package app.siphondsp.fragment

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import app.siphondsp.R
import app.siphondsp.adapter.RoundedRipplePreferenceGroupAdapter

/** Main DSP-page entry point for the native BMW processor. */
class NativeBmwDspCardFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(Preference(requireContext()).apply {
                key = KEY_OPEN_BMW_DSP
                title = "BMW DSP"
                summary = "Crossovers, routing, delay, dynamics and live response"
                icon = requireContext().getDrawable(R.drawable.ic_baseline_equalizer_24dp)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    if (parentFragmentManager.findFragmentByTag(NativeBmwDspBottomSheet.TAG) == null) {
                        NativeBmwDspBottomSheet().show(parentFragmentManager, NativeBmwDspBottomSheet.TAG)
                    }
                    true
                }
            })
        }
    }

    override fun onCreateRecyclerView(
        inflater: android.view.LayoutInflater,
        parent: android.view.ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
        itemAnimator = null
        isNestedScrollingEnabled = false
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        RoundedRipplePreferenceGroupAdapter(preferenceScreen)

    companion object {
        private const val KEY_OPEN_BMW_DSP = "open_native_bmw_dsp"
        fun newInstance() = NativeBmwDspCardFragment()
    }
}
