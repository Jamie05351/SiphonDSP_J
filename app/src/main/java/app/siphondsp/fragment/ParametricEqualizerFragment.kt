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
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
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
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.deepCopy
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import app.siphondsp.utils.extensions.ContextExtensions.showYesNoAlert
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.ParametricEqSurface
import timber.log.Timber
import java.util.UUID

class ParametricEqualizerFragment : Fragment() {
    private lateinit var binding: FragmentParametricEqBinding
    private val adapter get() = binding.bandList.adapter as ParametricEqBandAdapter
    private lateinit var peqState: BmwPeqState
    private var selectedScope = PeqScope.FULL
    private var suppressPreampCallback = false
    private val selectedBandByScope = mutableMapOf<PeqScope, UUID?>()

    private var editorBandUuid: UUID? = null
    private var editorActive = false
        set(value) {
            field = value
            binding.add.isEnabled = !value
            binding.reset.isEnabled = !value
            binding.importFile.isEnabled = !value
            binding.exportFile.isEnabled = !value
            binding.editString.isEnabled = !value
        }

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
            val imported = ParametricEqBandList()
            val result = imported.fromApoString(text)
            val candidate = peqState.deepCopy()
            replaceScopeBands(candidate, imported)
            val applied = if (selectedScope == PeqScope.FULL) {
                applyCandidate(candidate.copy(preampDb = result.preampDb.toFloat()), "import")
            } else {
                applyCandidate(candidate, "import")
            }
            if (!applied) return@registerForActivityResult
            val message = getString(R.string.peq_import_success, imported.size)
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
            val preamp = if (selectedScope == PeqScope.FULL) peqState.preampDb.toDouble() else 0.0
            val apoString = bandsForScope().toApoString(preamp)
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentParametricEqBinding.inflate(layoutInflater, container, false)
        binding.qInput.min = 0.1f

        binding.previewCard.setOnClickListener {
            if (resources.configuration.orientation != ORIENTATION_LANDSCAPE) {
                collapsePreview(!binding.equalizerSurface.isVisible)
            }
        }

        binding.reset.setOnClickListener {
            requireContext().showYesNoAlert(
                "Reset ${selectedScope.label}?",
                if (selectedScope == PeqScope.FULL) {
                    "Restore the Full Range default filters and reset its preamp to 0 dB?"
                } else {
                    "Clear every filter from ${selectedScope.label}?"
                },
            ) { confirmed ->
                if (confirmed) {
                    val candidate = peqState.deepCopy()
                    val resetBands = ParametricEqBandList()
                    if (selectedScope == PeqScope.FULL) resetBands.deserialize(Constants.DEFAULT_PEQ)
                    replaceScopeBands(candidate, resetBands)
                    applyCandidate(
                        if (selectedScope == PeqScope.FULL) candidate.copy(preampDb = 0f) else candidate,
                        "reset",
                    )
                }
            }
        }

        binding.editString.setOnClickListener {
            requireContext().showInputAlert(
                layoutInflater,
                R.string.peq_edit_as_string,
                R.string.peq_edit_hint,
                adapter.bands.toApoString(binding.preampInput.value.toDouble()),
                false,
                null,
            ) { text ->
                text?.let {
                    val parsed = ParametricEqBandList()
                    val result = parsed.fromApoString(it)
                    val candidate = peqState.deepCopy()
                    replaceScopeBands(candidate, parsed)
                    applyCandidate(
                        if (selectedScope == PeqScope.FULL) candidate.copy(preampDb = result.preampDb.toFloat()) else candidate,
                        "text-import",
                    )
                }
            }
        }

        binding.add.setOnClickListener {
            if (editorActive) return@setOnClickListener
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
        binding.exportFile.setOnClickListener { exportFileLauncher.launch(selectedScope.fileName) }

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
            if (!suppressPreampCallback && selectedScope == PeqScope.FULL) {
                val previous = peqState.preampDb
                if (!applyCandidate(peqState.deepCopy().copy(preampDb = binding.preampInput.value), "preamp")) {
                    suppressPreampCallback = true
                    binding.preampInput.value = previous
                    suppressPreampCallback = false
                }
            }
        }

        binding.bandList.layoutManager = LinearLayoutManager(requireContext())
        configureGraph()
        loadBands(savedInstanceState)
        binding.peqScopeGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val next = when (checkedIds.firstOrNull()) {
                R.id.peq_scope_low -> PeqScope.LOW
                R.id.peq_scope_mid -> PeqScope.MID
                else -> PeqScope.FULL
            }
            if (next != selectedScope) {
                if (editorActive || binding.equalizerSurface.hasActiveDraft()) {
                    requireContext().toast("Confirm or cancel the active filter edit before switching scope")
                    binding.peqScopeGroup?.check(selectedScope.chipId)
                } else {
                    selectedScope = next
                    Timber.d("Selected BMW PEQ scope=${selectedScope.label}")
                    bindScope()
                }
            }
        }
        updateViewState()
        return binding.root
    }

    private fun loadBands(savedInstanceState: Bundle?) {
        val prefs = requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
        val restored = BmwPeqState.load(requireContext())
        val legacyFull = ParametricEqBandList().apply {
            deserialize(prefs.getString(getString(R.string.key_peq_bands), Constants.DEFAULT_PEQ)!!)
        }
        peqState = if (restored == BmwPeqState.empty() && legacyFull.isNotEmpty()) {
            BmwPeqState(
                prefs.getBoolean(getString(R.string.key_peq_enable), false),
                prefs.getFloat(getString(R.string.key_peq_preamp), 0f),
                legacyFull,
                ParametricEqBandList(),
                ParametricEqBandList(),
            )
        } else restored.copy(enabled = prefs.getBoolean(getString(R.string.key_peq_enable), restored.enabled))
        suppressPreampCallback = true
        binding.preampInput.value = peqState.preampDb
        suppressPreampCallback = false
        bindScope()
    }

    private fun bandsForScope() = when (selectedScope) {
        PeqScope.FULL -> peqState.fullRangeBands
        PeqScope.LOW -> peqState.lowBandBands
        PeqScope.MID -> peqState.midBandBands
    }

    private fun bandsForScope(state: BmwPeqState) = when (selectedScope) {
        PeqScope.FULL -> state.fullRangeBands
        PeqScope.LOW -> state.lowBandBands
        PeqScope.MID -> state.midBandBands
    }

    private fun replaceScopeBands(state: BmwPeqState, replacement: ParametricEqBandList) {
        bandsForScope(state).apply {
            clear()
            addAll(replacement)
        }
    }

    private fun bindScope() {
        val bands = bandsForScope()
        val preamp = if (selectedScope == PeqScope.FULL) binding.preampInput.value.toDouble() else 0.0
        val sampleRate = (RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f).toDouble()
        binding.preampInput.isEnabled = selectedScope == PeqScope.FULL
        binding.preampInput.isVisible = selectedScope == PeqScope.FULL
        binding.equalizerSurface.setBands(
            bands,
            preamp,
            selectedBandByScope[selectedScope],
            sampleRate,
        )
        binding.previewTitle.text = when (selectedScope) {
            PeqScope.FULL -> "Full Range PEQ response"
            PeqScope.LOW -> "Low Band PEQ response · inside low crossover branch"
            PeqScope.MID -> "Mid Band PEQ response · inside mid crossover branch"
        }
        binding.bandList.adapter = ParametricEqBandAdapter(bands).apply {
            onItemClicked = { band, _ ->
                selectBandForEditing(band)
            }
            onDeleteClicked = { _, index ->
                val candidate = peqState.deepCopy()
                bandsForScope(candidate).removeAt(index)
                applyCandidate(candidate, "delete")
            }
        }
        selectedBandByScope[selectedScope]?.let { uuid ->
            bands.indexOfFirst { it.uuid == uuid }.takeIf { it >= 0 }?.let(binding.bandList::scrollToPosition)
        }
        updateViewState()
    }

    private fun configureGraph() {
        val graphPrefs = requireContext().getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
        binding.equalizerSurface.showIndividualFilters =
            graphPrefs.getBoolean(GRAPH_SHOW_OVERLAYS, true)
        binding.equalizerSurface.channelDisplay = runCatching {
            ParametricEqSurface.ChannelDisplay.valueOf(
                graphPrefs.getString(GRAPH_CHANNEL, ParametricEqSurface.ChannelDisplay.BOTH.name)!!
            )
        }.getOrDefault(ParametricEqSurface.ChannelDisplay.BOTH)

        binding.equalizerSurface.onPointSelected = { uuid ->
            bandsForScope().firstOrNull { it.uuid == uuid }?.let(::selectBandForEditing)
        }
        binding.equalizerSurface.onDragCommitted = { draggedBand ->
            val candidate = peqState.deepCopy()
            val candidateBands = bandsForScope(candidate)
            val index = candidateBands.indexOfFirst { it.uuid == draggedBand.uuid }
            if (index < 0) {
                binding.equalizerSurface.cancelDraft()
                requireContext().toast("The selected filter no longer exists; graph edit cancelled")
            } else {
                candidateBands[index] = draggedBand
                selectedBandByScope[selectedScope] = draggedBand.uuid
                if (applyCandidate(candidate, "graph-drag")) {
                    editorBandUuid = draggedBand.uuid
                    binding.freqInput.value = draggedBand.frequency.toFloat()
                    binding.gainInput.value = draggedBand.gain.toFloat()
                } else {
                    binding.equalizerSurface.cancelDraft()
                }
            }
        }
        binding.graphOptions.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menu.add(Menu.NONE, GRAPH_MENU_OVERLAYS, Menu.NONE, "Show individual filters")
                .setCheckable(true)
                .setChecked(binding.equalizerSurface.showIndividualFilters)
            popup.menu.addSubMenu("Display channel").apply {
                add(Menu.NONE, GRAPH_MENU_BOTH, Menu.NONE, "Both")
                add(Menu.NONE, GRAPH_MENU_LEFT, Menu.NONE, "Left")
                add(Menu.NONE, GRAPH_MENU_RIGHT, Menu.NONE, "Right")
                setGroupCheckable(Menu.NONE, true, true)
                val checked = when (binding.equalizerSurface.channelDisplay) {
                    ParametricEqSurface.ChannelDisplay.BOTH -> GRAPH_MENU_BOTH
                    ParametricEqSurface.ChannelDisplay.LEFT -> GRAPH_MENU_LEFT
                    ParametricEqSurface.ChannelDisplay.RIGHT -> GRAPH_MENU_RIGHT
                }
                findItem(checked)?.isChecked = true
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    GRAPH_MENU_OVERLAYS -> {
                        binding.equalizerSurface.showIndividualFilters = !item.isChecked
                        graphPrefs.edit()
                            .putBoolean(GRAPH_SHOW_OVERLAYS, binding.equalizerSurface.showIndividualFilters)
                            .apply()
                        true
                    }
                    GRAPH_MENU_BOTH, GRAPH_MENU_LEFT, GRAPH_MENU_RIGHT -> {
                        binding.equalizerSurface.channelDisplay = when (item.itemId) {
                            GRAPH_MENU_LEFT -> ParametricEqSurface.ChannelDisplay.LEFT
                            GRAPH_MENU_RIGHT -> ParametricEqSurface.ChannelDisplay.RIGHT
                            else -> ParametricEqSurface.ChannelDisplay.BOTH
                        }
                        graphPrefs.edit()
                            .putString(GRAPH_CHANNEL, binding.equalizerSurface.channelDisplay.name)
                            .apply()
                        bindScope()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun selectBandForEditing(band: ParametricEqBand) {
        editorBandUuid = band.uuid
        selectedBandByScope[selectedScope] = band.uuid
        binding.equalizerSurface.selectBand(band.uuid)
        editorActive = true
        binding.freqInput.value = band.frequency.toFloat()
        binding.gainInput.value = band.gain.toFloat()
        binding.qInput.value = band.q.toFloat()
        setFilterTypeSelection(band.filterType)
        setChannelSelection(band.channel)
        updateViewState()
    }

    private fun getSelectedFilterType() = when (binding.filterTypeGroup.checkedButtonId) {
        R.id.filter_low_shelf -> ParametricEqFilterType.LOW_SHELF
        R.id.filter_high_shelf -> ParametricEqFilterType.HIGH_SHELF
        else -> ParametricEqFilterType.PEAKING
    }

    private fun setFilterTypeSelection(type: ParametricEqFilterType) {
        binding.filterTypeGroup.check(
            when (type) {
                ParametricEqFilterType.PEAKING -> R.id.filter_peaking
                ParametricEqFilterType.LOW_SHELF -> R.id.filter_low_shelf
                ParametricEqFilterType.HIGH_SHELF -> R.id.filter_high_shelf
            }
        )
    }

    private fun getSelectedChannel() = when (binding.channelGroup?.checkedButtonId) {
        R.id.channel_left -> ParametricEqChannel.LEFT
        R.id.channel_right -> ParametricEqChannel.RIGHT
        else -> ParametricEqChannel.LEFT_RIGHT
    }

    private fun setChannelSelection(channel: ParametricEqChannel) {
        binding.channelGroup?.check(
            when (channel) {
                ParametricEqChannel.LEFT_RIGHT -> R.id.channel_both
                ParametricEqChannel.LEFT -> R.id.channel_left
                ParametricEqChannel.RIGHT -> R.id.channel_right
            }
        )
    }

    private fun updateViewState() {
        val empty = adapter.bands.isEmpty()
        binding.emptyView.isVisible = empty && !editorActive
        binding.bandList.isVisible = !empty && !editorActive
        binding.bandEdit.isVisible = editorActive
        binding.bandDetailContextButtons.visibility = if (editorActive) View.VISIBLE else View.INVISIBLE
        binding.editCardTitle.text = getString(if (editorActive) R.string.peq_band_editor else R.string.peq_band_list)
    }

    private fun editorCanSave() = binding.freqInput.isCurrentValueValid() &&
        binding.gainInput.isCurrentValueValid() && binding.qInput.isCurrentValueValid()

    private fun editorDiscard() {
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
        val uuid = editorBandUuid ?: UUID.randomUUID()
        val band = ParametricEqBand(
            binding.freqInput.value.toDouble(),
            binding.gainInput.value.toDouble(),
            binding.qInput.value.toDouble(),
            getSelectedFilterType(),
            getSelectedChannel(),
            uuid,
        )
        val candidate = peqState.deepCopy()
        val candidateBands = bandsForScope(candidate)
        val existingIndex = candidateBands.indexOfFirst { it.uuid == editorBandUuid }
        if (existingIndex >= 0) {
            candidateBands[existingIndex] = band
        } else {
            candidateBands.add(band)
        }
        if (applyCandidate(candidate, if (existingIndex >= 0) "edit" else "add")) {
            selectedBandByScope[selectedScope] = band.uuid
            editorBandUuid = null
            editorActive = false
            bindScope()
        }
    }

    override fun onStop() {
        binding.equalizerSurface.cancelDraft()
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

    private fun applyCandidate(candidate: BmwPeqState, source: String): Boolean {
        val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f
        val validation = candidate.validate(sampleRate)
        if (validation != null) {
            Timber.e("$source ${selectedScope.label} validation failed: $validation")
            requireContext().toast("$validation; previous PEQ remains active")
            return false
        }
        val result = RootlessAudioProcessorService.applyNativeBmwPeq(candidate)
        Timber.d(
            "$source scope=${selectedScope.label} full=${candidate.fullRangeBands.size} " +
                "low=${candidate.lowBandBands.size} mid=${candidate.midBandBands.size} result=$result"
        )
        if (!result) {
            requireContext().toast("BMW PEQ configuration rejected; previous state remains active")
            return false
        }
        peqState = candidate
        suppressPreampCallback = true
        binding.preampInput.value = peqState.preampDb
        suppressPreampCallback = false
        bindScope()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (editorActive) editorDiscard()
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val STATE_BANDS = "bands"
        private const val GRAPH_PREFS = "peq_graph_display"
        private const val GRAPH_SHOW_OVERLAYS = "show_individual_filters"
        private const val GRAPH_CHANNEL = "channel_display"
        private const val GRAPH_MENU_OVERLAYS = 1
        private const val GRAPH_MENU_BOTH = 2
        private const val GRAPH_MENU_LEFT = 3
        private const val GRAPH_MENU_RIGHT = 4
        fun newInstance() = ParametricEqualizerFragment()
    }

    private enum class PeqScope(val label: String, val fileName: String, val chipId: Int) {
        FULL("Full Range", "full_range_parametric_eq.txt", R.id.peq_scope_full),
        LOW("Low Band", "low_band_parametric_eq.txt", R.id.peq_scope_low),
        MID("Mid Band", "mid_band_parametric_eq.txt", R.id.peq_scope_mid),
    }
}
