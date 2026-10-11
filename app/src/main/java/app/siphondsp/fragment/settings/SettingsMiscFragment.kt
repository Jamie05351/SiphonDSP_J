package app.siphondsp.fragment.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import app.siphondsp.BuildConfig
import app.siphondsp.R
import app.siphondsp.activity.OnboardingActivity
import app.siphondsp.model.FactoryBaselineStore
import app.siphondsp.preference.IconPreference
import app.siphondsp.preference.MaterialSwitchPreference
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.AssetManagerExtensions.installPrivateAssets
import app.siphondsp.utils.extensions.ContextExtensions.showAlert
import app.siphondsp.utils.extensions.ContextExtensions.showYesNoAlert
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import app.siphondsp.utils.isRootless
import app.siphondsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject

class SettingsMiscFragment : SettingsBaseFragment() {

    private val autoStartNotify by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_autostart_prompt_at_boot)) }
    private val repairAssets by lazy { findPreference<Preference>(getString(R.string.key_troubleshooting_repair_assets)) }
    private val debugDatabase by lazy { findPreference<Preference>(getString(R.string.key_debug_database)) }
    private val permSkipPrompt by lazy { findPreference<IconPreference>(getString(R.string.key_misc_permission_skip_prompt)) }
    private val permAutoStart by lazy { findPreference<IconPreference>(getString(R.string.key_misc_permission_auto_start)) }
    private val permRestartSetup by lazy { findPreference<Preference>(getString(R.string.key_misc_permission_restart_setup)) }
    private val baselineImport by lazy { findPreference<Preference>(getString(R.string.key_factory_baseline_import)) }
    private val baselineExport by lazy { findPreference<Preference>(getString(R.string.key_factory_baseline_export)) }
    private val baselineReset by lazy { findPreference<Preference>(getString(R.string.key_factory_baseline_reset)) }

    // Same picker pattern as the PEQ backup / restore: the file is validated before it replaces anything.
    private val baselineImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        FactoryBaselineStore.importFrom(ctx, uri)
            .onSuccess {
                val sums = if (it.hasSeatSums) ", seat sums for ${it.seat}" else ", no seat sums"
                ctx.showAlert(getString(R.string.success), "Imported ${it.drivers.size} driver curves$sums.\n${it.source}")
            }
            .onFailure { ctx.showAlert(getString(R.string.factory_baseline_import), "Not imported: ${it.message}") }
        updateBaselineSummary()
    }
    private val baselineExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        FactoryBaselineStore.exportTo(ctx, uri)
            .onSuccess { ctx.toast("Factory baseline exported") }
            .onFailure { ctx.showAlert(getString(R.string.factory_baseline_export), "Not exported: ${it.message}") }
    }

    private val preferences: Preferences.App by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_misc_preferences, rootKey)

        repairAssets?.setOnPreferenceClickListener {
            requireContext().assets.installPrivateAssets(requireContext(), force = true)
            requireContext().showAlert(R.string.success, R.string.troubleshooting_repair_assets_success)
            true
        }


        permRestartSetup?.setOnPreferenceClickListener {
            startActivity(Intent(context, OnboardingActivity::class.java).apply {
                putExtra(OnboardingActivity.EXTRA_ROOTLESS_REDO_ADB_SETUP, true)
            })
            true
        }

        baselineImport?.setOnPreferenceClickListener {
            baselineImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            true
        }
        baselineExport?.setOnPreferenceClickListener {
            baselineExportLauncher.launch(FactoryBaselineStore.FILE_NAME)
            true
        }
        baselineReset?.setOnPreferenceClickListener {
            requireContext().showYesNoAlert(R.string.factory_baseline_reset, R.string.factory_baseline_reset_summary) { yes ->
                if (yes) {
                    FactoryBaselineStore.resetToBuiltIn(requireContext())
                    updateBaselineSummary()
                }
            }
            true
        }

        updatePermissionStates()
        updateBaselineSummary()

        debugDatabase?.parent?.isVisible = BuildConfig.DEBUG
        autoStartNotify?.isVisible = isRootless()
        permRestartSetup?.parent?.isVisible = isRootless()
    }

    override fun onResume() {
        updatePermissionStates()
        super.onResume()
    }

    private fun updateBaselineSummary() {
        val ctx = context ?: return
        val source = FactoryBaselineStore.source(ctx)
        baselineImport?.summary = getString(
            when (source) {
                FactoryBaselineStore.Source.IMPORTED -> R.string.factory_baseline_source_imported
                FactoryBaselineStore.Source.BUILT_IN -> R.string.factory_baseline_source_built_in
                FactoryBaselineStore.Source.NONE -> R.string.factory_baseline_source_none
            },
        )
        baselineExport?.isEnabled = source != FactoryBaselineStore.Source.NONE
        baselineReset?.isEnabled = source == FactoryBaselineStore.Source.IMPORTED
    }

    private fun updatePermissionStates() {
        if(!isRootless())
            return

        val allowSkipPrompt = context?.hasProjectMediaAppOp() == true
        val allowAutoStart = allowSkipPrompt && Settings.canDrawOverlays(context)

        autoStartNotify?.title = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot
            else R.string.autostart_prompt_at_boot
        )
        autoStartNotify?.summaryOn = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot_on
            else R.string.autostart_prompt_at_boot_on
        )
        autoStartNotify?.summaryOff = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot_off
            else R.string.autostart_prompt_at_boot_off
        )

        fun getIcon(allowed: Boolean) = context?.let {
            ContextCompat.getDrawable(it,
                if(allowed) R.drawable.ic_twotone_check_circle_24dp
                else R.drawable.ic_twotone_warning_24dp
            )
        }

        fun getSummary(allowed: Boolean) = context?.getString(
            if(allowed) R.string.permission_allowed
            else R.string.permission_not_allowed
        )

        permSkipPrompt?.summary = getSummary(allowSkipPrompt)
        permAutoStart?.summary = getSummary(allowAutoStart)
        permSkipPrompt?.icon = getIcon(allowSkipPrompt)
        permAutoStart?.icon = getIcon(allowAutoStart)
        permRestartSetup?.isVisible = !allowSkipPrompt || !allowAutoStart
    }

    companion object {
        fun newInstance(): SettingsMiscFragment {
            return SettingsMiscFragment()
        }
    }
}