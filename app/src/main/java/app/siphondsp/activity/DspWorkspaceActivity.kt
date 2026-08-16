package app.siphondsp.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.Menu
import android.view.MenuItem
import android.view.View
import app.siphondsp.R
import app.siphondsp.interop.JamesDspRemoteEngine
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.SdkCheck
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.isPlugin
import app.siphondsp.utils.isRoot
import app.siphondsp.utils.isRootless
import app.siphondsp.view.BmwDashboardSkin

/** Shared chrome for the dedicated BMW DSP workspaces: the power toggle (still a Toolbar action
 *  icon). Settings, Presets, Revert, and Blocklist live solely on MainActivity's bottom bar now;
 *  they used to also be reachable from a settings cog/3-dot overflow in this sidebar, removed in
 *  favor of Routing taking that space as a DspCrossNavBar tile instead. The sidebar itself has no
 *  background of its own -- it's transparent, so the same continuous photo background painted on
 *  the workspace root (paintWorkspaceBackground/styleWorkspace) shows through behind it too,
 *  rather than a separate solid panel colour hiding it. */
abstract class DspWorkspaceActivity : BaseActivity() {
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

}
