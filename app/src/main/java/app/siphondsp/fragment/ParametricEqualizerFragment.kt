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
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import app.siphondsp.R
import app.siphondsp.BuildConfig
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.adapter.ParametricEqBandAdapter
import app.siphondsp.databinding.FragmentParametricEqBinding
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
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.ParametricEqSurface
import app.siphondsp.view.StaticPagerAdapter
import timber.log.Timber
import java.util.UUID

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

    private val apoImport = PeqApoImport(object : PeqApoImport.Host {
        override val context get() = requireContext()
        override val activeScope get() = selectedScope
        override val state get() = peqState
        override fun applyCandidate(candidate: BmwPeqState, source: String) =
            this@ParametricEqualizerFragment.applyCandidate(candidate, source)
    })

    private fun dialogs() = PeqDialogs(requireContext(), layoutInflater)

    private fun refreshActionChips() {
        binding.chipUndo.isEnabled = history.canUndo
        binding.chipRedo.isEnabled = history.canRedo
    }

    // One or more REW/APO ".txt" exports. A single file whose name isn't recognised falls back
    // to the classic "import into the current bank" dialog; anything else is routed by filename
    // (input / low_left / low_right / mid_left / mid_right -> bank + channel) in one shot.
    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            apoImport.handleApoImport(uris)
        }

    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        apoImport.exportTo(uri)
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
                    ?.use(ApoImportRouter::readImportText)
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
                val graphPrefs = PeqGraphPreferences(requireContext())
                val backup = PrivatePeqBackup(
                    createdAtEpochMs = System.currentTimeMillis(),
                    state = BmwPeqPreset.fromState(peqState, name = "Jamie private PEQ backup"),
                    graphDisplay = PrivatePeqBackup.GraphDisplay(
                        graphPrefs.showIndividualFilters,
                        graphPrefs.channelDisplayName,
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
                    ?.use(ApoImportRouter::readImportText)
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
                                // An older backup carries a shorter, position-encoded array; pad
                                // it forward so the new MBC/limiter indices land on their
                                // shipped defaults instead of tripping save()'s size check.
                                val restored = NativeBmwDspValues.padToCurrentSize(values.toFloatArray())
                                NativeBmwDspValues.save(requireContext(), restored)
                                NativeBmwDspValues.broadcast(requireContext(), restored)
                            }
                            PeqGraphPreferences(requireContext()).writeBackupGraphDisplay(
                                backup.graphDisplay.showIndividualFilters,
                                backup.graphDisplay.channelDisplay,
                            )
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
                    binding.equalizerSurface.setSystemValues(nativeDspValues)
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
        binding.equalizerSurface.showSpectrum = true

        binding.previewCard.setOnClickListener {
            if (resources.configuration.orientation != ORIENTATION_LANDSCAPE) {
                collapsePreview(!binding.equalizerSurface.isVisible)
            }
        }

        binding.bandList.layoutManager = LinearLayoutManager(requireContext())
        configureGraph()
        configureActionChips()
        loadBands(savedInstanceState)
        binding.peqScopeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val next = when (checkedIds.firstOrNull()) {
                R.id.peq_scope_low -> PeqScope.LOW
                R.id.peq_scope_mid -> PeqScope.MID
                else -> PeqScope.FULL
            }
            if (next != selectedScope) {
                selectedScope = next
                Timber.d("Selected BMW PEQ scope=${selectedScope.label}")
                bindScope()
            }
        }
        configurePager()
        updateViewState()
        return binding.root
    }

    /** Guard for ParametricEqualizerActivity's DspCrossNavBar.populate() call (the sidebar now
     *  lives at the activity level, alongside the toolbar, not inside this fragment -- see
     *  activity_parametric_eq.xml). Blocks switching to another DSP screen while there's an
     *  unsaved filter edit, same guard as switching Full/Low/Mid scope. */
    fun canSwitchDspScreens(): Boolean = true

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
        peqDisplayMode = runCatching {
            PeqDisplayMode.valueOf(
                PeqGraphPreferences(requireContext()).listModeName ?: PeqDisplayMode.GRAPH.name
            )
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
        PeqGraphPreferences(requireContext()).listModeName = mode.name
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

    private fun bandsForScope() = when (selectedScope) {
        PeqScope.FULL -> peqState.fullRangeBands
        PeqScope.LOW -> peqState.lowBandBands
        PeqScope.MID -> peqState.midBandBands
    }

    private fun bandsForScope(state: BmwPeqState, scope: PeqScope = selectedScope) =
        PeqBandEditor.bandsFor(state, scope)

    private fun replaceScopeBands(state: BmwPeqState, replacement: ParametricEqBandList) =
        PeqBandEditor.replaceScopeBands(state, selectedScope, replacement)

    private fun bindScope(preserveScroll: Boolean = false) {
        val bands = bandsForScope()
        // A per-cell -/+ edit funnels through applyCandidate -> bindScope, which swaps in a fresh
        // adapter; without this the RecyclerView snaps back to the top (or jumps to the focused
        // stepper's row) on every tap. Capture the layout manager's scroll anchor before the swap
        // and restore it after, and skip the scroll-to-selected below.
        val layoutManager = binding.bandList.layoutManager as? LinearLayoutManager
        val savedScroll = if (preserveScroll) layoutManager?.onSaveInstanceState() else null
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
            accentColor = scopeAccent()
            onDeleteClicked = { _, index ->
                val candidate = peqState.deepCopy()
                bandsForScope(candidate).removeAt(index)
                applyCandidate(candidate, "delete")
            }
            onTypeClicked = { band, index -> showFilterTypePicker(band, index) }
            onChannelClicked = { band, index -> showChannelPicker(band, index) }
            onFrequencyClicked = { band, index -> showFrequencyDialog(band, index) }
            onFrequencyStep = { _, index, factor ->
                commitBandEdit(index, "edit_frequency_step") {
                    ParametricEqBand(
                        (it.frequency * factor).coerceIn(20.0, 20000.0), it.gain, it.q,
                        it.filterType, it.channel, it.uuid,
                    )
                }
            }
            onGainClicked = { band, index -> showGainDialog(band, index) }
            onGainStep = { _, index, delta ->
                commitBandEdit(index, "edit_gain_step") {
                    ParametricEqBand(
                        it.frequency, (it.gain + delta).coerceIn(-30.0, 30.0), it.q,
                        it.filterType, it.channel, it.uuid,
                    )
                }
            }
            onQClicked = { band, index -> showQDialog(band, index) }
            onQStep = { _, index, delta ->
                commitBandEdit(index, "edit_q_step") {
                    ParametricEqBand(
                        it.frequency, it.gain, (it.q + delta).coerceIn(0.1, 30.0),
                        it.filterType, it.channel, it.uuid,
                    )
                }
            }
            onAddClicked = { performAdd() }
        }
        if (savedScroll != null) {
            layoutManager?.onRestoreInstanceState(savedScroll)
        } else {
            selectedBandByScope[selectedScope]?.let { uuid ->
                bands.indexOfFirst { it.uuid == uuid }.takeIf { it >= 0 }?.let(binding.bandList::scrollToPosition)
            }
        }
        updateViewState()
    }

    private fun configureGraph() {
        // Tilt now has its own dedicated page (CrossoverTiltFragment) for precise numeric
        // editing, but the drag-to-adjust handles stay directly on this graph too.
        binding.equalizerSurface.showTiltHandles = true
        binding.equalizerSurface.showGainMeters = true
        val graphPrefs = PeqGraphPreferences(requireContext())
        binding.equalizerSurface.showIndividualFilters = graphPrefs.showIndividualFilters
        binding.equalizerSurface.channelDisplay = graphPrefs.channelDisplay
        binding.equalizerSurface.displayMode = graphPrefs.responseMode

        // Read-only graph now: a node tap just highlights that filter's row on the band-list
        // page (only active-scope taps reach here). Dragging filters/tilt on the graph was
        // removed -- it only ever nudged filters by accident; tilt is edited on its own
        // numeric page. There's no in-graph editor to open any more -- the graph and the band
        // list are independent swipeable pages (see setUpCardsPager).
        binding.equalizerSurface.onPointSelected = { uuid ->
            bandsForScope().firstOrNull { it.uuid == uuid }?.let {
                selectedBandByScope[selectedScope] = it.uuid
                adapter.selectedUuid = it.uuid
            }
        }
    }

    private fun showGraphOptionsPopup(anchor: View) {
        val graphPrefs = PeqGraphPreferences(requireContext())
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
                    graphPrefs.showIndividualFilters = binding.equalizerSurface.showIndividualFilters
                    true
                }
                GRAPH_MENU_BOTH, GRAPH_MENU_LEFT, GRAPH_MENU_RIGHT -> {
                    binding.equalizerSurface.channelDisplay = when (item.itemId) {
                        GRAPH_MENU_LEFT -> ParametricEqSurface.ChannelDisplay.LEFT
                        GRAPH_MENU_RIGHT -> ParametricEqSurface.ChannelDisplay.RIGHT
                        else -> ParametricEqSurface.ChannelDisplay.BOTH
                    }
                    graphPrefs.channelDisplay = binding.equalizerSurface.channelDisplay
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
                    graphPrefs.responseMode = binding.equalizerSurface.displayMode
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Restores the pre-redesign horizontally-scrolling action-chip row (same row as the
     *  Full/Low/Mid scope chips) in place of the toolbar's 3-dot overflow menu -- same
     *  underlying functions as before, just triggered from chips instead of menu items. */
    private fun configureActionChips() {
        binding.chipUndo.setOnClickListener { performUndo() }
        binding.chipRedo.setOnClickListener { performRedo() }
        binding.chipReset.setOnClickListener { performReset() }
        binding.chipEditString.setOnClickListener { performEditAsString() }
        binding.chipImportFile.setOnClickListener { performImport() }
        binding.chipExportFile.setOnClickListener { performExport() }
        binding.chipPresetImport.setOnClickListener { performPresetImport() }
        binding.chipPresetExport.setOnClickListener { performPresetExport() }
        binding.chipBackupImport.setOnClickListener { performBackupImport() }
        binding.chipBackupExport.setOnClickListener { performBackupExport() }
        binding.chipFilterTools.setOnClickListener { showFilterTools(it) }
        binding.chipDiagnostics.setOnClickListener { showDiagnosticReport() }
        binding.chipGraphOptions.setOnClickListener { showGraphOptionsPopup(it) }
        refreshActionChips()
    }

    private fun performReset() {
        // Constants.DEFAULT_PEQ is an empty filter string, ie. there's no curated default
        // curve to restore -- clearing is the only actual behavior, for every scope including
        // Full. Word the confirmation to match that instead of implying a designed baseline.
        requireContext().showYesNoAlert(
            "Clear ${selectedScope.label}?",
            if (selectedScope == PeqScope.FULL) {
                "Clear every filter from Full Range and reset its preamp to 0 dB?"
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
                // preampDb is a single global field, not scoped per band group -- a Preamp line
                // in the pasted text should always apply, not just when editing Full scope.
                applyCandidate(candidate.copy(preampDb = result.preampDb.toFloat()), "text-import")
            }
        }
    }

    private fun performAdd() {
        val candidate = peqState.deepCopy()
        val list = bandsForScope(candidate)
        val newBand = ParametricEqBand(
            1000.0, 0.0, 1.41, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT
        )
        list.add(newBand)
        if (applyCandidate(candidate, "add")) {
            selectedBandByScope[selectedScope] = newBand.uuid
        }
    }

    /** Shared instant-commit path for every per-cell edit: deep-copy the state, swap the one
     *  band at [index] in the active scope for [transform]'s result, and funnel it through
     *  [applyCandidate] (validation + persistence + native push + undo history + bindScope). */
    private fun commitBandEdit(index: Int, source: String, transform: (ParametricEqBand) -> ParametricEqBand) {
        val candidate = peqState.deepCopy()
        val list = bandsForScope(candidate)
        if (index !in list.indices) return
        val updated = transform(list[index])
        list[index] = updated
        // Select this band before the commit so bindScope() paints its solid-fill highlight on
        // the row being stepped; the UUID is carried through the transform so it stays valid even
        // if applyCandidate rejects the edit.
        selectedBandByScope[selectedScope] = updated.uuid
        applyCandidate(candidate, source, preserveScroll = true)
    }

    /** Per-scope cell accent, matching BmwDashboardSkin's app-wide Low=blue / Mid=yellow
     *  convention; Input Correction stays neutral. Used for the band-row cells and the
     *  tap-to-commit choice picker. */
    private fun scopeAccent(): Int? = when (selectedScope) {
        PeqScope.FULL -> null
        PeqScope.LOW -> BmwDashboardSkin.LIGHT_BLUE
        PeqScope.MID -> BmwDashboardSkin.MID_BAND_YELLOW
    }

    private fun showFrequencyDialog(band: ParametricEqBand, index: Int) = dialogs().showValueInput(
        getString(R.string.peq_frequency), band.frequency, 20.0, 20000.0, "Hz",
    ) { value ->
        commitBandEdit(index, "edit_frequency") {
            ParametricEqBand(value, it.gain, it.q, it.filterType, it.channel, it.uuid)
        }
    }

    private fun showGainDialog(band: ParametricEqBand, index: Int) = dialogs().showValueInput(
        getString(R.string.peq_gain), band.gain, -30.0, 30.0, "dB",
    ) { value ->
        commitBandEdit(index, "edit_gain") {
            ParametricEqBand(it.frequency, value, it.q, it.filterType, it.channel, it.uuid)
        }
    }

    private fun showQDialog(band: ParametricEqBand, index: Int) = dialogs().showValueInput(
        getString(R.string.peq_q_factor), band.q, 0.1, 30.0, null,
    ) { value ->
        commitBandEdit(index, "edit_q") {
            ParametricEqBand(it.frequency, it.gain, value, it.filterType, it.channel, it.uuid)
        }
    }

    private fun showFilterTypePicker(band: ParametricEqBand, index: Int) {
        val types = ParametricEqFilterType.entries
        dialogs().showChoice(
            titleRes = R.string.peq_filter_type,
            labels = listOf(
                getString(R.string.peq_filter_type_peaking),
                getString(R.string.peq_filter_type_low_shelf),
                getString(R.string.peq_filter_type_high_shelf),
                getString(R.string.peq_filter_type_notch),
            ),
            currentIndex = types.indexOf(band.filterType),
            accentColor = scopeAccent(),
        ) { picked ->
            val newType = types.getOrNull(picked) ?: return@showChoice
            commitBandEdit(index, "edit_filter_type") {
                ParametricEqBand(it.frequency, it.gain, it.q, newType, it.channel, it.uuid)
            }
        }
    }

    private fun showChannelPicker(band: ParametricEqBand, index: Int) {
        val channels = ParametricEqChannel.entries
        dialogs().showChoice(
            titleRes = R.string.peq_channel,
            labels = channels.map { it.displayLabel },
            currentIndex = channels.indexOf(band.channel),
            accentColor = scopeAccent(),
        ) { picked ->
            val newChannel = channels.getOrNull(picked) ?: return@showChoice
            commitBandEdit(index, "edit_channel") {
                ParametricEqBand(it.frequency, it.gain, it.q, it.filterType, newChannel, it.uuid)
            }
        }
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
        when (val result = PeqBandEditor.copyWholeScope(peqState, selectedScope, target, append)) {
            is PeqBandEditResult.Overflow -> requireContext().toast(PeqBandEditor.overflowToast(result))
            is PeqBandEditResult.Changed -> applyCandidate(result.candidate, result.undoSource)
            else -> Unit
        }
    }

    private fun duplicateSelectedFilter(index: Int): Boolean {
        when (val result = PeqBandEditor.duplicateFilter(peqState, selectedScope, index)) {
            is PeqBandEditResult.Overflow -> requireContext().toast(PeqBandEditor.overflowToast(result))
            is PeqBandEditResult.Changed -> {
                selectedBandByScope[selectedScope] = result.select
                applyCandidate(result.candidate, result.undoSource)
            }
            else -> Unit
        }
        return true
    }

    private fun copySelectedFilter(index: Int, target: PeqScope): Boolean {
        when (val result = PeqBandEditor.copyFilter(peqState, selectedScope, index, target)) {
            is PeqBandEditResult.Overflow -> requireContext().toast(PeqBandEditor.overflowToast(result))
            is PeqBandEditResult.Changed ->
                if (applyCandidate(result.candidate, result.undoSource)) {
                    selectedBandByScope[target] = result.select
                }
            else -> Unit
        }
        return true
    }

    private fun moveSelectedFilter(from: Int, to: Int): Boolean {
        when (val result = PeqBandEditor.moveFilter(peqState, selectedScope, from, to)) {
            is PeqBandEditResult.Changed -> {
                selectedBandByScope[selectedScope] = result.select
                applyCandidate(result.candidate, result.undoSource)
            }
            else -> Unit
        }
        return true
    }

    private fun copyChannelFilters(
        sourceChannel: ParametricEqChannel,
        destinationChannel: ParametricEqChannel,
        replaceBoth: Boolean,
    ): Boolean {
        when (
            val result = PeqBandEditor.copyChannelFilters(
                peqState, selectedScope, sourceChannel, destinationChannel, replaceBoth,
            )
        ) {
            is PeqBandEditResult.Overflow -> requireContext().toast(PeqBandEditor.overflowToast(result))
            PeqBandEditResult.NoMatchingChannel ->
                requireContext().toast("No ${sourceChannel.displayLabel} filters to copy")
            is PeqBandEditResult.Changed -> applyCandidate(result.candidate, result.undoSource)
            else -> Unit
        }
        return true
    }

    private fun updateViewState() {
        binding.editCardTitle.text = getString(R.string.peq_band_list)
        adapter.selectedUuid = selectedBandByScope[selectedScope]
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
        // Portrait only: preview_card is now a weighted member of the vertical chain with
        // edit_card (so the graph fills down to the screen). Hiding the graph therefore also has
        // to drop the card's weight/height, or it keeps its large share of the column with just
        // the title in it. Landscape re-parents preview_card into cards_pager with plain
        // LayoutParams -- the cast fails there and this is a no-op, which is what we want.
        (binding.previewCard.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.height = if (collapsed) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            lp.verticalWeight = if (collapsed) 1f else 0f
            binding.previewCard.layoutParams = lp
        }
    }

    private fun applyCandidate(
        rawCandidate: BmwPeqState,
        source: String,
        recordHistory: Boolean = true,
        preserveScroll: Boolean = false,
    ): Boolean {
        // PEQ has no enable/disable control anymore -- it's always live -- so every candidate
        // funnelled through here (band edits, preset import, backup restore, etc.) is coerced on
        // regardless of what an imported preset or legacy persisted state happened to carry.
        val candidate = rawCandidate.copy(enabled = true)
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
        bindScope(preserveScroll)
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        updateHistoryControls()
        return true
    }

    companion object {
        const val STATE_BANDS = "bands"
        // Lowest sample rate RootlessAudioProcessorService ever opens the recorder at.
        private const val MIN_ASSUMED_SAMPLE_RATE = 44_100f
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
        fun newInstance() = ParametricEqualizerFragment()
    }

    /** Landscape-only: which of edit_card/preview_card dominates the cards row. */
    private enum class PeqDisplayMode { GRAPH, LIST }
}
