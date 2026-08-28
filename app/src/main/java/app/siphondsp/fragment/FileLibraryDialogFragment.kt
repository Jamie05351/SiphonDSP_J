package app.siphondsp.fragment

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.preference.DialogPreference.TargetFragment
import androidx.preference.ListPreferenceDialogFragmentCompat
import androidx.preference.Preference
import kotlinx.coroutines.*
import app.siphondsp.BuildConfig
import app.siphondsp.MainApplication
import app.siphondsp.R
import app.siphondsp.databinding.DialogFilelibraryBinding
import app.siphondsp.interop.JdspImpResToolbox
import app.siphondsp.preference.FileLibraryPreference
import app.siphondsp.utils.extensions.ContextExtensions.showAlert
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.storage.StorageUtils
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt


/**
 * File picker for the Convolver's impulse-response library. (Historically also served the
 * Liveprog script library and the generic-preset library -- both removed.)
 */
class FileLibraryDialogFragment : ListPreferenceDialogFragmentCompat(), TargetFragment {

    private val fileLibPreference by lazy {
        preference as FileLibraryPreference
    }

    private var clickedEntryValue: CharSequence? = null
    private lateinit var dialog: AlertDialog
    private lateinit var importLauncher: ActivityResultLauncher<Intent>
    private lateinit var binding: DialogFilelibraryBinding

    private val scriptScannerScope = CoroutineScope(Dispatchers.IO)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        dialog = super.onCreateDialog(savedInstanceState) as AlertDialog
        // Workaround to prevent the button from closing the dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { import() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = false
        }

        dialog.listView.setOnItemLongClickListener {
                _, view, position, _ ->
            val item = dialog.listView.adapter.getItem(position) as Entry
            val name = item.name
            val path = FileLibraryPreference.createFullPathCompat(requireContext(), item.value.toString())

            val popupMenu = PopupMenu(requireContext(), view)
            popupMenu.menuInflater.inflate(R.menu.menu_filelibrary_context, popupMenu.menu)
            popupMenu.menu.findItem(R.id.duplicate_selection).isVisible = false
            popupMenu.menu.findItem(R.id.edit_selection).isVisible = false
            popupMenu.menu.findItem(R.id.overwrite_selection).isVisible = false
            popupMenu.menu.findItem(R.id.resample_selection).isVisible = fileLibPreference.isIrs()

            popupMenu.setOnMenuItemClickListener { menuItem ->
                val selectedFile = File(path.toString())
                when (menuItem.itemId) {
                    R.id.resample_selection -> {
                        if(fileLibPreference.isIrs()) {
                            var targetRate = (requireActivity().application as MainApplication).engineSampleRate.roundToInt()
                            if (targetRate <= 0) {
                                targetRate = requireContext().getSystemService<AudioManager>()
                                    ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                                    ?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 48000
                                Timber.w("resample: engine sample rate is zero, using HAL rate instead")
                            }

                            Timber.d("resample: Resampling ${selectedFile.name} to ${targetRate}Hz")

                            scriptScannerScope.launch {
                                val newName = JdspImpResToolbox.OfflineAudioResample(
                                    (selectedFile.absoluteFile.parentFile?.absolutePath + "/"),
                                    selectedFile.name,
                                    targetRate
                                )

                                withContext(Dispatchers.Main) {
                                    try {
                                        if (newName == "Invalid")
                                            requireContext().toast(getString(R.string.filelibrary_resample_failed))
                                        else
                                            requireContext().toast(getString(R.string.filelibrary_resample_complete,
                                                targetRate))
                                        refresh()
                                    }
                                    catch (_: IllegalStateException) {
                                        // Context may not be attached to fragment at this point
                                    }
                                }
                            }
                        }
                        refresh()
                    }
                    R.id.rename_selection -> {
                        showFileNamePrompt(
                            R.string.filelibrary_context_rename,
                            selectedFile,
                            autofill = true,
                            allowOverwrite = false
                        ) {
                            selectedFile.renameTo(it)
                            requireContext().toast(getString(R.string.filelibrary_renamed, it.nameWithoutExtension))
                            refresh()
                        }
                    }
                    R.id.delete_selection -> {
                        selectedFile.delete()
                        requireContext().toast(getString(R.string.filelibrary_deleted, name))
                        refresh()

                        // If this file was active, we need to reset the selection to null
                        if (fileLibPreference.callChangeListener("")) {
                            fileLibPreference.value = ""
                        }
                    }
                    R.id.share_selection -> {
                        val uri = FileProvider.getUriForFile(
                            requireContext(),
                            BuildConfig.APPLICATION_ID + ".file_library_provider",
                            selectedFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND)
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                        shareIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        shareIntent.type = "application/octet-stream"
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.filelibrary_context_share)))
                    }
                }
                true
            }
            popupMenu.show()
            true
        }

        refreshSelection()

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK)
                return@registerForActivityResult

            result?.data?.data?.let { uri ->
                val correctType = fileLibPreference.hasCorrectExtension(
                    StorageUtils.queryName(
                        requireContext(),
                        uri
                    ) ?: "INVALID"
                )
                if(!correctType)
                {
                    requireContext().showAlert(R.string.filelibrary_unsupported_format_title,
                        R.string.filelibrary_unsupported_format)
                    return@let
                }

                val file = StorageUtils.importFile(requireContext(),
                    fileLibPreference.directory?.absolutePath ?: "", uri)
                if(file == null)
                {
                    Timber.e("Failed to import file")
                    return@let
                }

                scriptScannerScope.launch(Dispatchers.Main) {
                    delay(150L)
                    refresh()
                }
            }
        }

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        scriptScannerScope.cancel()

        if (positiveResult && !clickedEntryValue.isNullOrEmpty()) {
            val value = clickedEntryValue.toString()

            if (fileLibPreference.callChangeListener(value)) {
                fileLibPreference.value = value
            }
        }
    }

    private fun showFileNamePrompt(
        @StringRes title: Int,
        selectedFile: File,
        autofill: Boolean,
        allowOverwrite: Boolean,
        callback: (File) -> Unit,
    ) {
        requireContext().showInputAlert(
            layoutInflater,
            title,
            R.string.filelibrary_new_file_name,
            if (autofill) selectedFile.nameWithoutExtension else "",
            false,
            null
        ) {
            if (it != null) {
                val newFile =
                    File(selectedFile.absoluteFile.parentFile!!.absolutePath + File.separator + it + "." + selectedFile.extension)
                if (newFile.exists() && !allowOverwrite) {
                    requireContext().toast(getString(R.string.filelibrary_file_exists))
                    return@showInputAlert
                }
                callback.invoke(newFile)
            }
        }
    }

    private fun refresh() {
        fileLibPreference.refresh()
        dialog.listView.adapter = createAdapter()
        refreshSelection()
    }

    private fun refreshSelection() {
        val selectedIndex = (dialog.listView.adapter as? ListItemAdapter)?.indexOf(fileLibPreference.value) ?: -1
        if (selectedIndex >= 0) {
            dialog.listView.setItemChecked(selectedIndex, true)
            dialog.listView.setSelection(selectedIndex)
        }
        else {
            dialog.listView.setItemChecked(-1, true)
        }
    }

    @SuppressLint("PrivateResource")
    private fun createAdapter(): ListAdapter {
        return ListItemAdapter(
            requireContext(),
            com.google.android.material.R.layout.mtrl_alert_select_dialog_singlechoice,
            android.R.id.text1,
            fileLibPreference.entries.zip(fileLibPreference.entryValues){
                    a, b -> Entry(a, b)
            }.toTypedArray(),
        ) {
            refreshSelection()
        }
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        binding = DialogFilelibraryBinding.inflate(layoutInflater)
        binding.tags.isVisible = false
        binding.root.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
        }

        builder.setView(binding.root)
        builder.setNeutralButton(getString(R.string.action_import)) { _, _ -> }

        builder.setAdapter(createAdapter()) { _, position ->
            val item = dialog.listView.adapter.getItem(position) as Entry
            clickedEntryValue = item.value

            // Simulate positive button press and dismiss
            this.onClick(dialog, DialogInterface.BUTTON_POSITIVE)
            dialog.dismiss()
        }
    }

    private fun import() {
        try {
            importLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            })
        }
        catch(ex: Exception) {
            Timber.e("No activity found")
            Timber.i(ex)
            requireContext().toast(R.string.no_activity_found)
        }
    }

    data class Entry(val name: CharSequence, val value: CharSequence) {
        override fun toString() = name.toString()
    }

    private inner class ListItemAdapter(
        context: Context, resource: Int, textViewResourceId: Int, val allItems: Array<Entry>,
        val onFiltered: () -> Unit
    ) : ArrayAdapter<Entry>(context, resource, textViewResourceId, allItems) {
        private var items: Array<Entry> = allItems

        fun indexOf(value: String): Int {
            return items.map { it.value }.indexOf(value)
        }
        override fun hasStableIds(): Boolean = true
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Entry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Preference?> findPreference(key: CharSequence): T? {
        return if(key == arguments?.getString(BUNDLE_KEY))
            fileLibPreference as? T
        else
            null
    }

    companion object {
        private const val BUNDLE_KEY = "key"

        fun newInstance(key: String): FileLibraryDialogFragment {
            val fragment = FileLibraryDialogFragment()

            val args = Bundle()
            args.putString(BUNDLE_KEY, key)
            fragment.arguments = args
            return fragment
        }
    }
}
