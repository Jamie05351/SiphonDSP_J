package app.siphondsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSwipeTest {
    @Test
    fun readsTheFrontAppAndTheOpenOnesFromAnAndroid12Dump() {
        val tasks = AppSwipe.parse(ANDROID_12)
        assertEquals("com.waze", tasks.front)
        assertEquals(setOf("com.waze", "com.spotify.music", "app.siphondsp"), tasks.open)
    }

    @Test
    fun anAppNamedOnlyAsTheLastPausedOneIsNotOpen() {
        val dump = """
            * Task{8d1e0f2 #45 type=standard A=10112:com.waze U=0 visible=true mode=fullscreen translucent=false sz=1}
              mLastPausedActivity: ActivityRecord{2f4a1b9 u0 com.spotify.music/.MainActivity t44}
              * Hist #0: ActivityRecord{c33a7d1 u0 com.waze/.FreeMapAppActivity t45}
            topResumedActivity=ActivityRecord{c33a7d1 u0 com.waze/.FreeMapAppActivity t45}
        """.trimIndent()
        val tasks = AppSwipe.parse(dump)
        assertEquals(setOf("com.waze"), tasks.open)
        assertNull("nothing to swipe to", AppSwipe.swipeTarget(tasks))
    }

    @Test
    fun readsTheFrontAppFromAnOlderDump() {
        val dump = "  mResumedActivity: ActivityRecord{5b1c2a u0 com.spotify.music/.MainActivity t31}\n"
        assertEquals("com.spotify.music", AppSwipe.parse(dump).front)
    }

    @Test
    fun swipesToTheOtherAppOnlyWhileOneIsInFrontAndBothAreOpen() {
        val both = setOf("com.waze", "com.spotify.music")
        assertEquals("com.spotify.music", AppSwipe.swipeTarget(AppSwipe.Tasks("com.waze", both)))
        assertEquals("com.waze", AppSwipe.swipeTarget(AppSwipe.Tasks("com.spotify.music", both)))
        assertNull("the other isn't open", AppSwipe.swipeTarget(AppSwipe.Tasks("com.waze", setOf("com.waze"))))
        assertNull("neither is in front", AppSwipe.swipeTarget(AppSwipe.Tasks("app.siphondsp", both)))
        assertNull("nothing read", AppSwipe.swipeTarget(AppSwipe.Tasks(null, both)))
    }

    private companion object {
        val ANDROID_12 = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            Display #0 (activities from top to bottom):
              * Task{8d1e0f2 #45 type=standard A=10112:com.waze U=0 visible=true mode=fullscreen translucent=false sz=1}
                mLastPausedActivity: ActivityRecord{2f4a1b9 u0 com.spotify.music/.MainActivity t44}
                * Hist #0: ActivityRecord{c33a7d1 u0 com.waze/.FreeMapAppActivity t45}
              * Task{61b2e0a #44 type=standard A=10098:com.spotify.music U=0 visible=false mode=fullscreen translucent=false sz=1}
                * Hist #0: ActivityRecord{2f4a1b9 u0 com.spotify.music/.MainActivity t44}
              * Task{19ac3e4 #2 type=home U=0 visible=false mode=fullscreen translucent=false sz=1}
                * Hist #0: ActivityRecord{77de120 u0 app.siphondsp/.activity.MainActivity t2}
              ResumedActivity: ActivityRecord{c33a7d1 u0 com.waze/.FreeMapAppActivity t45}
            topResumedActivity=ActivityRecord{c33a7d1 u0 com.waze/.FreeMapAppActivity t45}
        """.trimIndent()
    }
}
