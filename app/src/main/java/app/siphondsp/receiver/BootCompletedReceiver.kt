package app.siphondsp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.siphondsp.R
import app.siphondsp.activity.EngineLauncherActivity
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
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
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                })
                return
            }

            ServiceNotificationHelper.pushPermissionPromptNotification(context)
        }
    }
}