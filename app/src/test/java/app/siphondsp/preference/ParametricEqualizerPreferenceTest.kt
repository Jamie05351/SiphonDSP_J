package app.siphondsp.preference

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.preference.PreferenceViewHolder
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.R
import app.siphondsp.dsp.BmwPeqBank
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.view.ParametricEqSurface
import app.siphondsp.view.PeqGraphMath
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Settings preview row must render the same UNIFIED_SYSTEM graphic as the full editor but
 * stay strictly non-interactive -- a stray tap on a node must never start a drag or steal the
 * touch from the row's own click-to-open-editor listener (see ParametricEqSurface.interactive).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParametricEqualizerPreferenceTest {
    private lateinit var surface: ParametricEqSurface
    private val width = 400
    private val height = 300

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preference = ParametricEqualizerPreference(context)
        val itemView = LayoutInflater.from(context)
            .inflate(R.layout.preference_parametric_equalizer, null, false)
        itemView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height + 79, View.MeasureSpec.EXACTLY),
        )
        itemView.layout(0, 0, width, height + 79)

        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(itemView))
        surface = itemView.findViewById(R.id.layout_equalizer)
    }

    @Test
    fun bindsInNonInteractiveMode() {
        assertFalse(surface.interactive)
    }

    @Test
    fun tapOnANodeIsNotConsumed() {
        val band = ParametricEqBand(1000.0, 5.0, 1.41)
        val peq = BmwPeqState.empty().copy(
            fullRangeBands = ParametricEqBandList().apply { add(band) },
        )
        surface.setSystemState(NativeBmwDspValues.DEFAULTS.copyOf(), peq, BmwPeqBank.FULL, null, 48_000.0)

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
        val x = plotLeft + xFraction * (plotRight - plotLeft)
        val y = plotTop + yFraction * (plotBottom - plotTop)

        val consumed = surface.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0))

        assertFalse(consumed)
    }
}
