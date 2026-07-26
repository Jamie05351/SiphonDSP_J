package app.siphondsp.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import app.siphondsp.R
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
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
    private val fullRangeBands = ParametricEqBandList()
    private val lowBandBands = ParametricEqBandList()
    private val midBandBands = ParametricEqBandList()
    private var selectedScope = Scope.FULL_RANGE
    private var selectedIndex = -1
    private var loadingControls = false
    private var preampDb = 0.0

    private val bands: ParametricEqBandList
        get() = when (selectedScope) {
            Scope.FULL_RANGE -> fullRangeBands
            Scope.LOW_BAND -> lowBandBands
            Scope.MID_BAND -> midBandBands
        }

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
    private lateinit var scopeGroup: MaterialButtonToggleGroup

    private val numberFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val peqPrefs = requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
        fullRangeBands.deserialize(
            peqPrefs.getString(
                getString(R.string.key_peq_bands),
                requireArguments().getString(ARG_BANDS).orEmpty(),
            ).orEmpty()
        )
        preampDb = peqPrefs.getFloat(
            getString(R.string.key_peq_preamp),
            requireArguments().getDouble(ARG_PREAMP, 0.0).toFloat(),
        ).toDouble()

        val bandPrefs = requireContext().getSharedPreferences(BAND_PEQ_PREFS, Context.MODE_PRIVATE)
        lowBandBands.deserialize(bandPrefs.getString(KEY_LOW_BANDS, EMPTY_PEQ).orEmpty())
        midBandBands.deserialize(bandPrefs.getString(KEY_MID_BANDS, EMPTY_PEQ).orEmpty())
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
        scopeGroup = view.findViewById(R.id.live_eq_scope_group)

        view.findViewById<MaterialButton>(R.id.live_eq_close).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.live_eq_add).setOnClickListener {
            bands.add(defaultBand())
            selectedIndex = bands.lastIndex
            rebuildBandChips()
            loadSelectedBand()
            publishChanges()
        }

        frequency.setOnSeekBarChangeListener(changeListener { updateSelectedBand() })
        gain.setOnSeekBarChangeListener(changeListener { updateSelectedBand() })
        q.setOnSeekBarChangeListener(changeListener { updateSelectedBand() })
        filterGroup.addOnButtonCheckedListener { _, _, checked -> if (checked && !loadingControls) updateSelectedBand() }
        channelGroup.addOnButtonCheckedListener { _, _, checked -> if (checked && !loadingControls) updateSelectedBand() }
        scopeGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked && !loadingControls) {
                selectedScope = when (checkedId) {
                    R.id.live_eq_scope_low -> Scope.LOW_BAND
                    R.id.live_eq_scope_mid -> Scope.MID_BAND
                    else -> Scope.FULL_RANGE
                }
                selectedIndex = if (bands.isEmpty()) -1 else 0
                rebuildBandChips()
                loadSelectedBand()
            }
        }

        loadingControls = true
        scopeGroup.check(R.id.live_eq_scope_full)
        loadingControls = false
        selectedIndex = if (bands.isEmpty()) -1 else 0
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
                    bands.removeAt(index)
                    selectedIndex = when {
                        bands.isEmpty() -> -1
                        selectedIndex >= bands.size -> bands.lastIndex
                        else -> selectedIndex
                    }
                    rebuildBandChips()
                    loadSelectedBand()
                    publishChanges()
                    true
                }
            }
            bandGroup.addView(chip)
            if (index == selectedIndex) bandGroup.check(chip.id)
        }
    }

    private fun setEditorControlsEnabled(enabled: Boolean) {
        frequency.isEnabled = enabled
        gain.isEnabled = enabled
        q.isEnabled = enabled
        for (index in 0 until filterGroup.childCount) filterGroup.getChildAt(index).isEnabled = enabled
        for (index in 0 until channelGroup.childCount) channelGroup.getChildAt(index).isEnabled = enabled
    }

    private fun loadSelectedBand() {
        val band = bands.getOrNull(selectedIndex)
        if (band == null) {
            selectedIndex = -1
            setEditorControlsEnabled(false)
            frequencyValue.text = "Frequency: —"
            gainValue.text = "Gain: —"
            qValue.text = "Q: —"
            updateSurface()
            return
        }

        setEditorControlsEnabled(true)
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
        updateSurface()
        if (selectedIndex in 0 until bandGroup.childCount) bandGroup.check(bandGroup.getChildAt(selectedIndex).id)
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
        updateSurface()
        publishChanges()
    }

    private fun updateSurface() {
        val saved = requireContext().getSharedPreferences(NATIVE_BMW_PREFS, Context.MODE_PRIVATE)
            .getString(NATIVE_BMW_KEY, null)
        val values = saved?.split(',')?.mapNotNull(String::toDoubleOrNull)
        val lowPassHz = values?.getOrNull(15) ?: 150.0
        val lowLr4 = (values?.getOrNull(16) ?: 0.0) >= 0.5
        val highPassHz = values?.getOrNull(18) ?: 125.0
        surface.setBmwSystemResponse(
            fullRangeBands,
            lowBandBands,
            midBandBands,
            preampDb,
            lowPassHz,
            lowLr4,
            highPassHz,
        )
    }

    private fun updateValueLabels(band: ParametricEqBand) {
        frequencyValue.text = "Frequency: ${numberFormat.format(band.frequency)} Hz"
        gainValue.text = "Gain: ${numberFormat.format(band.gain)} dB"
        qValue.text = "Q: ${numberFormat.format(band.q)}"
    }

    /** Persist the complete three-bank state before requesting one engine refresh. */
    private fun publishChanges() {
        val context = requireContext()
        val fullCommitted = context.getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
            .edit()
            .putString(getString(R.string.key_peq_bands), fullRangeBands.serialize())
            .putFloat(getString(R.string.key_peq_preamp), preampDb.toFloat())
            .commit()
        val bandsCommitted = context.getSharedPreferences(BAND_PEQ_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOW_BANDS, lowBandBands.serialize())
            .putString(KEY_MID_BANDS, midBandBands.serialize())
            .commit()
        if (fullCommitted && bandsCommitted) {
            context.sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        }
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

    private enum class Scope { FULL_RANGE, LOW_BAND, MID_BAND }

    companion object {
        const val REQUEST_KEY = "live_eq_result"
        const val RESULT_BANDS = "live_eq_bands"
        private const val ARG_BANDS = "bands"
        private const val ARG_PREAMP = "preamp"
        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0
        private const val MIN_Q = 0.1
        private const val MAX_Q = 30.0
        private const val NATIVE_BMW_PREFS = "native_bmw_dsp"
        private const val NATIVE_BMW_KEY = "values"
        const val BAND_PEQ_PREFS = "native_bmw_band_peq"
        const val KEY_LOW_BANDS = "low_bands"
        const val KEY_MID_BANDS = "mid_bands"
        const val EMPTY_PEQ = "PEQ: "

        fun newInstance(bands: ParametricEqBandList, preampDb: Double) = LiveEqBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_BANDS, bands.serialize())
                putDouble(ARG_PREAMP, preampDb)
            }
        }
    }
}
