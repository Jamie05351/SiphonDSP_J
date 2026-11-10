package app.siphondsp.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods the app's start-up and front page use, for
 * app/src/main/baseline-prof.txt. Runs on an API 33+ device or emulator (non-rooted).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
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

    private fun MacrobenchmarkScope.dismissCapturePrompt() {
        // SystemUI's MediaProjection dialog; absent if the app didn't ask.
        val prompt = device.wait(Until.findObject(By.pkg("com.android.systemui")), PROMPT_TIMEOUT_MS)
        if (prompt != null) device.pressBack()
    }

    private companion object {
        const val PACKAGE = "app.siphondsp"
        const val PROMPT_TIMEOUT_MS = 5_000L
    }
}
