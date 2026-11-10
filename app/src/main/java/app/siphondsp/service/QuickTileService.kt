package app.siphondsp.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.siphondsp.MainApplication
import app.siphondsp.R
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.utils.EngineUtils.toggleEnginePower
import app.siphondsp.utils.SdkCheck
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import app.siphondsp.utils.isRootless
import app.siphondsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickTileService : TileService(),
    SharedPreferences.OnSharedPreferenceChangeListener, KoinComponent {

    private val app
        get() = application as MainApplication

    private val preferences: Preferences.App by inject()

    // While the tile is listening: the engine starting or stopping.
    private var listening: CoroutineScope? = null

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            getString(R.string.key_powered_on) -> updateState()
        }
    }

    // Called when your app can update your tile.
    override fun onStartListening() {
        updateState()

        listening?.cancel()
        listening = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch {
                AppEvents.flow.collect { event ->
                    if (event == AppEvent.ServiceStarted || event == AppEvent.ServiceStopped) updateState()
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(this)

        super.onStartListening()
    }

    // Called when your app can no longer update your tile.
    override fun onStopListening() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    private fun isEffectEnabled(): Boolean {
        return (isRootless() && BaseAudioProcessorService.activeServices > 0) ||
                (!isRootless() && preferences.get<Boolean>(R.string.key_powered_on))
    }

    private fun updateState() {
        qsTile?.let { tile ->
            tile.state = if(isEffectEnabled()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    // Called when the user taps on your tile in an active or inactive state.
    // startActivityAndCollapse(Intent) is deprecated in favor of the PendingIntent overload,
    // but that overload only exists on API 34+ -- the Intent fallback below is still required
    // for older devices, where it's the only startActivityAndCollapse that exists.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        val toggled = qsTile?.let { it.state != Tile.STATE_ACTIVE } ?: return
        toggleEnginePower(toggled) { intent ->
            val pending = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE);

            // If projection permission request needs to be shown, collapse status bar
            if (isRootless() && app.mediaProjectionStartIntent == null && !hasProjectMediaAppOp() && !SdkCheck.isVanillaIceCream) {
                if(SdkCheck.isUpsideDownCake)
                    startActivityAndCollapse(pending)
                else
                    startActivityAndCollapse(intent)
            }
            else
                startActivity(intent)
        }
    }
}