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
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.materialswitch.MaterialSwitch
import app.siphondsp.R
import app.siphondsp.BuildConfig
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.adapter.ParametricEqBandAdapter
import app.siphondsp.databinding.FragmentParametricEqBinding
import app.siphondsp.dsp.BmwPeqBank
import app.siphondsp.dsp.BmwSignalChain
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.BmwPeqPreset
import app.siphondsp.model.PeqStateHistory
import app.siphondsp.model.PeqDiagnosticReport
import app.siphondsp.model.PrivatePeqBackup
import app.siphondsp.model.deepCopy
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import app.siphondsp.utils.extensions.ContextExtensions.showYesNoAlert
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination
import app.siphondsp.view.ParametricEqSurface
import app.siphondsp.view.StaticPagerAdapter
import timber.log.Timber
import java.util.UUID
import java.io.InputStream

class ParametricEqualizerFragment : Fragment() {
    private lateinit var binding: FragmentParametricEqBinding
    private val adapter get() = binding.bandList.adapter as ParametricEqBandAdapter
    private lateinit var peqState: BmwPeqState
    private lateinit var nativeDspValues: FloatArray
    private var selectedScope = PeqScope.FULL
    private val selectedBandByScope = mutableMapOf<PeqScope, UUID?>()
    private val history = PeqStateHistory(HISTORY_LIMIT)
    private var pendingDiagnosticReport: String? = null
    private var peqDisplayMode = PeqDisplayMode.GRAPH

    private var editorBandUuid: UUID? = null
    private var editorActive = false
        set(value) {
            field = value
            refreshActionChips()
        }

    private fun refreshActionChips() {
        if (isAdded) activity?.invalidateMenu() // keeps the toolbar enable switch synced, see onPrepareMenu()
        binding.chipUndo?.isEnabled = !editorActive && history.canUndo
        binding.chipRedo?.isEnabled = !editorActive && history.canRedo
        binding.chipReset?.isEnabled = !editorActive
        binding.chipEditString?.isEnabled = !editorActive
        binding.chipImportFile?.isEnabled = !editorActive
        binding.chipExportFile?.isEnabled = !editorActive
        binding.chipPresetImport?.isEnabled = !editorActive
        binding.chipPresetExport?.isEnabled = !editorActive
        binding.chipBackupImport?.isEnabled = !editorActive
        binding.chipBackupExport?.isEnabled = !editorActive
        binding.chipFilterTools?.isEnabled = !editorActive
    }

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.use(::readImportText) ?: return@registerForActivityResult
            val imported = ParametricEqBandList()
            val result = imported.fromApoString(text)
            val detail = if (result.skippedFilters > 0) {
                "${result.skippedFilters} malformed or unsupported lines will be skipped."
            } else {
                "All ${imported.size} parsed filters are supported."
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Import into ${selectedScope.label}")
                .setMessage("$detail Choose how to apply the previewed filters.")
                .setPositiveButton("Replace") { _, _ ->
                    applyScopeImport(imported, result.preampDb.toFloat(), append = false, result.skippedFilters)
                }
                .setNeutralButton("Append") { _, _ ->
                    applyScopeImport(imported, result.preampDb.toFloat(), append = true, result.skippedFilters)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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

    private fun applyScopeImport(
        imported: ParametricEqBandList,
        importedPreamp: Float,
        append: Boolean,
        skippedFilters: Int,
    ) {
        val candidate = peqState.deepCopy()
        val destination = bandsForScope(candidate)
        if (!append) destination.clear()
        if (destination.size + imported.size > BmwPeqState.MAX_BANDS) {
            requireContext().toast(
                "${selectedScope.label} would exceed the maximum ${BmwPeqState.MAX_BANDS} filters"
            )
            return
        }
        imported.forEach { destination.add(it.copyWithUuid(UUID.randomUUID())) }
        val completeCandidate = if (selectedScope == PeqScope.FULL && !append) {
            candidate.copy(preampDb = importedPreamp)
        } else {
            candidate
        }
        if (applyCandidate(completeCandidate, if (append) "import-append" else "import-replace")) {
            val message = getString(R.string.peq_import_success, imported.size)
            requireContext().toast(
                if (skippedFilters > 0) "$message ($skippedFilters malformed or unsupported lines skipped)"
                else message
            )
        }
    }

    private fun readImportText(input: InputStream): String {
        val reader = input.bufferedReader()
        val output = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (output.length + count > MAX_IMPORT_CHARS) {
                throw IllegalArgumentException("The import file is larger than the 1 MB safety limit")
            }
            output.append(buffer, 0, count)
        }
        return output.toString()
    }

    private val presetExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val preset = BmwPeqPreset.fromState(
                    peqState,
                    name = "Three-bank PEQ",
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(BmwPeqPreset.encode(preset))
                }
                requireContext().toast("Complete three-bank PEQ preset exported")
            } catch (error: Exception) {
                Timber.e(error, "Failed to export complete PEQ preset")
                requireContext().toast("Preset export failed")
            }
        }

    private val presetImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.use(::readImportText)
                    ?: throw IllegalArgumentException("The preset file is empty")
                val preset = BmwPeqPreset.decode(text)
                val candidate = preset.toState()
                val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f
                candidate.validate(sampleRate)?.let { throw IllegalArgumentException(it) }
                requireContext().showYesNoAlert(
                    "Load complete PEQ preset?",
                    "Replace Full Range, Low Band, Mid Band and preamp with " +
                        "\"${preset.name ?: "Imported preset"}\"? This can be undone.",
                ) { confirmed ->
                    if (confirmed) applyCandidate(candidate, "preset-load")
                }
            } catch (error: Exception) {
                Timber.e(error, "Failed to import complete PEQ preset")
                requireContext().toast(error.message ?: "Preset import failed; previous PEQ was kept")
            }
        }

    private val diagnosticExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            val report = pendingDiagnosticReport ?: return@registerForActivityResult
            try {
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(report)
                }
                requireContext().toast("Sanitised PEQ diagnostic report exported")
            } catch (error: Exception) {
                Timber.e(error, "Failed to export PEQ diagnostic report")
                requireContext().toast("Diagnostic report export failed")
            } finally {
                pendingDiagnosticReport = null
            }
        }

    private val privateBackupExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val graphPrefs =
                    requireContext().getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
                val backup = PrivatePeqBackup(
                    createdAtEpochMs = System.currentTimeMillis(),
                    state = BmwPeqPreset.fromState(peqState, name = "Jamie private PEQ backup"),
                    graphDisplay = PrivatePeqBackup.GraphDisplay(
                        graphPrefs.getBoolean(GRAPH_SHOW_OVERLAYS, true),
                        graphPrefs.getString(
                            GRAPH_CHANNEL,
                            ParametricEqSurface.ChannelDisplay.BOTH.name,
                        ) ?: ParametricEqSurface.ChannelDisplay.BOTH.name,
                    ),
                    // Full BMW DSP state (Gains & Delay, Compressor, Crossovers & Tilt), not
                    // just PEQ bands -- see PrivatePeqBackup.nativeDspValues.
                    nativeDspValues = NativeBmwDspValues.load(requireContext()).toList(),
                )
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(PrivatePeqBackup.encode(backup))
                }
                Timber.i("Private BMW DSP backup exported format=${PrivatePeqBackup.CURRENT_VERSION}")
                requireContext().toast("Complete private BMW DSP backup exported")
            } catch (error: Exception) {
                Timber.e(error, "Private PEQ backup export failed")
                requireContext().toast("Backup export failed")
            }
        }

    private val privateBackupImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.use(::readImportText)
                    ?: throw IllegalArgumentException("The backup file is empty")
                val backup = PrivatePeqBackup.decode(text)
                val candidate = backup.validatedState()
                val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f
                candidate.validate(sampleRate)?.let { throw IllegalArgumentException(it) }
                val restoresFullState = backup.nativeDspValues != null
                AlertDialog.Builder(requireContext())
                    .setTitle("Restore complete private BMW DSP backup?")
                    .setMessage(
                        "Full Range ${candidate.fullRangeBands.size}, " +
                            "Low ${candidate.lowBandBands.size}, Mid ${candidate.midBandBands.size}, " +
                            "preamp ${candidate.preampDb} dB" +
                            (if (restoresFullState) ", plus Gains & Delay, Compressor, and Crossovers & Tilt" else "") +
                            ". This replaces all PEQ banks" +
                            (if (restoresFullState) " and the rest of the BMW DSP setup" else "") +
                            " only after the complete state validates and applies successfully." +
                            (if (!restoresFullState) " (This is an older backup file that only contains PEQ bands.)" else "")
                    )
                    .setPositiveButton("Restore") { _, _ ->
                        if (applyCandidate(candidate, "private-backup-restore")) {
                            backup.nativeDspValues?.let { values ->
                                val restored = values.toFloatArray()
                                NativeBmwDspValues.save(requireContext(), restored)
                                NativeBmwDspValues.broadcast(requireContext(), restored)
                            }
                            val graphPrefs = requireContext()
                                .getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
                            graphPrefs.edit()
                                .putBoolean(
                                    GRAPH_SHOW_OVERLAYS,
                                    backup.graphDisplay.showIndividualFilters,
                                )
                                .putString(GRAPH_CHANNEL, backup.graphDisplay.channelDisplay)
                                .apply()
                            binding.equalizerSurface.showIndividualFilters =
                                backup.graphDisplay.showIndividualFilters
                            binding.equalizerSurface.channelDisplay =
                                ParametricEqSurface.ChannelDisplay.valueOf(
                                    backup.graphDisplay.channelDisplay
                                )
                            BmwPeqState.recordBackupRestoreResult(
                                requireContext(), "success-v${backup.version}"
                            )
                            Timber.i("Private PEQ backup restore succeeded version=${backup.version}")
                        } else {
                            BmwPeqState.recordBackupRestoreResult(
                                requireContext(), "native-apply-rejected"
                            )
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (error: Exception) {
                BmwPeqState.recordBackupRestoreResult(
                    requireContext(), "rejected: ${error.message ?: "invalid backup"}"
                )
                Timber.e(error, "Private PEQ backup import rejected")
                requireContext().toast(error.message ?: "Invalid backup; previous PEQ was kept")
            }
        }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_PRESET_LOADED -> {
                    activity?.finish()
                    startActivity(Intent(requireContext(), ParametricEqualizerActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })
                }
                Constants.ACTION_NATIVE_BMW_DSP_UPDATED -> {
                    val values = intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES) ?: return
                    if (values.size != BmwSignalChain.VALUE_COUNT) return
                    nativeDspValues = values.copyOf()
                    // Don't clobber an in-flight drag on this surface; setSystemValues() will
                    // run again once the drag resolves via bindScope()/cancelDraft().
                    if (!binding.equalizerSurface.hasActiveDraft()) {
                        binding.equalizerSurface.setSystemValues(nativeDspValues)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requireContext().registerLocalReceiver(
            broadcastReceiver,
            IntentFilter(Constants.ACTION_PRESET_LOADED).apply {
                addAction(Constants.ACTION_NATIVE_BMW_DSP_UPDATED)
            },
        )
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
        setUpCardsPager()
        binding.qInput.min = 0.1f
        binding.equalizerSurface.showSpectrum = true

        binding.previewCard.setOnClickListener {
            if (resources.configuration.orientation != ORIENTATION_LANDSCAPE) {
                collapsePreview(!binding.equalizerSurface.isVisible)
            }
        }

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
        binding.addBand?.setOnClickListener { performAdd() }
        // Notch has no gain parameter (the graph shows a full null, depth is Q-only) -- hide
        // the field rather than leave a control visible that silently does nothing.
        binding.filterTypeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) binding.gainInput.isVisible = checkedId != R.id.filter_notch
        }

        binding.bandList.layoutManager = LinearLayoutManager(requireContext())
        configureGraph()
        configureProductionTools()
        configureActionChips()
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
        configurePager()
        configureCrossNav()
        updateViewState()
        return binding.root
    }

    /** The other 3 DSP screens, forming the left sidebar (landscape only -- see DspCrossNavBar).
     *  Blocked while there's an unsaved filter edit or an in-flight graph drag, same guard as
     *  switching Full/Low/Mid scope. */
    private fun configureCrossNav() {
        val container = binding.peqCrossNav ?: return
        DspCrossNavBar.populate(requireActivity(), container, DspDestination.PARAMETRIC_EQ) {
            if (editorActive || binding.equalizerSurface.hasActiveDraft()) {
                requireContext().toast("Confirm or cancel the active filter edit before switching screens")
                false
            } else {
                true
            }
        }
    }

    /** Detaches edit_card/preview_card from the plain ConstraintLayout position the landscape
     *  layout declares them at (kept there purely so ViewBinding still generates their field
     *  references, and so portrait -- which has no cards_pager -- can use them as-is) and hands
     *  them to cards_pager as swipeable pages: 0 = preview_card/GRAPH, 1 = edit_card/LIST.
     *  Portrait has no cards_pager -- no-op there. */
    private fun setUpCardsPager() {
        val pager = binding.cardsPager ?: return
        val pages = listOf(binding.previewCard, binding.editCard)
        pages.forEach { page ->
            (page.parent as? ViewGroup)?.removeView(page)
            page.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            page.visibility = View.VISIBLE
        }
        pager.offscreenPageLimit = 1
        pager.adapter = StaticPagerAdapter(pages)
    }

    /** Left-edge Graph/List paging (landscape only): restores which page cards_pager should
     *  open on, and keeps [peqDisplayMode] (and its persisted preference) in sync as the user
     *  swipes -- see [setDisplayMode]. The old tap-to-switch mode_tab_strip is gone now that
     *  swiping does the same job; cards_pager is the only way to switch. Portrait has no
     *  cards_pager -- no-op there. */
    private fun configurePager() {
        val graphPrefs = requireContext().getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
        peqDisplayMode = runCatching {
            PeqDisplayMode.valueOf(graphPrefs.getString(GRAPH_DISPLAY_MODE, PeqDisplayMode.GRAPH.name)!!)
        }.getOrDefault(PeqDisplayMode.GRAPH)

        binding.cardsPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setDisplayMode(if (position == 1) PeqDisplayMode.LIST else PeqDisplayMode.GRAPH, smoothScroll = false)
            }
        })
        applyDisplayMode(smoothScroll = false)
    }

    private fun setDisplayMode(mode: PeqDisplayMode, smoothScroll: Boolean) {
        if (peqDisplayMode == mode) return
        peqDisplayMode = mode
        requireContext().getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
            .edit().putString(GRAPH_DISPLAY_MODE, mode.name).apply()
        applyDisplayMode(smoothScroll)
    }

    /** GRAPH: page 0, the live visualizer. LIST: page 1, the band editor -- the two are
     *  mutually exclusive pages of cards_pager, List mode dedicated entirely to the filter list,
     *  not sharing space with the visualizer. Portrait has none of these views -- no-op there. */
    private fun applyDisplayMode(smoothScroll: Boolean) {
        val pager = binding.cardsPager ?: return
        val targetPosition = if (peqDisplayMode == PeqDisplayMode.LIST) 1 else 0
        if (pager.currentItem != targetPosition) pager.setCurrentItem(targetPosition, smoothScroll)
    }

    private fun loadBands(savedInstanceState: Bundle?) {
        peqState = BmwPeqState.load(requireContext())
        nativeDspValues = NativeBmwDspValues.load(requireContext())
        history.reset(peqState)
        updateHistoryControls()
        bindScope()
    }

    /**
     * Tilt is not part of BmwPeqState -- it lives in the separate 35-float native BMW DSP
     * config array -- so this is deliberately a sibling of applyCandidate(), not routed
     * through it or PeqStateHistory: folding tilt into PEQ undo/redo would make PEQ undo
     * silently revert tilt changes too. Same transaction discipline as applyCandidate()
     * though -- the surface only calls this once, on ACTION_UP of an actual drag.
     */
    private fun applyTiltCandidate(frequencyHz: Float, amountDb: Float): Boolean {
        return try {
            val clampedFreq = frequencyHz.coerceIn(200f, 2000f)
            val clampedAmount = amountDb.coerceIn(-6f, 6f)
            val applied = NativeBmwDspValues.update(requireContext()) { values ->
                values[NativeBmwDspValues.INDEX_TILT_FREQ] = clampedFreq
                values[NativeBmwDspValues.INDEX_TILT_AMOUNT] = clampedAmount
            }
            nativeDspValues = applied
            binding.equalizerSurface.setSystemValues(applied)
            Timber.d("tilt-drag committed frequency=$clampedFreq amount=$clampedAmount")
            true
        } catch (error: Exception) {
            Timber.e(error, "tilt-drag commit failed")
            requireContext().toast("Tilt change could not be saved; previous value kept")
            false
        }
    }

    private fun bandsForScope() = when (selectedScope) {
        PeqScope.FULL -> peqState.fullRangeBands
        PeqScope.LOW -> peqState.lowBandBands
        PeqScope.MID -> peqState.midBandBands
    }

    private fun bandsForScope(state: BmwPeqState, scope: PeqScope = selectedScope) = when (scope) {
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
        val sampleRate = (RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f).toDouble()
        binding.equalizerSurface.setSystemState(
            nativeDspValues,
            peqState,
            selectedScope.bank,
            selectedBandByScope[selectedScope],
            sampleRate,
        )
        binding.previewTitle.text = when (selectedScope) {
            PeqScope.FULL -> "Input Correction PEQ response"
            PeqScope.LOW -> "Low Band PEQ response · inside low crossover branch"
            PeqScope.MID -> "Mid Band PEQ response · inside mid crossover branch"
        }
        binding.bandList.adapter = ParametricEqBandAdapter(bands).apply {
            selectedUuid = selectedBandByScope[selectedScope]
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
        // Tilt now has its own dedicated page (CrossoverTiltFragment) for precise numeric
        // editing, but the drag-to-adjust handles stay directly on this graph too.
        binding.equalizerSurface.showTiltHandles = true
        binding.equalizerSurface.showGainMeters = true
        val graphPrefs = requireContext().getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
        binding.equalizerSurface.showIndividualFilters =
            graphPrefs.getBoolean(GRAPH_SHOW_OVERLAYS, true)
        binding.equalizerSurface.channelDisplay = runCatching {
            ParametricEqSurface.ChannelDisplay.valueOf(
                graphPrefs.getString(GRAPH_CHANNEL, ParametricEqSurface.ChannelDisplay.BOTH.name)!!
            )
        }.getOrDefault(ParametricEqSurface.ChannelDisplay.BOTH)
        binding.equalizerSurface.displayMode = runCatching {
            ParametricEqSurface.DisplayMode.valueOf(
                graphPrefs.getString(GRAPH_RESPONSE_MODE, ParametricEqSurface.DisplayMode.MAGNITUDE.name)!!
            )
        }.getOrDefault(ParametricEqSurface.DisplayMode.MAGNITUDE)

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
        binding.equalizerSurface.onTiltDragCommitted = { frequencyHz, amountDb ->
            if (!applyTiltCandidate(frequencyHz, amountDb)) {
                binding.equalizerSurface.cancelDraft()
            }
        }
    }

    private fun showGraphOptionsPopup(anchor: View) {
        val graphPrefs = requireContext().getSharedPreferences(GRAPH_PREFS, Context.MODE_PRIVATE)
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
        popup.menu.addSubMenu("Response mode").apply {
            add(Menu.NONE, GRAPH_MENU_MODE_MAGNITUDE, Menu.NONE, "Magnitude")
            add(Menu.NONE, GRAPH_MENU_MODE_PHASE, Menu.NONE, "Phase")
            add(Menu.NONE, GRAPH_MENU_MODE_MAGNITUDE_PHASE, Menu.NONE, "Magnitude + Phase")
            add(Menu.NONE, GRAPH_MENU_MODE_GROUP_DELAY, Menu.NONE, "Group Delay")
            setGroupCheckable(Menu.NONE, true, true)
            val checked = when (binding.equalizerSurface.displayMode) {
                ParametricEqSurface.DisplayMode.MAGNITUDE -> GRAPH_MENU_MODE_MAGNITUDE
                ParametricEqSurface.DisplayMode.PHASE -> GRAPH_MENU_MODE_PHASE
                ParametricEqSurface.DisplayMode.MAGNITUDE_PHASE -> GRAPH_MENU_MODE_MAGNITUDE_PHASE
                ParametricEqSurface.DisplayMode.GROUP_DELAY -> GRAPH_MENU_MODE_GROUP_DELAY
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
                GRAPH_MENU_MODE_MAGNITUDE, GRAPH_MENU_MODE_PHASE, GRAPH_MENU_MODE_MAGNITUDE_PHASE, GRAPH_MENU_MODE_GROUP_DELAY -> {
                    binding.equalizerSurface.displayMode = when (item.itemId) {
                        GRAPH_MENU_MODE_PHASE -> ParametricEqSurface.DisplayMode.PHASE
                        GRAPH_MENU_MODE_MAGNITUDE_PHASE -> ParametricEqSurface.DisplayMode.MAGNITUDE_PHASE
                        GRAPH_MENU_MODE_GROUP_DELAY -> ParametricEqSurface.DisplayMode.GROUP_DELAY
                        else -> ParametricEqSurface.DisplayMode.MAGNITUDE
                    }
                    graphPrefs.edit()
                        .putString(GRAPH_RESPONSE_MODE, binding.equalizerSurface.displayMode.name)
                        .apply()
                    true
                }
                else -> false
            }
        }
        popup.show()
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

    /** Sets up the toolbar's enable switch -- the only remaining toolbar menu item, everything
     *  else lives in the horizontally-scrolling action-chip row (see configureActionChips()). */
    private fun configureProductionTools() {
        val menuHost = requireActivity() as MenuHost
        var bindingEnableSwitch = false
        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_parametric_eq, menu)
                    val enableSwitch = menu.findItem(R.id.menu_peq_enable)?.actionView as? MaterialSwitch
                    enableSwitch?.setOnCheckedChangeListener { _, checked ->
                        if (bindingEnableSwitch) return@setOnCheckedChangeListener
                        val current = BmwPeqState.load(requireContext())
                        val candidate = current.copy(enabled = checked)
                        val applied = RootlessAudioProcessorService.applyNativeBmwPeq(candidate)
                        val saved = applied || candidate.persist(requireContext())
                        Timber.i(
                            "PEQ enabled change (toolbar) enabled=${candidate.enabled} " +
                                "nativeApply=$applied saved=$saved"
                        )
                    }
                }

                override fun onPrepareMenu(menu: Menu) {
                    val enableSwitch = menu.findItem(R.id.menu_peq_enable)?.actionView as? MaterialSwitch
                    val currentlyEnabled = BmwPeqState.load(requireContext()).enabled
                    if (enableSwitch != null && enableSwitch.isChecked != currentlyEnabled) {
                        bindingEnableSwitch = true
                        enableSwitch.isChecked = currentlyEnabled
                        bindingEnableSwitch = false
                    }
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED,
        )
    }

    /** Restores the pre-redesign horizontally-scrolling action-chip row (same row as the
     *  Full/Low/Mid scope chips) in place of the toolbar's 3-dot overflow menu -- same
     *  underlying functions as before, just triggered from chips instead of menu items. */
    private fun configureActionChips() {
        binding.chipUndo?.setOnClickListener { performUndo() }
        binding.chipRedo?.setOnClickListener { performRedo() }
        binding.chipReset?.setOnClickListener { performReset() }
        binding.chipEditString?.setOnClickListener { performEditAsString() }
        binding.chipImportFile?.setOnClickListener { performImport() }
        binding.chipExportFile?.setOnClickListener { performExport() }
        binding.chipPresetImport?.setOnClickListener { performPresetImport() }
        binding.chipPresetExport?.setOnClickListener { performPresetExport() }
        binding.chipBackupImport?.setOnClickListener { performBackupImport() }
        binding.chipBackupExport?.setOnClickListener { performBackupExport() }
        binding.chipFilterTools?.setOnClickListener { showFilterTools(it) }
        binding.chipDiagnostics?.setOnClickListener { showDiagnosticReport() }
        binding.chipGraphOptions?.setOnClickListener { showGraphOptionsPopup(it) }
        refreshActionChips()
    }

    private fun performReset() {
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

    private fun performEditAsString() {
        requireContext().showInputAlert(
            layoutInflater,
            R.string.peq_edit_as_string,
            R.string.peq_edit_hint,
            adapter.bands.toApoString(peqState.preampDb.toDouble()),
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

    private fun performAdd() {
        if (editorActive) return
        editorBandUuid = null
        editorActive = true
        binding.freqInput.value = 1000f
        binding.gainInput.value = 0f
        binding.qInput.value = 1.41f
        setFilterTypeSelection(ParametricEqFilterType.PEAKING)
        setChannelSelection(ParametricEqChannel.LEFT_RIGHT)
        updateViewState()
    }

    private fun performImport() = importFileLauncher.launch(arrayOf("text/plain", "text/*"))
    private fun performExport() = exportFileLauncher.launch(selectedScope.fileName)

    private fun performUndo() {
        val candidate = history.peekUndo() ?: return
        if (applyCandidate(candidate, "undo", recordHistory = false)) {
            history.confirmUndo()
            updateHistoryControls()
        }
    }

    private fun performRedo() {
        val candidate = history.peekRedo() ?: return
        if (applyCandidate(candidate, "redo", recordHistory = false)) {
            history.confirmRedo()
            updateHistoryControls()
        }
    }

    private fun performPresetExport() = presetExportLauncher.launch("three_bank_peq.json")
    private fun performPresetImport() =
        presetImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
    private fun performBackupExport() =
        privateBackupExportLauncher.launch("SiphonDSP-private-peq-backup.json")
    private fun performBackupImport() =
        privateBackupImportLauncher.launch(arrayOf("application/json", "text/plain"))

    private fun showDiagnosticReport() {
        val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate()
        val report = PeqDiagnosticReport.create(
            requireContext(),
            peqState,
            sampleRate,
            serviceActive = sampleRate != null,
            nativeHandleReady = RootlessAudioProcessorService.nativeBmwPeqHandleReady(),
        )
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("PEQ diagnostic report")
            .setMessage(report)
            .setPositiveButton("Export") { _, _ ->
                pendingDiagnosticReport = report
                diagnosticExportLauncher.launch("siphondsp-peq-diagnostic.txt")
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (BuildConfig.DEBUG) {
            dialog.setNeutralButton("Restore last known good") { _, _ ->
                val candidate = BmwPeqState.loadLastKnownGood(requireContext())
                if (candidate == null) {
                    requireContext().toast("No last-known-good PEQ state is available")
                } else {
                    applyCandidate(candidate, "developer-lkg-restore")
                }
            }
        }
        dialog.show()
    }

    private fun updateHistoryControls() {
        refreshActionChips()
    }

    private fun showFilterTools(anchor: View) {
        val selectedId = selectedBandByScope[selectedScope]
        val selectedBands = bandsForScope()
        val selectedIndex = selectedBands.indexOfFirst { it.uuid == selectedId }
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(Menu.NONE, FILTER_DUPLICATE, Menu.NONE, "Duplicate selected filter")
            .isEnabled = selectedIndex >= 0
        popup.menu.add(Menu.NONE, FILTER_MOVE_UP, Menu.NONE, "Move up").isEnabled = selectedIndex > 0
        popup.menu.add(Menu.NONE, FILTER_MOVE_DOWN, Menu.NONE, "Move down")
            .isEnabled = selectedIndex >= 0 && selectedIndex < selectedBands.lastIndex
        PeqScope.entries.filter { it != selectedScope }.forEach { target ->
            popup.menu.add(
                Menu.NONE,
                FILTER_COPY_SCOPE_BASE + target.ordinal,
                Menu.NONE,
                "Copy selected to ${target.label}",
            ).isEnabled = selectedIndex >= 0
            popup.menu.add(
                Menu.NONE,
                FILTER_COPY_ALL_SCOPE_BASE + target.ordinal,
                Menu.NONE,
                "Copy all to ${target.label}…",
            )
        }
        popup.menu.add(Menu.NONE, FILTER_COPY_LEFT_TO_RIGHT, Menu.NONE, "Copy all Left filters to Right")
        popup.menu.add(Menu.NONE, FILTER_COPY_RIGHT_TO_LEFT, Menu.NONE, "Copy all Right filters to Left")
        popup.menu.add(Menu.NONE, FILTER_SPLIT_BOTH, Menu.NONE, "Split Both filters into Left + Right")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                FILTER_DUPLICATE -> duplicateSelectedFilter(selectedIndex)
                FILTER_MOVE_UP -> moveSelectedFilter(selectedIndex, selectedIndex - 1)
                FILTER_MOVE_DOWN -> moveSelectedFilter(selectedIndex, selectedIndex + 1)
                in FILTER_COPY_SCOPE_BASE until FILTER_COPY_SCOPE_BASE + PeqScope.entries.size -> {
                    val target = PeqScope.entries[item.itemId - FILTER_COPY_SCOPE_BASE]
                    copySelectedFilter(selectedIndex, target)
                }
                in FILTER_COPY_ALL_SCOPE_BASE until FILTER_COPY_ALL_SCOPE_BASE + PeqScope.entries.size -> {
                    val target = PeqScope.entries[item.itemId - FILTER_COPY_ALL_SCOPE_BASE]
                    confirmCopyWholeScope(target)
                }
                FILTER_COPY_LEFT_TO_RIGHT ->
                    copyChannelFilters(ParametricEqChannel.LEFT, ParametricEqChannel.RIGHT, replaceBoth = false)
                FILTER_COPY_RIGHT_TO_LEFT ->
                    copyChannelFilters(ParametricEqChannel.RIGHT, ParametricEqChannel.LEFT, replaceBoth = false)
                FILTER_SPLIT_BOTH ->
                    copyChannelFilters(ParametricEqChannel.LEFT_RIGHT, ParametricEqChannel.LEFT, replaceBoth = true)
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmCopyWholeScope(target: PeqScope): Boolean {
        AlertDialog.Builder(requireContext())
            .setTitle("Copy ${selectedScope.label} to ${target.label}")
            .setMessage("Append keeps destination filters. Replace removes them first. The operation commits once and can be undone.")
            .setPositiveButton("Replace") { _, _ -> copyWholeScope(target, append = false) }
            .setNeutralButton("Append") { _, _ -> copyWholeScope(target, append = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    private fun copyWholeScope(target: PeqScope, append: Boolean) {
        val candidate = peqState.deepCopy()
        val source = bandsForScope(candidate).toList()
        val destination = bandsForScope(candidate, target)
        val resultingSize = (if (append) destination.size else 0) + source.size
        if (resultingSize > BmwPeqState.MAX_BANDS) {
            requireContext().toast(
                "${target.label} would exceed the maximum ${BmwPeqState.MAX_BANDS} filters"
            )
            return
        }
        if (!append) destination.clear()
        source.forEach { destination.add(it.copyWithUuid(UUID.randomUUID())) }
        applyCandidate(candidate, if (append) "copy-scope-append" else "copy-scope-replace")
    }

    private fun duplicateSelectedFilter(index: Int): Boolean {
        val candidate = peqState.deepCopy()
        val bands = bandsForScope(candidate)
        if (bands.size >= BmwPeqState.MAX_BANDS) {
            requireContext().toast("${selectedScope.label} already has the maximum ${BmwPeqState.MAX_BANDS} filters")
            return true
        }
        val source = bands[index]
        val duplicate = source.copyWithUuid(UUID.randomUUID())
        bands.add(index + 1, duplicate)
        selectedBandByScope[selectedScope] = duplicate.uuid
        applyCandidate(candidate, "duplicate")
        return true
    }

    private fun copySelectedFilter(index: Int, target: PeqScope): Boolean {
        val candidate = peqState.deepCopy()
        val destination = bandsForScope(candidate, target)
        if (destination.size >= BmwPeqState.MAX_BANDS) {
            requireContext().toast("${target.label} already has the maximum ${BmwPeqState.MAX_BANDS} filters")
            return true
        }
        val copied = bandsForScope(candidate)[index].copyWithUuid(UUID.randomUUID())
        destination.add(copied)
        if (applyCandidate(candidate, "copy-filter")) {
            selectedBandByScope[target] = copied.uuid
        }
        return true
    }

    private fun moveSelectedFilter(from: Int, to: Int): Boolean {
        val candidate = peqState.deepCopy()
        val bands = bandsForScope(candidate)
        if (from !in bands.indices || to !in bands.indices) return true
        val band = bands.removeAt(from)
        bands.add(to, band)
        selectedBandByScope[selectedScope] = band.uuid
        applyCandidate(candidate, "reorder")
        return true
    }

    private fun copyChannelFilters(
        sourceChannel: ParametricEqChannel,
        destinationChannel: ParametricEqChannel,
        replaceBoth: Boolean,
    ): Boolean {
        val candidate = peqState.deepCopy()
        val bands = bandsForScope(candidate)
        val source = bands.filter { it.channel == sourceChannel }
        val resultingSize = bands.size + source.size
        if (resultingSize > BmwPeqState.MAX_BANDS) {
            requireContext().toast(
                "${selectedScope.label} would exceed the maximum ${BmwPeqState.MAX_BANDS} filters"
            )
            return true
        }
        if (replaceBoth) {
            val sourceIds = source.mapTo(mutableSetOf()) { it.uuid }
            bands.removeAll { it.uuid in sourceIds }
        }
        source.forEach { band ->
            bands.add(
                ParametricEqBand(
                    band.frequency,
                    band.gain,
                    band.q,
                    band.filterType,
                    destinationChannel,
                    UUID.randomUUID(),
                )
            )
            if (replaceBoth) {
                bands.add(
                    ParametricEqBand(
                        band.frequency,
                        band.gain,
                        band.q,
                        band.filterType,
                        ParametricEqChannel.RIGHT,
                        UUID.randomUUID(),
                    )
                )
            }
        }
        if (source.isEmpty()) {
            requireContext().toast("No ${sourceChannel.displayLabel} filters to copy")
            return true
        }
        applyCandidate(candidate, "copy-channel")
        return true
    }

    private fun ParametricEqBand.copyWithUuid(uuid: UUID) = ParametricEqBand(
        frequency, gain, q, filterType, channel, uuid,
    )

    private fun getSelectedFilterType() = when (binding.filterTypeGroup.checkedButtonId) {
        R.id.filter_low_shelf -> ParametricEqFilterType.LOW_SHELF
        R.id.filter_high_shelf -> ParametricEqFilterType.HIGH_SHELF
        R.id.filter_notch -> ParametricEqFilterType.NOTCH
        else -> ParametricEqFilterType.PEAKING
    }

    private fun setFilterTypeSelection(type: ParametricEqFilterType) {
        binding.filterTypeGroup.check(
            when (type) {
                ParametricEqFilterType.PEAKING -> R.id.filter_peaking
                ParametricEqFilterType.LOW_SHELF -> R.id.filter_low_shelf
                ParametricEqFilterType.HIGH_SHELF -> R.id.filter_high_shelf
                ParametricEqFilterType.NOTCH -> R.id.filter_notch
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
        // List pane stays visible regardless of edit state (see the split edit_card layout);
        // only the editor pane's own visibility is gated by editorActive.
        binding.emptyView.isVisible = empty
        binding.bandList.isVisible = !empty
        binding.bandListHeader?.root?.isVisible = !empty
        binding.bandEdit.isVisible = editorActive
        binding.bandDetailContextButtons.visibility = if (editorActive) View.VISIBLE else View.GONE
        binding.addBand?.visibility = if (editorActive) View.GONE else View.VISIBLE
        binding.editCardTitle.text = getString(if (editorActive) R.string.peq_band_editor else R.string.peq_band_list)
        adapter.selectedUuid = selectedBandByScope[selectedScope]
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

    override fun onDestroyView() {
        // ParametricEqBandAdapter registers an ObservableList callback on peqState's bands
        // (which JamesDspLocalEngine's own long-lived bmwPeqState.fullRangeBands can end up
        // sharing after applyCandidate() hands the same object to the engine) in
        // onAttachedToRecyclerView, removed again in onDetachedFromRecyclerView -- which only
        // fires if the adapter is actually cleared, not just left attached when the view dies.
        // Without this, LeakCanary traces the whole Activity retained through that callback.
        binding.bandList.adapter = null
        super.onDestroyView()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation == ORIENTATION_LANDSCAPE) collapsePreview(false)
        super.onConfigurationChanged(newConfig)
    }

    private fun collapsePreview(collapsed: Boolean) {
        binding.equalizerSurface.isVisible = collapsed
        binding.previewTitle.text = getString(if (collapsed) R.string.peq_preview else R.string.peq_preview_collapsed)
    }

    private fun applyCandidate(
        candidate: BmwPeqState,
        source: String,
        recordHistory: Boolean = true,
    ): Boolean {
        // When the service isn't running, validate against the lowest sample rate it can ever
        // open the recorder at (RootlessAudioProcessorService clamps to 44100-48000Hz), not the
        // usual 48kHz assumption. Otherwise a near-Nyquist band can pass validation here, get
        // persisted, and then silently fail validation -- and get dropped in favor of
        // last-known-good/empty -- the next time the service actually starts at a lower rate.
        val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: MIN_ASSUMED_SAMPLE_RATE
        val validation = candidate.validate(sampleRate)
        if (validation != null) {
            Timber.e("$source ${selectedScope.label} validation failed: $validation")
            requireContext().toast("$validation; previous PEQ remains active")
            return false
        }
        // nativeBmwPeqHandleReady() is a Boolean?: null means the service isn't running, but
        // `false` means it IS running with the handle not yet ready -- != null would wrongly
        // treat that as available and route into applyNativeBmwPeq(), which fails without ever
        // persisting the edit, silently discarding it.
        val serviceAvailable = RootlessAudioProcessorService.nativeBmwPeqHandleReady() == true
        val result = if (serviceAvailable) {
            RootlessAudioProcessorService.applyNativeBmwPeq(candidate)
        } else {
            candidate.persist(requireContext())
        }
        Timber.d(
            "$source scope=${selectedScope.label} full=${candidate.fullRangeBands.size} " +
                "low=${candidate.lowBandBands.size} mid=${candidate.midBandBands.size} " +
                "serviceAvailable=$serviceAvailable result=$result"
        )
        if (!result) {
            requireContext().toast(
                if (serviceAvailable) {
                    "BMW PEQ configuration rejected; previous state remains active"
                } else {
                    "BMW PEQ could not be saved; previous state remains active"
                }
            )
            return false
        }
        peqState = candidate
        if (recordHistory) history.commit(candidate)
        bindScope()
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        updateHistoryControls()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (editorActive) editorDiscard()
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val STATE_BANDS = "bands"
        // Lowest sample rate RootlessAudioProcessorService ever opens the recorder at.
        private const val MIN_ASSUMED_SAMPLE_RATE = 44_100f
        private const val GRAPH_PREFS = "peq_graph_display"
        private const val GRAPH_SHOW_OVERLAYS = "show_individual_filters"
        private const val GRAPH_CHANNEL = "channel_display"
        private const val GRAPH_DISPLAY_MODE = "peq_display_mode"
        // Unrelated to GRAPH_DISPLAY_MODE above (that's the Graph/List PeqDisplayMode toggle) --
        // this persists ParametricEqSurface.DisplayMode (Magnitude/Phase/Group Delay).
        private const val GRAPH_RESPONSE_MODE = "response_display_mode"
        private const val GRAPH_MENU_OVERLAYS = 1
        private const val GRAPH_MENU_BOTH = 2
        private const val GRAPH_MENU_LEFT = 3
        private const val GRAPH_MENU_RIGHT = 4
        private const val GRAPH_MENU_MODE_MAGNITUDE = 5
        private const val GRAPH_MENU_MODE_PHASE = 6
        private const val GRAPH_MENU_MODE_MAGNITUDE_PHASE = 7
        private const val GRAPH_MENU_MODE_GROUP_DELAY = 8
        private const val FILTER_DUPLICATE = 10
        private const val FILTER_MOVE_UP = 11
        private const val FILTER_MOVE_DOWN = 12
        private const val FILTER_COPY_SCOPE_BASE = 20
        private const val FILTER_COPY_ALL_SCOPE_BASE = 40
        private const val FILTER_COPY_LEFT_TO_RIGHT = 30
        private const val FILTER_COPY_RIGHT_TO_LEFT = 31
        private const val FILTER_SPLIT_BOTH = 32
        private const val HISTORY_LIMIT = 20
        private const val MAX_IMPORT_CHARS = 1_000_000
        fun newInstance() = ParametricEqualizerFragment()
    }

    private enum class PeqScope(val label: String, val fileName: String, val chipId: Int, val bank: BmwPeqBank) {
        FULL("Input Correction", "input_correction_parametric_eq.txt", R.id.peq_scope_full, BmwPeqBank.FULL),
        LOW("Low Band", "low_band_parametric_eq.txt", R.id.peq_scope_low, BmwPeqBank.LOW),
        MID("Mid Band", "mid_band_parametric_eq.txt", R.id.peq_scope_mid, BmwPeqBank.MID),
    }

    /** Landscape-only: which of edit_card/preview_card dominates the cards row. */
    private enum class PeqDisplayMode { GRAPH, LIST }
}
