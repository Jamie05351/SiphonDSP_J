package app.siphondsp.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.Toolbar
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
import app.siphondsp.utils.extensions.ContextExtensions.showYesNoAlert
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.isPlugin
import app.siphondsp.utils.isRoot
import app.siphondsp.utils.isRootless
import app.siphondsp.view.BmwDashboardSkin
import com.google.android.material.appbar.MaterialToolbar
import kotlin.math.roundToInt

/** Shared top-right chrome and BMW visual treatment for the dedicated DSP workspaces. */
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
            showYesNoAlert(R.string.revert_confirmation_title, R.string.revert_confirmation) { confirmed ->
                if (confirmed) restoreDspSettings()
            }
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
        installBmwChrome()
    }

    override fun onStop() {
        unregisterLocalReceiver(serviceStateReceiver)
        super.onStop()
    }

    private fun installBmwChrome() {
        findViewById<View>(R.id.params)?.post {
            findViewById<View>(R.id.params)?.let(BmwDashboardSkin::styleWorkspace)
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar) ?: return
        if (toolbar.findViewWithTag<View>(M_ACCENT_TAG) != null) return
        val accentHost = LinearLayout(this).apply {
            tag = M_ACCENT_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = .92f
        }
        BmwDashboardSkin.addMAccent(accentHost)
        toolbar.addView(
            accentHost,
            Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(58)
            },
        )
    }

    private fun rootlessServiceIsActive(): Boolean =
        RootlessAudioProcessorService.nativeBmwPeqHandleReady() != null

    private fun workspacePowerIsOn(): Boolean = when {
        isRootless() -> rootlessServiceIsActive()
        else -> prefsApp.get<Boolean>(R.string.key_powered_on)
    }

    private fun toggleWorkspacePower() {
        when {
            isRootless() -> {
                if (rootlessServiceIsActive()) {
                    RootlessAudioProcessorService.stop(this)
                } else {
                    val projection = app.mediaProjectionStartIntent
                    if (projection != null && !(SdkCheck.isVanillaIceCream && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)) {
                        RootlessAudioProcessorService.start(this, projection)
                    } else {
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

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

    companion object {
        private const val M_ACCENT_TAG = "bmw_m_toolbar_accent"
    }
}
