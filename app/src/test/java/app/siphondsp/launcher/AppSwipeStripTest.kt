package app.siphondsp.launcher

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The edge strips' gesture, at mdpi so a dp is a pixel: a swipe needs 60 px sideways. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h480dp-land-mdpi")
class AppSwipeStripTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val swipes = mutableListOf<Boolean>()
    private val strip = AppSwipe.strip(context) { leftwards -> swipes += leftwards }

    @Test
    fun aSidewaysDragSwitchesOnceTheWayItWent() {
        drag(strip, 10f to 200f, 40f to 205f, 90f to 210f, 200f to 215f)
        assertEquals("one switch, rightwards", listOf(false), swipes)

        drag(strip, 1270f to 200f, 1150f to 205f)
        assertEquals("then one leftwards", listOf(false, true), swipes)
    }

    @Test
    fun aDragDownTheEdgeOrAShortOneDoesNot() {
        drag(strip, 10f to 100f, 70f to 300f)
        drag(strip, 10f to 200f, 50f to 200f)
        assertEquals(emptyList<Boolean>(), swipes)
    }

    /** One touch through [points]: down at the first, moves through the rest, up at the last. */
    private fun drag(view: View, vararg points: Pair<Float, Float>) {
        points.forEachIndexed { i, (x, y) ->
            val action = if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            MotionEvent.obtain(0, i * 16L, action, x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        }
        val (x, y) = points.last()
        MotionEvent.obtain(0, points.size * 16L, MotionEvent.ACTION_UP, x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
    }
}
