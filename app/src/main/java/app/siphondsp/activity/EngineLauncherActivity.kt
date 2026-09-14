package app.siphondsp.activity

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import app.siphondsp.service.RootAudioProcessorService
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.SdkCheck
import app.siphondsp.utils.isRoot
import app.siphondsp.utils.sdkAbove
import timber.log.Timber

/**
 * Helper activity to launch the rootless foreground service
 * from the TileService
 */
class EngineLauncherActivity : BaseActivity() {
    // Must be registered unconditionally before the activity reaches STARTED, so this
    // happens once here rather than inside launchEngine(), which onNewIntent() can re-run.
    private val capturePermissionLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            app.mediaProjectionStartIntent = result.data
            Timber.d("Using new projection token to start service")

            RootlessAudioProcessorService.start(this, result.data)
        }
        finish()
    }

    override val disableAppTheme: Boolean = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Without FLAG_ACTIVITY_MULTIPLE_TASK, a boot that reuses an already-alive
        // instance of this task delivers here instead of onCreate() -- without this
        // override the engine start logic would silently never run on that boot.
        launchEngine()
    }

    private fun launchEngine() {
        if (isRoot()) {
            // Root
            RootAudioProcessorService.startServiceEnhanced(this)
            finish()
            return
        }

        sdkAbove(Build.VERSION_CODES.Q) {
            // If projection token available, start immediately
            // Note: Android >=14 doesn't allow token reuse
            if(app.mediaProjectionStartIntent != null && !SdkCheck.isUpsideDownCake) {
                Timber.d("Reusing old projection token to start service")
                RootlessAudioProcessorService.start(this, app.mediaProjectionStartIntent)
                finish()
                return
            }

            setFinishOnTouchOutside(false)

            getSystemService<MediaProjectionManager>()
                ?.createScreenCaptureIntent()
                ?.let(capturePermissionLauncher::launch)
        }
    }
}