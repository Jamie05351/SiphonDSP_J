package app.siphondsp.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import app.siphondsp.R
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.adapter.ParametricEqBandAdapter
import app.siphondsp.databinding.FragmentParametricEqBinding
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import app.siphondsp.utils.extensions.ContextExtensions.showYesNoAlert
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import com.google.android.material.chip.Chip
import timber.log.Timber
import java.util.UUID

class ParametricEqualizerFragment : Fragment() {
    private enum class SignalScope { FULL_RANGE, LOW_BAND, MID_BAND }

    private lateinit var binding: FragmentParametricEqBinding
    private lateinit var fullRangeBands: ParametricEqBandList
    private lateinit var lowBandBands: ParametricEqBandList
    private lateinit var midBandBands: ParametricEqBandList
    private var currentScope = SignalScope.FULL_RANGE
    private var liveEqChip: Chip? = null
    private val scopeChips = linkedMapOf<SignalScope, Chip>()

    private val adapter get() = binding.bandList.adapter as ParametricEqBandAdapter
    private val activeBands: ParametricEqBandList
        get() = when (currentScope) {
            SignalScope.FULL_RANGE -> fullRangeBands
            SignalScope.LOW_BAND -> lowBandBands
            SignalScope.MID_BAND -> midBandBands
        }
    private val activePreamp: Double
        get() = if (currentScope == SignalScope.FULL_RANGE) binding.preampInput.value.toDouble() else 0.0

    private var editorBandBackup: ParametricEqBand? = null
    private var editorBandUuid: UUID? = null
    private var editorActive = false
        set(value) {
            field = value
            binding.add.isEnabled = !value
            binding.reset.isEnabled = !value
            binding.importFile.isEnabled = !value
            binding.exportFile.isEnabled = !value
            binding.editString.isEnabled = !value
            liveEqChip?.isEnabled = !value
            scopeChips.values.forEach { it.isEnabled = !value }
        }

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
            val result = activeBands.fromApoString(text)
            if (currentScope == SignalScope.FULL_RANGE) {
                binding.preampInput.value = result.preampDb.toFloat()
            }
            binding.equalizerSurface.setBands(activeBands, activePreamp)
            save()
            updateViewState()
            val message = getString(R.string.peq_import_success, activeBands.size)
            requireContext().toast(
                if (result.skippedFilters > 0) "$message (${result.skippedFilters} malformed or unsupported lines skipped)"
                else message
            )
        } catch (error: Exception) {
            Timber.e(error, "Failed to import PEQ file")
            requireContext().toast(R.string.peq_import_error)
        }
    }

    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val apoString = activeBands.toApoString(activePreamp)
            requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(apoString) }
            requireContext().toast(R.string.peq_export_success)
        } catch (error: Exception) {
            Timber.e(error, "Failed to export PEQ file")
            requireContext().toast("Export failed: ${error.message}")
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PRESET_LOADED) {
                activity?.finish()
                startActivity(Intent(requireContext(), ParametricEqualizerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requireContext().registerLocalReceiver(broadcastReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        requireContext().unregisterLocalReceiver(broadcastReceiver)
        super.onDestroy()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentParametricEqBinding.inflate(layoutInflater, container, false)
        binding.qInput.min = 0.1f

        binding.previewCard.setOnClickListener {
            if (resources.configuration.orientation != ORIENTATION_LANDSCAPE) {
                collapsePreview(!binding.equalizerSurface.isVisible)
            }
        }

        binding.reset.setOnClickListener {
            requireContext().showYesNoAlert(R.string.peq_reset_confirm_title, R.string.peq_reset_confirm) { confirmed ->
                if (confirmed) {
                    if (currentScope == SignalScope.FULL_RANGE) {
                        activeBands.deserialize(Constants.DEFAULT_PEQ)
                        binding.preampInput.value = 0f
                    } else {
                        activeBands.clear()
                    }
                    binding.equalizerSurface.setBands(activeBands, activePreamp)
                    editorDiscard()
                    updateViewState()
                    save()
                }
            }
        }

        binding.editString.setOnClickListener {
            requireContext().showInputAlert(
                layoutInflater,
                R.string.peq_edit_as_string,
                R.string.peq_edit_hint,
                activeBands.toApoString(activePreamp),
                false,
                null,
            ) { text ->
                text?.let {
                    val result = activeBands.fromApoString(it)
                    if (currentScope == SignalScope.FULL_RANGE) {
                        binding.preampInput.value = result.preampDb.toFloat()
                    }
                    binding.equalizerSurface.setBands(activeBands, activePreamp)
                    save()
                    updateViewState()
                }
            }
        }

        binding.add.setOnClickListener {
            if (editorActive) return@setOnClickListener
            editorBandBackup = null
            editorBandUuid = null
            editorActive = true
            binding.freqInput.value = 1000f
            binding.gainInput.value = 0f
            binding.qInput.value = 1.41f
            setFilterTypeSelection(ParametricEqFilterType.PEAKING)
            setChannelSelection(ParametricEqChannel.LEFT_RIGHT)
            updateViewState()
        }

        binding.importFile.setOnClickListener { importFileLauncher.launch(arrayOf("text/plain", "text/*")) }
        binding.exportFile.setOnClickListener {
            val name = when (currentScope) {
                SignalScope.FULL_RANGE -> "parametric_eq.txt"
                SignalScope.LOW_BAND -> "low_band_parametric_eq.txt"
                SignalScope.MID_BAND -> "mid_band_parametric_eq.txt"
            }
            exportFileLauncher.launch(name)
        }

        binding.freqInput.setOnValueChangedListener { editorApply() }
        binding.gainInput.setOnValueChangedListener { editorApply() }
        binding.qInput.setOnValueChangedListener { editorApply() }
        binding.filterTypeGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) editorApply() }
        binding.channelGroup?.addOnButtonCheckedListener { _, _, checked -> if (checked) editorApply() }

        binding.freqInput.customStepScale = { value, _ ->
            when (value) {
                in 0f..400f -> 10f
                in 400f..600f -> 20f
                in 600f..1000f -> 50f
                in 1000f..5000f -> 100f
                in 5000f..Float.MAX_VALUE -> 500f
                else -> 10f
            }
        }
        binding.qInput.customStepScale = { value, _ ->
            when (value) {
                in 0f..1f -> 0.05f
                in 1f..5f -> 0.1f
                in 5f..10f -> 0.5f
                in 10f..Float.MAX_VALUE -> 1f
                else -> 0.1f
            }
        }

        binding.confirm.setOnClickListener { editorSave() }
        binding.cancel.setOnClickListener { editorDiscard() }
        binding.preampInput.setOnValueChangedListener {
            if (currentScope == SignalScope.FULL_RANGE) {
                binding.equalizerSurface.setPreampDb(binding.preampInput.value.toDouble())
                savePreamp()
            }
        }

        binding.bandList.layoutManager = LinearLayoutManager(requireContext())
        loadBands(savedInstanceState)
        installLiveEqChip()
        installScopeChips()
        installLiveEqResultListener()
        bindActiveScope()
        return binding.root
    }

    private fun installLiveEqChip() {
        val parent = binding.add.parent as? ViewGroup ?: return
        liveEqChip = Chip(requireContext()).apply {
            text = "Live EQ"
            isCheckable = false
            setOnClickListener {
                if (!editorActive) {
                    LiveEqBottomSheet.newInstance(activeBands, activePreamp)
                        .show(childFragmentManager, "live_eq")
                }
            }
        }
        parent.addView(liveEqChip, 1)
    }

    private fun installScopeChips() {
        val parent = binding.add.parent as? ViewGroup ?: return
        listOf(
            SignalScope.FULL_RANGE to "Full Range",
            SignalScope.LOW_BAND to "Low Band",
            SignalScope.MID_BAND to "Mid Band",
        ).forEach { (scope, label) ->
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isCheckedIconVisible = false
                setOnClickListener { switchScope(scope) }
            }
            scopeChips[scope] = chip
            parent.addView(chip, parent.childCount)
        }
        updateScopeChecks()
    }

    private fun switchScope(scope: SignalScope) {
        if (scope == currentScope || editorActive) return
        currentScope = scope
        updateScopeChecks()
        bindActiveScope()
    }

    private fun updateScopeChecks() {
        scopeChips.forEach { (scope, chip) -> chip.isChecked = scope == currentScope }
    }

    private fun installLiveEqResultListener() {
        childFragmentManager.setFragmentResultListener(LiveEqBottomSheet.REQUEST_KEY, this) { _, result ->
            val serialized = result.getString(LiveEqBottomSheet.RESULT_BANDS) ?: return@setFragmentResultListener
            activeBands.deserialize(serialized)
            binding.equalizerSurface.setBands(activeBands, activePreamp)
            bindAdapter(activeBands)
            save()
        }
    }

    private fun loadBands(savedInstanceState: Bundle?) {
        val peqPrefs = requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
        val bandPrefs = requireContext().getSharedPreferences(BAND_PEQ_PREFS, Context.MODE_PRIVATE)

        fullRangeBands = ParametricEqBandList().apply {
            savedInstanceState?.getBundle(STATE_BANDS)?.let(::fromBundle)
                ?: deserialize(peqPrefs.getString(getString(R.string.key_peq_bands), Constants.DEFAULT_PEQ)!!)
        }
        lowBandBands = ParametricEqBandList().apply {
            deserialize(bandPrefs.getString(KEY_LOW_BANDS, EMPTY_PEQ) ?: EMPTY_PEQ)
        }
        midBandBands = ParametricEqBandList().apply {
            deserialize(bandPrefs.getString(KEY_MID_BANDS, EMPTY_PEQ) ?: EMPTY_PEQ)
        }
        binding.preampInput.value = peqPrefs.getFloat(getString(R.string.key_peq_preamp), 0f)
    }

    private fun bindActiveScope() {
        editorBandBackup = null
        editorBandUuid = null
        editorActive = false
        binding.preampInput.isEnabled = currentScope == SignalScope.FULL_RANGE
        binding.equalizerSurface.setBands(activeBands, activePreamp)
        bindAdapter(activeBands)
        updateViewState()
    }

    private fun bindAdapter(bands: ParametricEqBandList) {
        binding.bandList.adapter = ParametricEqBandAdapter(bands).apply {
            onItemsChanged = {
                binding.equalizerSurface.setBands(it.bands, activePreamp)
                updateViewState()
                save()
            }
            onItemClicked = { band, _ ->
                editorBandBackup = band
                editorBandUuid = band.uuid
                editorActive = true
                binding.freqInput.value = band.frequency.toFloat()
                binding.gainInput.value = band.gain.toFloat()
                binding.qInput.value = band.q.toFloat()
                setFilterTypeSelection(band.filterType)
                setChannelSelection(band.channel)
                updateViewState()
            }
        }
    }

    private fun getSelectedFilterType() = when (binding.filterTypeGroup.checkedButtonId) {
        R.id.filter_low_shelf -> ParametricEqFilterType.LOW_SHELF
        R.id.filter_high_shelf -> ParametricEqFilterType.HIGH_SHELF
        else -> ParametricEqFilterType.PEAKING
    }

    private fun setFilterTypeSelection(type: ParametricEqFilterType) {
        binding.filterTypeGroup.check(when (type) {
            ParametricEqFilterType.PEAKING -> R.id.filter_peaking
            ParametricEqFilterType.LOW_SHELF -> R.id.filter_low_shelf
            ParametricEqFilterType.HIGH_SHELF -> R.id.filter_high_shelf
        })
    }

    private fun getSelectedChannel() = when (binding.channelGroup?.checkedButtonId) {
        R.id.channel_left -> ParametricEqChannel.LEFT
        R.id.channel_right -> ParametricEqChannel.RIGHT
        else -> ParametricEqChannel.LEFT_RIGHT
    }

    private fun setChannelSelection(channel: ParametricEqChannel) {
        binding.channelGroup?.check(when (channel) {
            ParametricEqChannel.LEFT_RIGHT -> R.id.channel_both
            ParametricEqChannel.LEFT -> R.id.channel_left
            ParametricEqChannel.RIGHT -> R.id.channel_right
        })
    }

    private fun updateViewState() {
        val empty = activeBands.isEmpty()
        binding.emptyView.isVisible = empty && !editorActive
        binding.bandList.isVisible = !empty && !editorActive
        binding.bandEdit.isVisible = editorActive
        binding.bandDetailContextButtons.visibility = if (editorActive) View.VISIBLE else View.INVISIBLE
        val scopeLabel = when (currentScope) {
            SignalScope.FULL_RANGE -> "Full Range"
            SignalScope.LOW_BAND -> "Low Band"
            SignalScope.MID_BAND -> "Mid Band"
        }
        binding.editCardTitle.text = if (editorActive) "$scopeLabel Band Editor" else "$scopeLabel Bands"
    }

    private fun editorCanSave() = binding.freqInput.isCurrentValueValid() &&
        binding.gainInput.isCurrentValueValid() && binding.qInput.isCurrentValueValid()

    private fun editorApply() {
        if (!editorCanSave()) return
        val uuid = editorBandUuid
        val band = ParametricEqBand(
            binding.freqInput.value.toDouble(),
            binding.gainInput.value.toDouble(),
            binding.qInput.value.toDouble(),
            getSelectedFilterType(),
            getSelectedChannel(),
            uuid ?: UUID.randomUUID(),
        )
        if (uuid == null) {
            activeBands.add(band)
            editorBandUuid = band.uuid
        } else {
            val index = activeBands.indexOfFirst { it.uuid == uuid }
            if (index >= 0) activeBands[index] = band else Timber.e("editorApply: failed to find matching band UUID")
        }
    }

    private fun editorDiscard() {
        val uuid = editorBandUuid
        if (editorBandBackup != null && uuid != null) {
            val index = activeBands.indexOfFirst { it.uuid == uuid }
            if (index >= 0) activeBands[index] = editorBandBackup
        } else if (uuid != null) {
            activeBands.removeAll { it.uuid == uuid }
        }
        editorBandBackup = null
        editorBandUuid = null
        editorActive = false
        updateViewState()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun editorSave() {
        if (!editorCanSave()) {
            requireContext().showYesNoAlert(R.string.peq_discard_changes_title, R.string.peq_discard_changes) {
                if (it) editorDiscard()
            }
            return
        }
        editorBandBackup = null
        editorBandUuid = null
        editorActive = false
        adapter.notifyDataSetChanged()
        updateViewState()
    }

    override fun onStop() {
        if (editorActive) editorDiscard()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation == ORIENTATION_LANDSCAPE) collapsePreview(false)
        super.onConfigurationChanged(newConfig)
    }

    private fun collapsePreview(collapsed: Boolean) {
        binding.equalizerSurface.isVisible = collapsed
        binding.previewTitle.text = getString(if (collapsed) R.string.peq_preview else R.string.peq_preview_collapsed)
    }

    @SuppressLint("ApplySharedPref")
    private fun save() {
        if (currentScope == SignalScope.FULL_RANGE) {
            requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE).edit()
                .putString(getString(R.string.key_peq_bands), fullRangeBands.serialize())
                .putFloat(getString(R.string.key_peq_preamp), binding.preampInput.value)
                .commit()
        } else {
            val key = if (currentScope == SignalScope.LOW_BAND) KEY_LOW_BANDS else KEY_MID_BANDS
            requireContext().getSharedPreferences(BAND_PEQ_PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, activeBands.serialize())
                .commit()
        }
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
    }

    @SuppressLint("ApplySharedPref")
    private fun savePreamp() {
        requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE).edit()
            .putFloat(getString(R.string.key_peq_preamp), binding.preampInput.value)
            .commit()
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (editorActive) editorDiscard()
        outState.putBundle(STATE_BANDS, fullRangeBands.toBundle())
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val STATE_BANDS = "bands"
        private const val BAND_PEQ_PREFS = "native_bmw_band_peq"
        private const val KEY_LOW_BANDS = "low_bands"
        private const val KEY_MID_BANDS = "mid_bands"
        private const val EMPTY_PEQ = "PEQ: "
        fun newInstance() = ParametricEqualizerFragment()
    }
}
