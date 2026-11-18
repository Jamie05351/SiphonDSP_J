package app.siphondsp.session.rootless

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.session.dump.data.ISessionPolicyInfoDump
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRecordingPolicyManagerTest {
    @Test fun earlyMuteRequiresAnExplicitAllowedPolicyForTheSameOwner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkg = "com.example.player"
        val uid = 12_345
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; this.uid = uid }
        })
        val manager = SessionRecordingPolicyManager(context)
        fun update(allowed: Boolean?) {
            manager.update(object : ISessionPolicyInfoDump {
                override val capturePermissionLog = hashMapOf<String, Boolean>().apply {
                    if (allowed != null) put(pkg, allowed)
                }
            })
        }

        assertFalse(manager.isCaptureAllowed(uid, pkg))
        update(true)
        assertTrue(manager.isCaptureAllowed(uid, pkg))
        assertFalse(manager.isCaptureAllowed(uid + 1, pkg))
        assertFalse(manager.isCaptureAllowed(uid, "com.other.player"))
        update(false)
        assertFalse(manager.isCaptureAllowed(uid, pkg))
        update(true)
        assertTrue(manager.isCaptureAllowed(uid, pkg))
        update(null)
        assertFalse(manager.isCaptureAllowed(uid, pkg))
        update(true)
        manager.clearSessions()
        assertFalse(manager.isCaptureAllowed(uid, pkg))
    }
}
