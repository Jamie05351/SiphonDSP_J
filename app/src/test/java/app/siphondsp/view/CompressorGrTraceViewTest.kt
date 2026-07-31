package app.siphondsp.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CompressorGrTraceView has no touch interaction -- its only real regression risk is the
 * hoisted-to-fields Path reuse (rewind() instead of new Path() per frame) introduced in
 * Phase 3. This drives enough pushFrame() calls to wrap the 300-sample ring buffer at
 * least once and draws onto a real software Canvas, so a corrupted/stale Path would show
 * up as a crash here rather than only on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompressorGrTraceViewTest {
    @Test
    fun pushFrameAndDrawSurviveARingBufferWraparound() {
        val view = CompressorGrTraceView(ApplicationProvider.getApplicationContext())
        view.thresholdDb = -12f
        view.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 400, 150)
        val bitmap = Bitmap.createBitmap(400, 150, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 350 frames > the 300-sample buffer, forcing at least one full wraparound.
        for (i in 0 until 350) {
            val inputDb = -40f + (i % 60)
            val outputDb = inputDb - 5f
            val gainReductionDb = (i % 20).toFloat()
            view.pushFrame(inputDb, outputDb, gainReductionDb)
            view.draw(canvas)
        }

        view.reset()
        view.draw(canvas)
    }
}
