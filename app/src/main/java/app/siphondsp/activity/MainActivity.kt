package app.siphondsp.activity

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.HapticFeedbackConstants
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.siphondsp.R
import app.siphondsp.compose.home.HomeRoute
import app.siphondsp.compose.nav.AppNavHost
import app.siphondsp.compose.nav.Routes
import app.siphondsp.compose.screens.AppCompatRequest
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.service.BaseAudioProcessorService
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.utils.SdkCheck
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.PermissionExtensions.hasDumpPermission
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import app.siphondsp.utils.extensions.PermissionExtensions.hasRecordPermission
import app.siphondsp.utils.sdkAbove
import app.siphondsp.view.DspDestination
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The app's one main activity: the front page and every DSP workspace, as Compose Navigation
 * destinations ([AppNavHost]). It owns the engine: the processor service binding, the capture
 * permission, and the power state the front page shows.
 */
class MainActivity : BaseActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var processorService: BaseAudioProcessorService? = null
    private var powerOn by mutableStateOf(false)

    private val capturePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && SdkCheck.isQ) {
            app.mediaProjectionStartIntent = result.data
            powerOn = true
            RootlessAudioProcessorService.start(this, result.data)
        } else {
            powerOn = false
        }
    }

    private val processorServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Timber.d("Service connected")
            processorService = (service as BaseAudioProcessorService.LocalBinder).service
            powerOn = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Timber.d("Service disconnected")
            processorService = null
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!

        // Where the graph starts: the setup wizard while permissions are missing, the
        // "incompatible app" screen when opened for it, otherwise the front page.
        val appCompatRequest = AppCompatRequest.from(intent)
        val needsSetup = SdkCheck.isQ && (!hasDumpPermission() || !hasRecordPermission())
        val firstBoot = prefsVar.get<Boolean>(R.string.key_first_boot)
        val startDestination = when {
            needsSetup -> Routes.ONBOARDING_ROUTE.also { Timber.i("Launching onboarding (first boot: $firstBoot)") }
            appCompatRequest != null -> Routes.APP_COMPATIBILITY
            else -> Routes.HOME
        }
        // Setup and the app-compatibility screen start the engine themselves (or not at all).
        autoStartEngine = startDestination == Routes.HOME

        // Not Main.immediate: handled after the service's sending call returns.
        lifecycleScope.launch(Dispatchers.Main) {
            AppEvents.flow.collect { event ->
                when (event) {
                    AppEvent.ServiceStarted -> powerOn = true
                    AppEvent.ServiceStopped -> powerOn = false
                    else -> Unit
                }
            }
        }

        setContent {
            BmwDspTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                // The workspaces are laid out for the head unit's full screen, with no system bars.
                val fullScreen = route != null && DspDestination.entries.any { route.startsWith(it.route) }
                DisposableEffect(fullScreen) {
                    setSystemBarsHidden(fullScreen)
                    onDispose {}
                }
                navController = nav
                AppNavHost(
                    nav = nav,
                    startDestination = startDestination,
                    appCompatRequest = appCompatRequest,
                    setupFixPerms = !firstBoot,
                    onExit = ::finish,
                    onSetupFinished = ::onSetupFinished,
                ) {
                    HomeRoute(powered = powerOn, onTogglePower = ::togglePower)
                }
            }
        }

        if (startDestination == Routes.HOME) showAndroid15Alert()
    }

    private var navController: NavHostController? = null

    /** False while setup or the app-compatibility screen runs: no capture prompt on start. */
    private var autoStartEngine = true

    /** The first setup is done: on to the front page, and ask for the capture permission. */
    private fun onSetupFinished() {
        prefsVar.set(R.string.key_first_boot, false)
        navController?.navigate(Routes.HOME) {
            popUpTo(Routes.ONBOARDING_ROUTE) { inclusive = true }
            launchSingleTop = true
        }
        autoStartEngine = true
        if (SdkCheck.isQ) requestCapturePermission()
    }

    /** Back to the setup wizard (a permission it grants is gone), in place of the front page. */
    private fun openSetup() {
        autoStartEngine = false
        navController?.navigate(Routes.onboarding()) {
            popUpTo(Routes.HOME) { inclusive = true }
        }
    }

    private var systemBarsHidden = false

    private fun setSystemBarsHidden(hidden: Boolean) {
        systemBarsHidden = hidden
        WindowCompat.setDecorFitsSystemWindows(window, !hidden)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (hidden) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A dialog or the shade can bring the bars back; a workspace hides them again.
        if (hasFocus && systemBarsHidden) {
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        bindProcessorService()
    }

    override fun onStop() {
        super.onStop()
        unbindProcessorService()
    }

    override fun onResume() {
        super.onResume()
        prefsVar.set(R.string.key_is_activity_active, true)
        powerOn = processorService != null
    }

    override fun onPause() {
        super.onPause()
        prefsVar.set(R.string.key_is_activity_active, false)
    }

    override fun onDestroy() {
        prefsVar.set(R.string.key_is_activity_active, false)
        super.onDestroy()
    }

    /** The power node was tapped: start or stop the engine. */
    private fun togglePower() {
        sdkAbove(Build.VERSION_CODES.R) {
            window.decorView.performHapticFeedback(
                if (powerOn) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT,
            )
        }
        if (!SdkCheck.isQ) return
        if (powerOn) {
            RootlessAudioProcessorService.stop(this)
            powerOn = false
        } else {
            requestCapturePermission()
        }
    }

    private fun bindProcessorService() {
        sdkAbove(Build.VERSION_CODES.Q) {
            val bound = bindService(Intent(this, RootlessAudioProcessorService::class.java), processorServiceConnection, 0)
            // Service not running.
            if (!bound && autoStartEngine) requestCapturePermission()
        }
    }

    private fun unbindProcessorService() {
        try {
            unbindService(processorServiceConnection)
        } catch (ex: IllegalArgumentException) {
            Timber.d("Failed to unbind service connection. Not registered?")
            Timber.i(ex)
        }
        processorService = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun requestCapturePermission() {
        if (app.mediaProjectionStartIntent != null && !SdkCheck.isVanillaIceCream) {
            powerOn = true
            RootlessAudioProcessorService.start(this, app.mediaProjectionStartIntent)
            return
        }
        try {
            capturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (ex: ActivityNotFoundException) {
            toast(getString(R.string.error_projection_api_missing))
            Timber.e(ex)
        }
    }

    private fun showAndroid15Alert() {
        if (!SdkCheck.isVanillaIceCream) return

        if (!hasProjectMediaAppOp()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.android_15_screenshare_warning_title)
                .setMessage(R.string.android_15_screenshare_keyguard_warning)
                .setCancelable(false)
                .setPositiveButton(R.string.continue_action) { _, _ -> openSetup() }
                .show()
            return
        }

        if (prefsVar.get<Boolean>(R.string.key_android15_screenrecord_restriction_seen)) return

        val checkBox = CheckBox(this).apply {
            text = getString(R.string.never_show_warning_again)
            isChecked = false
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.android_15_screenshare_warning_title)
            .setMessage(R.string.android_15_screenshare_warning)
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 32, 48, 16)
                    addView(checkBox)
                },
            )
            .setPositiveButton(R.string.tutorial) { _, _ ->
                prefsVar.set(R.string.key_android15_screenrecord_restriction_seen, checkBox.isChecked)
                startActivity(Intent(Intent.ACTION_VIEW, "https://youtu.be/rVM13aY2rwU?t=31".toUri()))
            }
            .setNegativeButton(R.string.close) { dialog, _ ->
                prefsVar.set(R.string.key_android15_screenrecord_restriction_seen, checkBox.isChecked)
                dialog.dismiss()
            }
            .show()
    }

}
