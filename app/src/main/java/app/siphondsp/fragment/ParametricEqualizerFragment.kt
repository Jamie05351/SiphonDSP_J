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
import timber.log.Timber
import java.util.UUID

class ParametricEqualizerFragment : Fragment() {
    private lateinit var binding: FragmentParametricEqBinding
    private val adapter get() = binding.bandList.adapter as ParametricEqBandAdapter

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
        }

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
            val result = adapter.bands.fromApoString(text)
            binding.preampInput.value = result.preampDb.toFloat()
            binding.equalizerSurface.setPreampDb(result.preampDb)
            save()
            updateViewState()
            val message = getString(R.string.peq_import_success, adapter.bands.size)
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
            val apoString = adapter.bands.toApoString(binding.preampInput.value.toDouble())
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

        binding.previewCard.setOnClickListener {
            if (resources.configuration.orientation != ORIENTATION_LANDSCAPE) {
                collapsePreview(!binding.equalizerSurface.isVisible)
            }
        }

        binding.reset.setOnClickListener {
            requireContext().showYesNoAlert(R.string.peq_reset_confirm_title, R.string.peq_reset_confirm) { confirmed ->
                if (confirmed) {
                    adapter.bands.deserialize(Constants.DEFAULT_PEQ)
                    binding.preampInput.value = 0f
                    binding.equalizerSurface.setPreampDb(0.0)
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
                adapter.bands.toApoString(binding.preampInput.value.toDouble()),
                false,
                null,
            ) { text ->
                text?.let {
                    val result = adapter.bands.fromApoString(it)
                    binding.preampInput.value = result.preampDb.toFloat()
                    binding.equalizerSurface.setPreampDb(result.preampDb)
                    save()
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
            updateViewState()
        }

        binding.importFile.setOnClickListener { importFileLauncher.launch(arrayOf("text/plain", "text/*")) }
        binding.exportFile.setOnClickListener { exportFileLauncher.launch("parametric_eq.txt") }

        binding.freqInput.setOnValueChangedListener { editorApply() }
        binding.gainInput.setOnValueChangedListener { editorApply() }
        binding.qInput.setOnValueChangedListener { editorApply() }
        binding.filterTypeGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) editorApply() }

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
            binding.equalizerSurface.setPreampDb(binding.preampInput.value.toDouble())
            savePreamp()
        }

        binding.bandList.layoutManager = LinearLayoutManager(requireContext())
        loadBands(savedInstanceState)
        updateViewState()
        return binding.root
    }

    private fun loadBands(savedInstanceState: Bundle?) {
        val bands = ParametricEqBandList()
        val prefs = requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
        savedInstanceState?.getBundle(STATE_BANDS)?.let(bands::fromBundle)
            ?: bands.deserialize(prefs.getString(getString(R.string.key_peq_bands), Constants.DEFAULT_PEQ)!!)

        val preampDb = prefs.getFloat(getString(R.string.key_peq_preamp), 0f)
        binding.preampInput.value = preampDb
        binding.equalizerSurface.setBands(bands, preampDb.toDouble())

        binding.bandList.adapter = ParametricEqBandAdapter(bands).apply {
            onItemsChanged = {
                binding.equalizerSurface.setBands(it.bands, binding.preampInput.value.toDouble())
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
                updateViewState()
            }
            onChannelClicked = { band, position ->
                val next = when (band.channel) {
                    ParametricEqChannel.LEFT_RIGHT -> ParametricEqChannel.LEFT
                    ParametricEqChannel.LEFT -> ParametricEqChannel.RIGHT
                    ParametricEqChannel.RIGHT -> ParametricEqChannel.LEFT_RIGHT
                }
                bands[position] = ParametricEqBand(
                    band.frequency,
                    band.gain,
                    band.q,
                    band.filterType,
                    next,
                    band.uuid,
                )
            }
        }
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

    private fun editorApply() {
        if (!editorCanSave()) return
        val uuid = editorBandUuid
        val existing = uuid?.let { id -> adapter.bands.firstOrNull { it.uuid == id } }
        val band = ParametricEqBand(
            binding.freqInput.value.toDouble(),
            binding.gainInput.value.toDouble(),
            binding.qInput.value.toDouble(),
            getSelectedFilterType(),
            existing?.channel ?: ParametricEqChannel.LEFT_RIGHT,
            uuid ?: UUID.randomUUID(),
        )

        if (uuid == null) {
            adapter.bands.add(band)
            editorBandUuid = band.uuid
        } else {
            val index = adapter.bands.indexOfFirst { it.uuid == uuid }
            if (index >= 0) adapter.bands[index] = band
            else Timber.e("editorApply: failed to find matching band UUID")
        }
    }

    private fun editorDiscard() {
        val uuid = editorBandUuid
        if (editorBandBackup != null && uuid != null) {
            val index = adapter.bands.indexOfFirst { it.uuid == uuid }
            if (index >= 0) adapter.bands[index] = editorBandBackup
        } else if (uuid != null) {
            adapter.bands.removeAll { it.uuid == uuid }
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
        requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE).edit()
            .putString(getString(R.string.key_peq_bands), adapter.bands.serialize())
            .putFloat(getString(R.string.key_peq_preamp), binding.preampInput.value)
            .commit()
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
        outState.putBundle(STATE_BANDS, adapter.bands.toBundle())
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val STATE_BANDS = "bands"
        fun newInstance() = ParametricEqualizerFragment()
    }
}
