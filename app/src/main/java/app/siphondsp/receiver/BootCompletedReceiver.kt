package app.siphondsp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.siphondsp.R
import app.siphondsp.activity.EngineLauncherActivity
import app.siphondsp.service.RootAudioProcessorService
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import app.siphondsp.utils.isRoot
import app.siphondsp.utils.isRootless
import app.siphondsp.utils.notifications.ServiceNotificationHelper
import app.siphondsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber


class BootCompletedReceiver : BroadcastReceiver(), KoinComponent {
    private val preferences: Preferences.App by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED)
            return

        if(isRootless()) {
            if (!preferences.get<Boolean>(R.string.key_autostart_prompt_at_boot))
                return

            if(Settings.canDrawOverlays(context) && context.hasProjectMediaAppOp()) {
                Timber.i("Preconditions for a silent auto-start met")
                context.startActivity(Intent(context, EngineLauncherActivity::class.java).apply {
                    // No FLAG_ACTIVITY_MULTIPLE_TASK: that flag forces a brand-new task on every
                    // single call, never reusing one that's already running. EngineLauncherActivity
                    // is a one-shot boot-time helper -- there's never a legitimate reason to want
                    // more than one instance of it alive at once -- and BOOT_COMPLETED fires once
                    // per device boot (once per ignition cycle in a car), so MULTIPLE_TASK here
                    // guaranteed a fresh task every single boot, accumulating in Recent Apps over
                    // many ignition cycles regardless of whether excludeFromRecents (declared both
                    // here and in the manifest) is honored by the OS build. Dropping it lets
                    // Android's normal task-affinity matching reuse any still-alive instance
                    // instead of always creating a new one.
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                })
                return
            }

            ServiceNotificationHelper.pushPermissionPromptNotification(context)
        }
        else if(isRoot()) {
            // Root version: if enhanced processing mode is on, we need to start the service manually
            if(preferences.get<Boolean>(R.string.key_audioformat_enhanced_processing) &&
                !preferences.get<Boolean>(R.string.key_audioformat_processing)) {

                /*
                    FIXME: When targetting Android 15+, we are not allowed to start a
                           media_playback/media_projection foreground service from a BOOT_COMPLETED receiver.

                           Possible solutions:
                            - Also use EngineLauncherActivity for this.
                              Downside: requires SYSTEM_ALERT_WINDOW permission for the root build
                            - Better: Use the special use FGS type instead of media_playback for the root service.

                           Ref: https://developer.android.com/about/versions/15/behavior-changes-15#fgs-sysalert
                 */
                RootAudioProcessorService.startServiceEnhanced(context)
            }
            else if(preferences.get<Boolean>(R.string.key_audioformat_processing))
                RootAudioProcessorService.updateLegacyMode(context, true)
        }
    }
}