package app.siphondsp.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
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
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/** Shared chrome for the dedicated BMW DSP workspaces: the power toggle (still a Toolbar action
 *  icon) plus the settings/overflow buttons every activity_parametric_eq-based layout's sidebar
 *  provides -- see [setUpWorkspaceSidebarActions]. */
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
        menu.findItem(R.id.workspace_power)?.icon?.alpha = if (workspacePowerIsOn()) 255 else 120
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.workspace_power -> {
            toggleWorkspacePower()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** Wires the sidebar's settings/overflow buttons and gives them the same lit, always-on
     *  accent look as DspCrossNavBar's icons (rather than the plain outlined-button default,
     *  which reads as an afterthought bolted onto that panel) -- call once after
     *  setContentView(), same as each subclass already calls DspCrossNavBar.populate() itself.
     *  (AppCompatActivity doesn't reliably call onContentChanged() the way plain Activity does
     *  -- it delegates setContentView() to AppCompatDelegate instead -- so that hook isn't a
     *  safe place for this.) */
    protected fun setUpWorkspaceSidebarActions() {
        val settingsButton = findViewById<MaterialButton>(R.id.workspace_settings_button)
        val moreButton = findViewById<MaterialButton>(R.id.workspace_more_button)
        listOfNotNull(settingsButton, moreButton).forEach { button ->
            button.backgroundTintList = ColorStateList.valueOf(Color.rgb(26, 69, 103))
            button.strokeColor = ColorStateList.valueOf(BmwDashboardSkin.LIGHT_BLUE)
            button.strokeWidth = (1 * resources.displayMetrics.density).roundToInt()
            button.iconTint = ColorStateList.valueOf(BmwDashboardSkin.LIGHT_BLUE)
        }
        settingsButton?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        moreButton?.setOnClickListener(::showWorkspaceMoreMenu)
    }

    /** Formerly the Toolbar's 3-dot overflow submenu; same items, same actions, now triggered
     *  from the sidebar's workspace_more_button instead. */
    private fun showWorkspaceMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_dsp_workspace_more, popup.menu)
        popup.menu.findItem(R.id.workspace_blocklist)?.isVisible = !isPlugin() && (!isRoot() || app.isEnhancedProcessing)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.workspace_routing -> {
                    startActivity(Intent(this, CrossoverTiltActivity::class.java).apply {
                        putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, CrossoverTiltActivity.MODE_ROUTING)
                    })
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
                else -> false
            }
        }
        popup.show()
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
