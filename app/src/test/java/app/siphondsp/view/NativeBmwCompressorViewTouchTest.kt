package app.siphondsp.view

import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives NativeBmwCompressorView's three-tier touch hit-test (threshold -> knee -> ratio)
 * with synthetic MotionEvents to confirm each drag mode only ever fires its own callback --
 * the isolation the tiered radii in CompressorGraphMath.pickDragMode are meant to guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeBmwCompressorViewTouchTest {
    private lateinit var view: NativeBmwCompressorView
    // Wide enough that the threshold and knee handles land well outside each other's hit
    // radii (34dp/26dp) -- a cramped view would make them ambiguous, since both sit close
    // together in dB-space for a mid-sized knee.
    private val width = 1200
    private val height = 600

    // Mirrors NativeBmwCompressorView's private padding/range constants (density=1 under
    // Robolectric's default mdpi config).
    private val minDb = -60f
    private val maxDb = 6f
    private val padLeft = 34f
    private val padTop = 14f
    private val padRight = 25f
    private val padBottom = 24f
    private val graphWidth get() = width - padLeft - padRight
    private val graphHeight get() = height - padTop - padBottom
    private fun x(db: Float) = padLeft + graphWidth * (db - minDb) / (maxDb - minDb)
    private fun y(db: Float) = padTop + graphHeight * (1f - (db - minDb) / (maxDb - minDb))

    private val thresholdDb = -12f
    private val ratio = 4f
    private val kneeDb = 6f
    private val makeupDb = 0f

    @Before
    fun setUp() {
        view = NativeBmwCompressorView(ApplicationProvider.getApplicationContext())
        view.thresholdDb = thresholdDb
        view.ratio = ratio
        view.kneeDb = kneeDb
        view.makeupDb = makeupDb
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun motionEvent(action: Int, x: Float, y: Float) = MotionEvent.obtain(0L, 0L, action, x, y, 0)

    private fun thresholdPixel(): Pair<Float, Float> {
        val output = CompressorGraphMath.outputFor(thresholdDb, thresholdDb, ratio, kneeDb, makeupDb, true)
        return x(thresholdDb) to y(output.coerceIn(minDb, maxDb))
    }

    private fun kneePixel(): Pair<Float, Float> {
        val (inputDb, outputDb) = CompressorGraphMath.kneeHandlePoint(thresholdDb, ratio, kneeDb, makeupDb, true)
        return x(inputDb) to y(outputDb.coerceIn(minDb, maxDb))
    }

    @Test
    fun kneeDragOnlyFiresOnKneeChanged() {
        var kneeCalls = 0
        var thresholdCalls = 0
        var ratioCalls = 0
        view.onKneeChanged = { kneeCalls++ }
        view.onThresholdChanged = { thresholdCalls++ }
        view.onRatioChanged = { ratioCalls++ }
        val (kx, ky) = kneePixel()

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, kx, ky))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, kx + 20f, ky))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, kx + 20f, ky))

        assertTrue("expected at least one onKneeChanged call", kneeCalls > 0)
        assertEquals(0, thresholdCalls)
        assertEquals(0, ratioCalls)
        assertNotEquals(kneeDb, view.kneeDb)
    }

    @Test
    fun thresholdDragOnlyFiresOnThresholdChanged() {
        var kneeCalls = 0
        var thresholdCalls = 0
        var ratioCalls = 0
        view.onKneeChanged = { kneeCalls++ }
        view.onThresholdChanged = { thresholdCalls++ }
        view.onRatioChanged = { ratioCalls++ }
        val (tx, ty) = thresholdPixel()

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, tx, ty))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, tx + 20f, ty))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, tx + 20f, ty))

        assertTrue("expected at least one onThresholdChanged call", thresholdCalls > 0)
        assertEquals(0, kneeCalls)
        assertEquals(0, ratioCalls)
        assertNotEquals(thresholdDb, view.thresholdDb)
    }

    @Test
    fun ratioDragOnlyFiresOnRatioChanged() {
        var kneeCalls = 0
        var thresholdCalls = 0
        var ratioCalls = 0
        view.onKneeChanged = { kneeCalls++ }
        view.onThresholdChanged = { thresholdCalls++ }
        view.onRatioChanged = { ratioCalls++ }
        // input ~5.5dB (far right, well outside the threshold/knee handle radii). ACTION_DOWN
        // lands exactly on the transfer curve at that input -- ratio-dragging now only starts
        // within curveHitRadiusPx of the curve itself, not anywhere on the graph -- then
        // ACTION_MOVE drags 15px up (comfortably inside that same radius) to a materially
        // different output, so the resulting ratio actually differs from the initial 4:1.
        val rInput = maxDb - 0.5f
        val rOutput = CompressorGraphMath.outputFor(rInput, thresholdDb, ratio, kneeDb, makeupDb, true)
        val rx = x(rInput)
        val ry = y(rOutput.coerceIn(minDb, maxDb))
        val movedRy = ry - 15f

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, rx, ry))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, rx, movedRy))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, rx, movedRy))

        assertEquals(0, kneeCalls)
        assertEquals(0, thresholdCalls)
        assertTrue("expected at least one onRatioChanged call", ratioCalls > 0)
    }

    @Test
    fun touchOnEmptyGraphSpaceFiresNoCallbackAndDoesNotDisallowIntercept() {
        var kneeCalls = 0
        var thresholdCalls = 0
        var ratioCalls = 0
        view.onKneeChanged = { kneeCalls++ }
        view.onThresholdChanged = { thresholdCalls++ }
        view.onRatioChanged = { ratioCalls++ }
        // Top-left corner: far from the threshold/knee handles and far above the curve (a
        // compressor curve never reaches this high this early), so this is genuinely empty space.
        val ex = x(minDb + 2f)
        val ey = y(maxDb)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, ex, ey))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, ex + 40f, ey))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, ex + 40f, ey))

        assertEquals(0, kneeCalls)
        assertEquals(0, thresholdCalls)
        assertEquals(0, ratioCalls)
    }
}
