package app.siphondsp.compose.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherGeometryTest {
    @Test
    fun paddedLayoutFitsWidthLimitedWindows() {
        for (width in listOf(360f, 800f, 1280f)) {
            val geometry = launcherGeometry(width, 480f)
            assertTrue("Original tile row must fit at $width dp", geometry.designWidth >= DesignW)
            assertEquals("15 dp at each edge", width - 30f,
                geometry.designWidth * geometry.scale, 0.001f)
            assertTrue(DesignH * geometry.scale <= 480f)
        }
    }

    @Test
    fun notificationBarDoesNotCreateSideLetterboxing() {
        val geometry = launcherGeometry(1280f, 417f)
        assertEquals(417f, DesignH * geometry.scale, 0.001f)
        assertEquals(1250f, geometry.designWidth * geometry.scale, 0.001f)
        assertTrue(geometry.designWidth > DesignW)
    }
}
