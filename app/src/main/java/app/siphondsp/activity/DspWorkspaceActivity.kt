package app.siphondsp.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.Menu
import android.view.MenuItem
import androidx.preference.DialogPreference.TargetFragment
import androidx.preference.Preference
import app.siphondsp.R
import app.siphondsp.fragment.FileLibraryDialogFragment
import app.siphondsp.interop.JamesDspRemoteEngine
import app.siphondsp.preference.FileLibraryPreference
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.SdkCheck
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.restoreDspSettings
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.isPlugin
import app.siphondsp.utils.isRoot
import app.siphondsp.utils.isRootless

/** Shared top-right chrome for the dedicated BMW DSP workspaces. */
abstract class DspWorkspaceActivity : BaseActivity() {
    private var presetDialogHost: WorkspacePresetFragment? = null

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidateOptionsMenu()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dsp_workspace, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.workspace_blocklist)?.isVisible = !isPlugin() && (!isRoot() || app.isEnhancedProcessing)
        menu.findItem(R.id.workspace_power)?.icon?.alpha = if (workspacePowerIsOn()) 255 else 120
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.workspace_power -> {
            toggleWorkspacePower()
            true
        }
        R.id.workspace_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.workspace_presets -> {
            showPresetLibrary()
            true
        }
        R.id.workspace_revert -> {
            restoreDspSettings()
            true
        }
        R.id.workspace_blocklist -> {
            startActivity(Intent(this, BlocklistActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Constants.ACTION_SERVICE_STARTED).apply {
            addAction(Constants.ACTION_SERVICE_STOPPED)
        }
        registerLocalReceiver(serviceStateReceiver, filter)
        invalidateOptionsMenu()
    }

    override fun onStop() {
        unregisterLocalReceiver(serviceStateReceiver)
        super.onStop()
    }

    private fun workspacePowerIsOn(): Boolean = when {
        isRootless() -> RootlessAudioProcessorService.isActive()
        else -> prefsApp.get<Boolean>(R.string.key_powered_on)
    }

    private fun toggleWorkspacePower() {
        when {
            isRootless() -> {
                if (RootlessAudioProcessorService.isActive()) {
                    RootlessAudioProcessorService.stop(this)
                } else {
                    val projection = app.mediaProjectionStartIntent
                    if (projection != null && !(SdkCheck.isVanillaIceCream && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)) {
                        RootlessAudioProcessorService.start(this, projection)
                    } else {
                        // The permission launcher is owned by MainActivity. Only fall back there
                        // when Android requires a fresh MediaProjection grant.
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_FORCE_SHOW_CAPTURE_PROMPT, true)
                        })
                    }
                }
            }
            isRoot() -> {
                if (JamesDspRemoteEngine.isPluginInstalled() == JamesDspRemoteEngine.PluginState.Available) {
                    prefsApp.set(R.string.key_powered_on, !prefsApp.get<Boolean>(R.string.key_powered_on))
                }
            }
            isPlugin() -> prefsApp.set(R.string.key_powered_on, !prefsApp.get<Boolean>(R.string.key_powered_on))
        }
        invalidateOptionsMenu()
    }

    private fun showPresetLibrary() {
        if (presetDialogHost == null) {
            presetDialogHost = WorkspacePresetFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .add(android.R.id.content, presetDialogHost!!)
                .commitNow()
        }
        presetDialogHost?.pref?.refresh()
        val dialog = FileLibraryDialogFragment.newInstance("presets")
        @Suppress("DEPRECATION")
        dialog.setTargetFragment(presetDialogHost, 0)
        dialog.show(supportFragmentManager, null)
    }

    class WorkspacePresetFragment : androidx.fragment.app.Fragment(), TargetFragment {
        val pref by lazy {
            FileLibraryPreference(requireContext(), null).apply {
                type = "Presets"
                key = "presets"
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Preference?> findPreference(key: CharSequence): T? = pref as? T

        companion object {
            fun newInstance() = WorkspacePresetFragment()
        }
    }
}
