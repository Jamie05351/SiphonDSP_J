package app.siphondsp.compose.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherHostTest {
    @Test
    fun homeWhileResumedRunsAtOnce() {
        val host = LauncherHost()
        var resets = 0
        host.homePressed = { resets++ }
        host.onHomePressed()
        assertEquals(1, resets)
    }

    @Test
    fun homeWhilePausedIsDeliveredOnResumeOnce() {
        val host = LauncherHost()
        var resets = 0
        host.homePressed = { resets++ }
        host.homePressed = null // paused behind another app
        host.onHomePressed()
        assertEquals(0, resets)
        host.homePressed = { resets++ } // resumed
        assertEquals(1, resets)
        host.homePressed = null
        host.homePressed = { resets++ } // a later resume with no Home press
        assertEquals(1, resets)
    }
}
