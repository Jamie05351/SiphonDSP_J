package app.siphondsp.receiver

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import app.siphondsp.session.rootless.RootlessSessionDatabase
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber

class SessionReceiver : BroadcastReceiver() {

    @SuppressLint("BinaryOperationInTimber")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) {
            return
        }

        if(intent.getIntExtra(RootlessSessionDatabase.EXTRA_IGNORE, 0) == 1) {
            Timber.d("Control close intent ignored")
            return
        }

        Timber.i(
            "Action: ${intent.action}; " +
                    "session: ${intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)}; " +
                    "package ${intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)}")

        context.sendLocalBroadcast(
            Intent(Constants.ACTION_SESSION_CHANGED)
                .apply {
                    putExtras(intent)
                }
        )
    }
}