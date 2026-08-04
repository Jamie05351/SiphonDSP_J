package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwControlBuilder
import kotlin.math.roundToInt

/** Dedicated "Crossovers & Tilt" screen: BMW subsonic/crossover filters and the post-sum
 *  tonality tilt, moved out of the Parametric EQ graph's cramped side panel so their numeric
 *  fields get full-screen room, same as Gains and Delay/Polarity. The live graph on the
 *  Parametric EQ screen still renders these curves directly from the same underlying
 *  NativeBmwDspValues array; this page is only for editing the numbers. */
class CrossoverTiltFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val scroll = NestedScrollView(requireContext()).apply { isFillViewport = true }
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val values = NativeBmwDspValues.load(requireContext())
        val builder = BmwControlBuilder(requireContext(), root, values) { updated ->
            NativeBmwDspValues.save(requireContext(), updated)
            NativeBmwDspValues.broadcast(requireContext(), updated)
        }
        builder.sectionCard(getString(R.string.bmw_dsp_crossovers)) {
            addSwitchRow(getString(R.string.bmw_dsp_subsonic_bw2), getString(R.string.bmw_dsp_subsonic_bw2_subtitle), NativeBmwDspValues.INDEX_SUBSONIC_ENABLED)
            addSliderRow(getString(R.string.bmw_dsp_subsonic_freq), NativeBmwDspValues.INDEX_SUBSONIC_FREQ, 20f, 60f, 1f, "Hz")
            addSwitchRow(getString(R.string.bmw_dsp_mute_low_band), null, NativeBmwDspValues.INDEX_LOW_MUTE)
            addSliderRow(getString(R.string.bmw_dsp_low_lpf_freq), NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ, 80f, 200f, 1f, "Hz")
            addSwitchRow(getString(R.string.bmw_dsp_lr4_topology), getString(R.string.bmw_dsp_lr4_topology_subtitle), NativeBmwDspValues.INDEX_LOW_LR4)
            addSwitchRow(getString(R.string.bmw_dsp_mute_mid_band), null, NativeBmwDspValues.INDEX_MID_MUTE)
            addSliderRow(getString(R.string.bmw_dsp_mid_hpf_freq), NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ, 80f, 200f, 1f, "Hz")
        }
        builder.sectionCard(getString(R.string.bmw_dsp_tilt_section)) {
            addSwitchRow(getString(R.string.bmw_dsp_tilt_active), getString(R.string.bmw_dsp_tilt_active_subtitle), NativeBmwDspValues.INDEX_TILT_ENABLED)
            addSliderRow(getString(R.string.bmw_dsp_tilt_amount), NativeBmwDspValues.INDEX_TILT_AMOUNT, -6f, 6f, .1f, "dB")
            addSliderRow(getString(R.string.bmw_dsp_tilt_pivot), NativeBmwDspValues.INDEX_TILT_FREQ, 200f, 2000f, 1f, "Hz")
        }
        builder.sectionCard(getString(R.string.bmw_dsp_mono_bass)) {
            addSwitchRow(getString(R.string.bmw_dsp_mono_bass), getString(R.string.bmw_dsp_mono_bass_subtitle), NativeBmwDspValues.INDEX_MONO_BASS_ENABLED)
            addSliderRow(getString(R.string.bmw_dsp_mono_bass_freq), NativeBmwDspValues.INDEX_MONO_BASS_FREQ, 40f, 120f, 1f, "Hz")
            addSliderRow(getString(R.string.bmw_dsp_mono_bass_blend), NativeBmwDspValues.INDEX_MONO_BASS_BLEND, 0f, 100f, 1f, "%")
            addSliderRow(getString(R.string.bmw_dsp_mono_bass_makeup), NativeBmwDspValues.INDEX_MONO_BASS_MAKEUP, -6f, 6f, .1f, "dB")
        }

        return scroll
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
