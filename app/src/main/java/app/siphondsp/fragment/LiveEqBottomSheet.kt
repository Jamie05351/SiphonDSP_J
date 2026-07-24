package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.os.bundleOf
import app.siphondsp.R
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.view.ParametricEqSurface
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class LiveEqBottomSheet : BottomSheetDialogFragment() {
    private val bands = ParametricEqBandList()
    private var selectedIndex = 0
    private var loadingControls = false
    private var preampDb = 0.0

    private lateinit var bandGroup: ChipGroup
    private lateinit var surface: ParametricEqSurface
    private lateinit var frequency: SeekBar
    private lateinit var gain: SeekBar
    private lateinit var q: SeekBar
    private lateinit var frequencyValue: TextView
    private lateinit var gainValue: TextView
    private lateinit var qValue: TextView
    private lateinit var filterGroup: MaterialButtonToggleGroup
    private lateinit var channelGroup: MaterialButtonToggleGroup

    private val numberFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bands.deserialize(requireArguments().getString(ARG_BANDS).orEmpty())
        preampDb = requireArguments().getDouble(ARG_PREAMP, 0.0)
        if (bands.isEmpty()) bands.add(defaultBand())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_live_eq, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bandGroup = view.findViewById(R.id.live_eq_band_group)
        surface = view.findViewById(R.id.live_eq_surface)
        frequency = view.findViewById(R.id.live_eq_frequency)
        gain = view.findViewById(R.id.live_eq_gain)
        q = view.findViewById(R.id.live_eq_q)
        frequencyValue = view.findViewById(R.id.live_eq_frequency_value)
        gainValue = view.findViewById(R.id.live_eq_gain_value)
        qValue = view.findViewById(R.id.live_eq_q_value)
        filterGroup = view.findViewById(R.id.live_eq_filter_group)
        channelGroup = view.findViewById(R.id.live_eq_channel_group)

        view.findViewById<MaterialButton>(R.id.live_eq_close).setOnClickListener { dismiss() }
        val addButton = view.findViewById<MaterialButton>(R.id.live_eq_add)
        addButton.setOnClickListener {
            bands.add(defaultBand())
            selectedIndex = bands.lastIndex
            rebuildBandChips()
            loadSelectedBand()
            publishChanges()
        }
        (addButton.parent as? ViewGroup)?.let { parent ->
            parent.addView(MaterialButton(requireContext()).apply {
                text = "BMW DSP"
                setOnClickListener {
                    NativeBmwDspBottomSheet().show(parentFragmentManager, NativeBmwDspBottomSheet.TAG)
                }
            }, parent.indexOfChild(addButton) + 1)
        }

        frequency.setOnSeekBarChangeListener(changeListener { updateSelectedBand() })
        gain.setOnSeekBarChangeListener(changeListener { updateSelectedBand() })
        q.setOnSeekBarChangeListener(changeListener { updateSelectedBand() })
        filterGroup.addOnButtonCheckedListener { _, _, checked -> if (checked && !loadingControls) updateSelectedBand() }
        channelGroup.addOnButtonCheckedListener { _, _, checked -> if (checked && !loadingControls) updateSelectedBand() }

        rebuildBandChips()
        loadSelectedBand()
    }

    private fun changeListener(onChanged: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser && !loadingControls) onChanged()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun rebuildBandChips() {
        bandGroup.removeAllViews()
        bands.forEachIndexed { index, band ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                isCheckable = true
                isCheckedIconVisible = false
                text = "${index + 1} · ${band.filterType.displayLabel} · ${band.channel.displayLabel}"
                setOnClickListener { selectedIndex = index; loadSelectedBand() }
                setOnLongClickListener {
                    if (bands.size > 1) {
                        bands.removeAt(index)
                        selectedIndex = selectedIndex.coerceAtMost(bands.lastIndex)
                        rebuildBandChips()
                        loadSelectedBand()
                        publishChanges()
                    }
                    true
                }
            }
            bandGroup.addView(chip)
            if (index == selectedIndex) bandGroup.check(chip.id)
        }
    }

    private fun loadSelectedBand() {
        val band = bands.getOrNull(selectedIndex) ?: return
        loadingControls = true
        frequency.progress = frequencyToProgress(band.frequency)
        gain.progress = ((band.gain + 30.0) * 10.0).roundToInt().coerceIn(0, 600)
        q.progress = qToProgress(band.q)
        filterGroup.check(when (band.filterType) {
            ParametricEqFilterType.PEAKING -> R.id.live_eq_filter_peaking
            ParametricEqFilterType.LOW_SHELF -> R.id.live_eq_filter_low_shelf
            ParametricEqFilterType.HIGH_SHELF -> R.id.live_eq_filter_high_shelf
        })
        channelGroup.check(when (band.channel) {
            ParametricEqChannel.LEFT_RIGHT -> R.id.live_eq_channel_both
            ParametricEqChannel.LEFT -> R.id.live_eq_channel_left
            ParametricEqChannel.RIGHT -> R.id.live_eq_channel_right
        })
        loadingControls = false
        updateValueLabels(band)
        surface.setBands(bands, preampDb)
    }

    private fun updateSelectedBand() {
        val old = bands.getOrNull(selectedIndex) ?: return
        val updated = ParametricEqBand(
            frequency = progressToFrequency(frequency.progress),
            gain = gain.progress / 10.0 - 30.0,
            q = progressToQ(q.progress),
            filterType = when (filterGroup.checkedButtonId) {
                R.id.live_eq_filter_low_shelf -> ParametricEqFilterType.LOW_SHELF
                R.id.live_eq_filter_high_shelf -> ParametricEqFilterType.HIGH_SHELF
                else -> ParametricEqFilterType.PEAKING
            },
            channel = when (channelGroup.checkedButtonId) {
                R.id.live_eq_channel_left -> ParametricEqChannel.LEFT
                R.id.live_eq_channel_right -> ParametricEqChannel.RIGHT
                else -> ParametricEqChannel.LEFT_RIGHT
            },
            uuid = old.uuid,
        )
        bands[selectedIndex] = updated
        updateValueLabels(updated)
        (bandGroup.getChildAt(selectedIndex) as? Chip)?.text =
            "${selectedIndex + 1} · ${updated.filterType.displayLabel} · ${updated.channel.displayLabel}"
        surface.setBands(bands, preampDb)
        publishChanges()
    }

    private fun updateValueLabels(band: ParametricEqBand) {
        frequencyValue.text = "Frequency: ${numberFormat.format(band.frequency)} Hz"
        gainValue.text = "Gain: ${numberFormat.format(band.gain)} dB"
        qValue.text = "Q: ${numberFormat.format(band.q)}"
    }

    private fun publishChanges() {
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_BANDS to bands.serialize()))
    }

    private fun defaultBand() = ParametricEqBand(1000.0, 0.0, 1.41)
    private fun frequencyToProgress(value: Double): Int =
        (ln(value.coerceIn(MIN_FREQ, MAX_FREQ) / MIN_FREQ) / ln(MAX_FREQ / MIN_FREQ) * 1000.0).roundToInt().coerceIn(0, 1000)
    private fun progressToFrequency(progress: Int): Double =
        MIN_FREQ * exp(progress.coerceIn(0, 1000) / 1000.0 * ln(MAX_FREQ / MIN_FREQ))
    private fun qToProgress(value: Double): Int =
        (ln(value.coerceIn(MIN_Q, MAX_Q) / MIN_Q) / ln(MAX_Q / MIN_Q) * 1000.0).roundToInt().coerceIn(0, 1000)
    private fun progressToQ(progress: Int): Double =
        MIN_Q * exp(progress.coerceIn(0, 1000) / 1000.0 * ln(MAX_Q / MIN_Q))

    companion object {
        const val REQUEST_KEY = "live_eq_result"
        const val RESULT_BANDS = "live_eq_bands"
        private const val ARG_BANDS = "bands"
        private const val ARG_PREAMP = "preamp"
        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0
        private const val MIN_Q = 0.1
        private const val MAX_Q = 30.0

        fun newInstance(bands: ParametricEqBandList, preampDb: Double) = LiveEqBottomSheet().apply {
            arguments = bundleOf(ARG_BANDS to bands.serialize(), ARG_PREAMP to preampDb)
        }
    }
}
