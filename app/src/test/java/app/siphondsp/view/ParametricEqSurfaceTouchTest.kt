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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives ParametricEqSurface's onTouchEvent() with synthetic MotionEvents. The graph is
 * read-only now: a tap on a node selects it (and, for an active-bank node, reports it via
 * onPointSelected); a tap that misses every node is not consumed; any real finger travel
 * cancels the pending tap. There is no dragging of filters or tilt handles any more.
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
        view.measure(View_EXACTLY(width), View_EXACTLY(height))
        view.layout(0, 0, width, height)
    }

    private fun View_EXACTLY(size: Int) =
        android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)

    private fun motionEvent(action: Int, x: Float, y: Float) =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    /** Mirrors ParametricEqSurface's plot padding so the computed point lands on the node. */
    private fun pixelFor(band: ParametricEqBand): Pair<Float, Float> {
        val plotLeft = 34f
        val plotRight = width - 44f
        val plotTop = 16f
        val plotBottom = height - 22f
        val xFraction = PeqGraphMath.frequencyToFraction(band.frequency, PeqGraphMath.MIN_FREQUENCY, PeqGraphMath.MAX_FREQUENCY)
        val yFraction = PeqGraphMath.gainToFraction(band.gain)
        return (plotLeft + xFraction * (plotRight - plotLeft)) to (plotTop + yFraction * (plotBottom - plotTop))
    }

    private fun bind(
        activeBank: BmwPeqBank,
        full: List<ParametricEqBand> = emptyList(),
        low: List<ParametricEqBand> = emptyList(),
        mid: List<ParametricEqBand> = emptyList(),
    ) {
        val peq = BmwPeqState.empty().copy(
            fullRangeBands = ParametricEqBandList().apply { addAll(full) },
            lowBandBands = ParametricEqBandList().apply { addAll(low) },
            midBandBands = ParametricEqBandList().apply { addAll(mid) },
        )
        view.setSystemState(NativeBmwDspValues.DEFAULTS.copyOf(), peq, activeBank, null, 48_000.0)
    }

    @Test
    fun tapOnActiveBankNodeSelectsAndConsumes() {
        val band = ParametricEqBand(1000.0, -6.0, 1.41)
        bind(BmwPeqBank.FULL, full = listOf(band))
        var selected: java.util.UUID? = null
        view.onPointSelected = { selected = it }
        val (x, y) = pixelFor(band)

        val down = view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))
        val up = view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, x, y))

        assertTrue(down)
        assertTrue(up)
        assertEquals(band.uuid, selected)
    }

    @Test
    fun tapOnEmptyAreaIsNotConsumedAndSelectsNothing() {
        val band = ParametricEqBand(1000.0, -6.0, 1.41)
        bind(BmwPeqBank.FULL, full = listOf(band))
        var selected: java.util.UUID? = null
        view.onPointSelected = { selected = it }

        val down = view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, 5f, 5f))

        assertFalse(down)
        assertNull(selected)
    }

    @Test
    fun movingBeyondTouchSlopCancelsTheTap() {
        val band = ParametricEqBand(1000.0, -6.0, 1.41)
        bind(BmwPeqBank.FULL, full = listOf(band))
        var selected: java.util.UUID? = null
        view.onPointSelected = { selected = it }
        val (x, y) = pixelFor(band)

        view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, x + 80f, y + 80f))
        view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, x + 80f, y + 80f))

        assertNull(selected)
    }

    @Test
    fun tapOnAlwaysVisibleLowNodeWhileFullActiveConsumesButDoesNotOpenEditor() {
        // Low Band is always drawn regardless of the active scope, and its nodes are tappable
        // for the info card -- but only active-bank taps report through onPointSelected.
        val lowBand = ParametricEqBand(80.0, -10.0, 1.0)
        bind(BmwPeqBank.FULL, low = listOf(lowBand))
        var selected: java.util.UUID? = null
        view.onPointSelected = { selected = it }
        val (x, y) = pixelFor(lowBand)

        val down = view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))
        val up = view.onTouchEvent(motionEvent(MotionEvent.ACTION_UP, x, y))

        assertTrue(down)
        assertTrue(up)
        assertNull(selected)
    }

    @Test
    fun nonInteractiveInstanceIgnoresEveryTouch() {
        val band = ParametricEqBand(1000.0, -6.0, 1.41)
        bind(BmwPeqBank.FULL, full = listOf(band))
        view.interactive = false
        var selected: java.util.UUID? = null
        view.onPointSelected = { selected = it }
        val (x, y) = pixelFor(band)

        val down = view.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, x, y))

        assertFalse(down)
        assertNull(selected)
    }
}
