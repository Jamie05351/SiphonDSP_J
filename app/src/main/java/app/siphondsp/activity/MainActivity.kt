package app.siphondsp.activity

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.view.HapticFeedbackConstants
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import app.siphondsp.BuildConfig
import app.siphondsp.R
import app.siphondsp.databinding.ActivityDspMainBinding
import app.siphondsp.databinding.ContentMainBinding
import app.siphondsp.fragment.DspFragment
import app.siphondsp.fragment.LibraryLoadErrorFragment
import app.siphondsp.service.BaseAudioProcessorService
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.SdkCheck
import app.siphondsp.utils.extensions.ContextExtensions.check
import app.siphondsp.utils.extensions.ContextExtensions.getAppName
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.restoreDspSettings
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import app.siphondsp.utils.extensions.ContextExtensions.showYesNoAlert
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.extensions.PermissionExtensions.hasDumpPermission
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import app.siphondsp.utils.extensions.PermissionExtensions.hasRecordPermission
import app.siphondsp.utils.isRootless
import app.siphondsp.utils.sdkAbove
import timber.log.Timber
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.math.abs


class MainActivity : BaseActivity() {
    /* UI bindings */
    lateinit var binding: ActivityDspMainBinding
    private lateinit var bindingContent: ContentMainBinding
    private lateinit var dspFragment: DspFragment

    /* Rootless version */
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var capturePermissionLauncher: ActivityResultLauncher<Intent>

    private var hasLoadFailed = false

    private var processorService: BaseAudioProcessorService? = null
    private var processorServiceBound: Boolean = false

    private val processorServiceConnection by lazy {
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                Timber.d("Service connected")

                processorService = (service as BaseAudioProcessorService.LocalBinder).service
                processorServiceBound = true

                if (isRootless())
                    setPowerUi(true)
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                Timber.d("Service disconnected")

                processorService = null
                processorServiceBound = false
            }
        }
    }

    private var broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SERVICE_STOPPED -> {
                    if(isRootless())
                        setPowerUi(false)
                }
                Constants.ACTION_SERVICE_STARTED -> {
                    if(isRootless())
                        setPowerUi(true)
                }
            }
        }
    }

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.let {
            hasLoadFailed = it.getBoolean(STATE_LOAD_FAILED)
        }

        val firstBoot = prefsVar.get<Boolean>(R.string.key_first_boot)

        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!
        binding = ActivityDspMainBinding.inflate(layoutInflater)
        bindingContent = ContentMainBinding.inflate(layoutInflater)

        val check = applicationContext.check()
        if(check != 0) {
            toast("($check) Cannot launch application. Please re-download the latest version from the Google Play or the official GitHub project site.")
            Timber.e(UnsupportedOperationException("Launch error $check; package=${packageName}; app_name=${getAppName()}"))
            quitGracefully()
            return
        }

        // Setup views
        setContentView(binding.root)
        // Both arts draw their own power button (PowerHotspot paints its lit patch); no LED dot.
        binding.powerLed.isVisible = false
        setSupportActionBar(binding.toolbar)

        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setHomeButtonEnabled(true)
        actionBar?.setDisplayShowTitleEnabled(true)
        if(firstBoot) {
            // Set timer for translation notice (+30m)
            prefsVar.set(R.string.key_snooze_translation_notice, (System.currentTimeMillis() / 1000L) + 1800L)
        }

        // Load main fragment
        dspFragment = DspFragment.newInstance()
        // The power / cog / overflow overlay belongs to the artwork page only: it slides with that
        // page during a swipe (the head unit's power-on patch is opaque, so a fixed overlay would
        // paint over the incoming settings page) and is hidden once the page is fully off screen.
        dspFragment.onHomePageOffset = { offset ->
            binding.homeChrome.translationX = offset
            binding.homeChrome.isVisible = abs(offset) < binding.homeChrome.width.coerceAtLeast(1)
        }
        dspFragment.onSettingsClick = {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        dspFragment.onMoreClick = { anchor ->
            showOverflowMenu(anchor)
        }
        if(!hasLoadFailed)
            supportFragmentManager.beginTransaction()
                .replace(R.id.dsp_fragment_container, dspFragment)
                .commit()
        else
            showLibraryLoadError()

        // Rootless: Check permissions and launch onboarding if required
        if(SdkCheck.isQ && isRootless() && (!hasDumpPermission() || !hasRecordPermission())) {
            Timber.i("Launching onboarding (first boot: $firstBoot)")

            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                putExtra(OnboardingActivity.EXTRA_FIX_PERMS, !firstBoot)
            })
            this.finish()
            return
        }

        // The front page is one piece of artwork (see fragment_dsp_page_shortcuts.xml). PEQ /
        // Gains & Delay / Compressor / Crossovers / All-pass are touch areas on it; the settings
        // cog and the "more" overflow (revert / blocklist / measurement / signal generator /
        // native truth) are transparent-backed icons in the overlay, and the power button is a
        // hotspot over the artwork's own button.
        // Settings and More are now proper seventh-row home tiles. Keep the legacy overlay
        // views in the XML for binding/source compatibility, but make them non-visual/non-clickable.
        binding.actionSettings.isVisible = false
        binding.actionOverflow.isVisible = false

        IntentFilter(Constants.ACTION_SERVICE_STOPPED).apply {
            addAction(Constants.ACTION_SERVICE_STARTED)
            addAction(Constants.ACTION_PRESET_LOADED)
            registerLocalReceiver(broadcastReceiver, this)
        }

        // The power button is the home screen's PowerNode (Compose). PowerHotspot stays as the
        // power state's holder (isToggled) but is no longer drawn or tapped.
        binding.powerToggle.toggleOnClick = false
        binding.powerToggle.isVisible = false
        dspFragment.onPowerClick = ::togglePower

        if (SdkCheck.isQ && isRootless()) {
            capturePermissionLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK && isRootless()) {
                    app.mediaProjectionStartIntent = result.data
                    setPowerUi(true)
                    RootlessAudioProcessorService.start(this, result.data)
                } else {
                    setPowerUi(false)
                }
            }
        }

        // Rootless: request capture permission instantly, if redirected from onboarding
        if (SdkCheck.isQ && isRootless()) {
            if (intent.getBooleanExtra(EXTRA_FORCE_SHOW_CAPTURE_PROMPT, false)) {
                requestCapturePermission()
            }
        }

        sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))

        showAndroid15Alert()
    }

    /** The front page's 3-dot menu: same items and actions the old bottom bar's overflow had. */
    private fun showOverflowMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_main_bottom, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_blocklist -> {
                    startActivity(Intent(this, BlocklistActivity::class.java))
                    true
                }
                R.id.action_revert -> {
                    this.showYesNoAlert(
                        R.string.revert_confirmation_title,
                        R.string.revert_confirmation
                    ) {
                        if(it)
                            restoreDspSettings()
                    }
                    true
                }
                R.id.action_measurement -> {
                    startActivity(Intent(this, MeasurementCaptureActivity::class.java))
                    true
                }
                R.id.action_signal_generator -> {
                    startActivity(Intent(this, SignalGeneratorActivity::class.java))
                    true
                }
                R.id.action_native_truth -> {
                    startActivity(Intent(this, NativeTruthActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        super.onSaveInstanceState(outState, outPersistentState.apply {
            putBoolean(STATE_LOAD_FAILED, hasLoadFailed)
        })
    }

    override fun onStart() {
        super.onStart()
        bindProcessorService()
    }

    override fun onStop() {
        super.onStop()
        unbindProcessorService()
    }

    override fun onPause() {
        super.onPause()
        prefsVar.set(R.string.key_is_activity_active, false)
    }

    override fun onDestroy() {
        unregisterLocalReceiver(broadcastReceiver)

        try {
            if (processorService != null && processorServiceBound)
                unbindService(processorServiceConnection)
        }
        catch (_: Exception) {}

        processorService = null
        processorServiceBound = false

        prefsVar.set(R.string.key_is_activity_active, false)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        prefsVar.set(R.string.key_is_activity_active, true)

        if(isRootless())
            setPowerUi(processorService != null)
    }

    /** The power node was tapped: start or stop the engine for this build flavour. */
    private fun togglePower() {
        sdkAbove(Build.VERSION_CODES.R) {
            binding.root.performHapticFeedback(
                if(binding.powerToggle.isToggled)
                    HapticFeedbackConstants.CONFIRM
                else
                    HapticFeedbackConstants.REJECT
            )
        }

        if(SdkCheck.isQ && isRootless()) {
            if (binding.powerToggle.isToggled) {
                // Currently on, let's turn it off
                RootlessAudioProcessorService.stop(this@MainActivity)
                setPowerUi(false)
            } else {
                // Currently off, let's turn it on
                requestCapturePermission()
            }
        }
    }

    /** Updates the physical-looking power control, and greys the front page out while off. */
    private fun setPowerUi(on: Boolean) {
        binding.powerToggle.isToggled = on
        if (::dspFragment.isInitialized) {
            dspFragment.setPowerState(on)
        }
    }

    private fun showAndroid15Alert() {
        if(!isRootless() || !SdkCheck.isVanillaIceCream)
            return

        if(!hasProjectMediaAppOp()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.android_15_screenshare_warning_title)
                .setMessage(R.string.android_15_screenshare_keyguard_warning)
                .setCancelable(false)
                .setPositiveButton(R.string.continue_action) { _, _ ->
                    startActivity(Intent(this, OnboardingActivity::class.java).apply {
                        putExtra(OnboardingActivity.EXTRA_FIX_PERMS, false)
                    })
                    this.finish()
                }
                .show()
            return
        }

        if(prefsVar.get<Boolean>(R.string.key_android15_screenrecord_restriction_seen))
            return

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
                }
            )
            .setPositiveButton(R.string.tutorial) { dialog, _ ->
                prefsVar.set(R.string.key_android15_screenrecord_restriction_seen, checkBox.isChecked)
                startActivity(Intent(Intent.ACTION_VIEW, "https://youtu.be/rVM13aY2rwU?t=31".toUri()))
            }
            .setNegativeButton(R.string.close) { dialog, _ ->
                prefsVar.set(R.string.key_android15_screenrecord_restriction_seen, checkBox.isChecked)
                dialog.dismiss()
            }
            .show()
    }

    private fun showLibraryLoadError() {
        if(DEBUG_IGNORE_MISSING_LIBRARY)
            return

        hasLoadFailed = true

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.dsp_fragment_container, LibraryLoadErrorFragment.newInstance())
            .commit()

        setPowerUi(false)
        binding.toolbar.isVisible = false
        // No artwork page behind the overlay in the load-error state.
        binding.homeChrome.isVisible = false
    }

    private fun bindProcessorService() {
        if (isRootless()) {
            sdkAbove(Build.VERSION_CODES.Q) {
                Intent(this, RootlessAudioProcessorService::class.java).also { intent ->
                    val ret = bindService(intent, processorServiceConnection, 0)
                    // Service not active
                    if (!ret)
                        requestCapturePermission()
                }
            }
        }
    }

    private fun unbindProcessorService() {
        try {
            unbindService(processorServiceConnection)
        }
        catch (ex: IllegalArgumentException) {
            Timber.d("Failed to unbind service connection. Not registered?")
            Timber.i(ex)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun requestCapturePermission() {
        if(app.mediaProjectionStartIntent != null && isRootless() && !SdkCheck.isVanillaIceCream) {
            setPowerUi(true)
            RootlessAudioProcessorService.start(this, app.mediaProjectionStartIntent)
            return
        }
        try {
            capturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
        catch (ex: ActivityNotFoundException) {
            toast(getString(R.string.error_projection_api_missing))
            Timber.e(ex)
        }
    }

    private fun makeSnackbar(text: String, duration: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return Snackbar.make(findViewById(android.R.id.content), text, duration)
    }

    private fun quitGracefully() {
        Timer().schedule(2000){
            this@MainActivity.finishAndRemoveTask()
        }
    }



    companion object {
        const val EXTRA_FORCE_SHOW_CAPTURE_PROMPT = "ForceShowCapturePrompt"

        private val DEBUG_IGNORE_MISSING_LIBRARY = BuildConfig.DEBUG
        private const val STATE_LOAD_FAILED = "LoadFailed"
    }
}
