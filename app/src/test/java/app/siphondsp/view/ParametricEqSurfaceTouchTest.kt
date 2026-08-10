package app.siphondsp.view

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.dsp.BmwPeqBank
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives ParametricEqSurface's onTouchEvent() with synthetic MotionEvents -- the gesture
 * dispatch logic (draft/commit-once-on-ACTION_UP, drag cancellation, per-bank hit-test
 * scoping) lives entirely in View touch handling that plain-JVM tests can't exercise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParametricEqSurfaceTouchTest {
    private lateinit var view: ParametricEqSurface
    private val width = 400
    private val height = 300

    @Before
    fun setUp() {
        view = ParametricEqSurface(ApplicationProvider.getApplicationContext(), null)
        view.measure(
            View_EXACTLY(width),
            View_EXACTLY(height),
        )
        view.layout(0, 0, width, height)
    }

    private fun View_EXACTLY(size: Int) =
        android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)

    private fun motionEvent(action: Int, x: Float, y: Float, downTime: Long = 0L, eventTime: Long = 0L) =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0)

    private fun multiPointerDown(x1: Float, y1: Float, x2: Float, y2: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = x2; y = y2; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            0L, 10L,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
    }

    // --- Band drag --------------------------------------------------------------------------

    private fun bindSingleBand(band: ParametricEqBand) {
        val peq = BmwPeqState.empty().copy(fullRangeBands = ParametricEqBandList().apply { add(band) })
        view.setSystemState(NativeBmwDspValues.DEFAULTS.copyOf(), peq, BmwPeqBank.FULL, null, 48_000.0)
    }

    @Test
    fun dragCommitsExactlyOnceOnActionUp() {
        val band = ParametricEqBand(1000.0, 0.0, 1.41)
        bindSingleBand(band)
        var commitCount = 0
        var lastCommitted: ParametricEqBand? = null
        view.onDragCommitted = { commitCount++; lastCommitted = it }
        val (x, y) = unifiedPixel(band)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, x + 60f, y + 40f))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, x + 65f, y + 42f))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, x + 65f, y + 42f))

        assertEquals(1, commitCount)
        assertEquals(band.uuid, lastCommitted?.uuid)
        assertNotEquals(band.frequency, lastCommitted?.frequency)
    }

    @Test
    fun tapWithoutMovementDoesNotCommit() {
        val band = ParametricEqBand(1000.0, 0.0, 1.41)
        bindSingleBand(band)
        var commitCount = 0
        view.onDragCommitted = { commitCount++ }
        val (x, y) = unifiedPixel(band)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, x, y))

        assertEquals(0, commitCount)
        assertFalse(view.hasActiveDraft())
    }

    @Test
    fun cancelledDragCommitsNothingAndClearsTheDraft() {
        val band = ParametricEqBand(1000.0, 0.0, 1.41)
        bindSingleBand(band)
        var commitCount = 0
        view.onDragCommitted = { commitCount++ }
        val (x, y) = unifiedPixel(band)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, x + 60f, y + 40f))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_CANCEL, x + 60f, y + 40f))

        assertEquals(0, commitCount)
        assertFalse(view.hasActiveDraft())
    }

    @Test
    fun secondPointerDownCancelsTheDraft() {
        val band = ParametricEqBand(1000.0, 0.0, 1.41)
        bindSingleBand(band)
        var commitCount = 0
        view.onDragCommitted = { commitCount++ }
        val (x, y) = unifiedPixel(band)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, x + 60f, y + 40f))
        view.onTouchEvent(multiPointerDown(x + 60f, y + 40f, x + 100f, y + 100f))

        assertEquals(0, commitCount)
        assertFalse(view.hasActiveDraft())
    }

    // --- Tilt drag ----------------------------------------------------------------------

    private fun tiltAmountPixel(): Pair<Float, Float> {
        val padLeft = 34f
        val padTop = 16f
        val padRight = 44f
        val padBottom = 22f
        val plotLeft = padLeft
        val plotRight = width - padRight
        val plotTop = padTop
        val plotBottom = height - padBottom
        val freqFraction = PeqGraphMath.frequencyToFraction(550.0, PeqGraphMath.MIN_FREQUENCY, PeqGraphMath.MAX_FREQUENCY)
        val pivotX = plotLeft + freqFraction * (plotRight - plotLeft)
        val amountX = pivotX + 22f
        val amountFraction = PeqGraphMath.gainToFraction(3.0)
        val amountY = plotTop + amountFraction * (plotBottom - plotTop)
        return amountX to amountY
    }

    @Test
    fun tiltDragCommitsExactlyOnceOnActionUp() {
        view.showTiltHandles = true
        val values = NativeBmwDspValues.DEFAULTS.copyOf() // tilt enabled=1, amount=3dB, freq=550Hz
        view.setSystemState(values, BmwPeqState.empty(), BmwPeqBank.FULL, null, 48_000.0)
        var commitCount = 0
        view.onTiltDragCommitted = { _, _ -> commitCount++ }
        val (x, y) = tiltAmountPixel()

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, x, y - 40f))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, x, y - 45f))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, x, y - 45f))

        assertEquals(1, commitCount)
    }

    // --- Per-bank hit-test scoping -----------------------------------------------------------

    /** hitTest() maps through plotLeft/plotRight/plotTop/plotBottom -- must mirror that offset
     *  here or the computed point wouldn't actually land where the node is drawn. */
    private fun unifiedPixel(band: ParametricEqBand): Pair<Float, Float> {
        val padLeft = 34f
        val padTop = 16f
        val padRight = 44f
        val padBottom = 22f
        val plotLeft = padLeft
        val plotRight = width - padRight
        val plotTop = padTop
        val plotBottom = height - padBottom
        val xFraction = PeqGraphMath.frequencyToFraction(band.frequency, PeqGraphMath.MIN_FREQUENCY, PeqGraphMath.MAX_FREQUENCY)
        val yFraction = PeqGraphMath.gainToFraction(band.gain)
        return (plotLeft + xFraction * (plotRight - plotLeft)) to (plotTop + yFraction * (plotBottom - plotTop))
    }

    @Test
    fun inactiveBankNodeNeverStealsATouch() {
        val lowBand = ParametricEqBand(2_000.0, 5.0, 1.41)
        val peq = BmwPeqState.empty().copy(
            lowBandBands = ParametricEqBandList().apply { add(lowBand) },
        )
        // Active bank is FULL (empty), so only the LOW band exists on screen -- as an
        // inactive-bank node, drawn but not present in hitTest()'s renderBands.
        view.setSystemState(NativeBmwDspValues.DEFAULTS.copyOf(), peq, BmwPeqBank.FULL, null, 48_000.0)
        var selected = false
        view.onPointSelected = { selected = true }
        val (x, y) = unifiedPixel(lowBand)

        val consumed = view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))

        assertFalse(consumed)
        assertFalse(selected)
        assertFalse(view.hasActiveDraft())
    }
}
