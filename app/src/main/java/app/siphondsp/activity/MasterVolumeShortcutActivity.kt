package app.siphondsp.activity

import android.app.Activity
import android.os.Bundle
import app.siphondsp.model.MasterVolume
import timber.log.Timber

/**
 * The DSP master volume as launchable shortcuts, for a head unit's button remapper (KSW Toolkit's
 * "Start App") to fire straight from the steering-wheel buttons, with no Tasker in between. Each
 * of the three subclasses is its own launcher entry ("DSP volume +", "DSP volume −", "DSP mute"),
 * so an app picker can choose it; launching one does its step, shows the usual "DSP volume" toast
 * and finishes at once. It never draws (Theme.NoDisplay, finished in onCreate), runs in its own
 * empty task and stays out of Recents, so whatever was on screen stays on screen.
 *
 * Exported with no permission on purpose, like the volume broadcast: the most any caller can do is
 * move the volume within −80..0 dB or toggle mute.
 */
abstract class MasterVolumeShortcutActivity : Activity() {
    protected abstract fun apply(): MasterVolume.State?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = apply()
        if (state == null) Timber.e("${javaClass.simpleName}: could not save the volume")
        else MasterVolume.show(this, state)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

class MasterVolumeUpActivity : MasterVolumeShortcutActivity() {
    override fun apply() = MasterVolume.step(this, 1)
}

class MasterVolumeDownActivity : MasterVolumeShortcutActivity() {
    override fun apply() = MasterVolume.step(this, -1)
}

class MasterVolumeMuteActivity : MasterVolumeShortcutActivity() {
    override fun apply() = MasterVolume.setMuted(this, null)
}
