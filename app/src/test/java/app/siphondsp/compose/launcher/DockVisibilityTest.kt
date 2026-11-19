package app.siphondsp.compose.launcher

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h480dp-land-mdpi")
class DockVisibilityTest {
    @Test
    fun coveringDockRetainsStateButRemovesDrawingAndTouch() {
        val covered = mutableStateOf(false)
        var created = 0
        var disposed = 0
        var observed = 0
        var taps = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent {
                Box(Modifier.fillMaxSize().background(Color.Blue)) {
                    Box(Modifier.dockVisibility(covered.value).size(100.dp)
                        .background(Color.Red).clickable { taps++ }) {
                        val identity = remember { ++created }
                        SideEffect { observed = identity }
                        DisposableEffect(Unit) { onDispose { disposed++ } }
                    }
                }
            }
            val root = controller.get().window.decorView.findViewById<View>(android.R.id.content)
            fun settle() { repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) } }
            fun tap() {
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(0, 10, action, 50f, 50f, 0)
                    root.dispatchTouchEvent(event)
                    event.recycle()
                }
                settle()
            }
            settle()
            assertEquals(android.graphics.Color.RED, root.drawToBitmap().getPixel(50, 50))
            tap()
            assertEquals(1, taps)
            covered.value = true
            settle()
            assertEquals(android.graphics.Color.BLUE, root.drawToBitmap().getPixel(50, 50))
            tap()
            assertEquals("Hidden dock cannot receive taps", 1, taps)
            assertEquals(0, disposed)
            covered.value = false
            settle()
            assertEquals(android.graphics.Color.RED, root.drawToBitmap().getPixel(50, 50))
            assertEquals("Dock animation state must survive menu round trips", 1, observed)
            assertEquals(1, created)
            assertEquals(0, disposed)
            tap()
            assertEquals(2, taps)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
