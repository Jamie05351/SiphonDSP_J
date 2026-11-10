package app.siphondsp.receiver

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import app.siphondsp.session.rootless.RootlessSessionDatabase
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
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

        AppEvents.send(
            AppEvent.AudioEffectSessionChanged(
                sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR),
                packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME),
            )
        )
    }
}