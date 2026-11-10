package app.siphondsp.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Records the classes and methods the app's start-up and front page use, for
 * app/src/main/baseline-prof.txt. Runs on an API 33+ device or emulator (non-rooted).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    // includeInStartupProfile stays off: with it, this Macrobenchmark writes only a startup
    // profile (for dex layout) and no -baseline-prof.txt, which is the file the app ships.
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = false) {
        grantSetupPermissions()
        pressHome()
        startActivityAndWait()
        // With no engine running, MainActivity asks for the screen-capture permission: decline
        // it, so the front page itself is what gets drawn and recorded.
        dismissCapturePrompt()
        device.waitForIdle()
    }

    /**
     * What the setup wizard would grant (DUMP and PROJECT_MEDIA over ADB, then the runtime
     * permissions), so the app opens on the front page instead of the wizard.
     */
    private fun MacrobenchmarkScope.grantSetupPermissions() {
        for (command in listOf(
            "pm grant $PACKAGE android.permission.DUMP",
            "pm grant $PACKAGE android.permission.RECORD_AUDIO",
            "pm grant $PACKAGE android.permission.POST_NOTIFICATIONS",
            "appops set $PACKAGE PROJECT_MEDIA allow",
        )) {
            device.executeShellCommand(command)
        }
    }

    /**
     * Taps Cancel on SystemUI's MediaProjection dialog, if it showed up. The match is the dialog's
     * own button, not just any SystemUI view: the status and navigation bars are SystemUI too,
     * and a Back meant for the dialog would otherwise leave the app. Then waits for the front
     * page to be the app's window again.
     */
    private fun MacrobenchmarkScope.dismissCapturePrompt() {
        val cancel = device.wait(
            Until.findObject(By.pkg(SYSTEM_UI).text(Pattern.compile("(?i)cancel"))),
            PROMPT_TIMEOUT_MS,
        )
        cancel?.click()
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), APP_TIMEOUT_MS)) {
            "The app's window isn't showing after the capture prompt"
        }
    }

    private companion object {
        const val PACKAGE = "app.siphondsp"
        const val SYSTEM_UI = "com.android.systemui"
        const val PROMPT_TIMEOUT_MS = 5_000L
        const val APP_TIMEOUT_MS = 10_000L
    }
}
