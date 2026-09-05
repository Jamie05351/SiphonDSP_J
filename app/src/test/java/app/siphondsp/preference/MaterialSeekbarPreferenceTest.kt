package app.siphondsp.preference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.preference.PreferenceViewHolder
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.R
import com.google.android.material.slider.Slider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * MaterialSeekbarPreference shares its `preference_materialslider` layout/view-type across
 * every slider on a PreferenceScreen, so RecyclerView recycles the same Slider instance across
 * rows with different min/max/step as the user scrolls. Google's Slider validates its
 * valueFrom/valueTo/stepSize/value lazily -- only at the next onDraw -- so a bad value applied
 * in onBindViewHolder doesn't crash until whichever row currently holds that recycled Slider
 * next gets drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterialSeekbarPreferenceTest {

    private val width = 400
    private val height = 100

    private fun themedContext(): android.content.Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SiphonDSP)

    private fun inflateRow(context: android.content.Context): View {
        val itemView = LayoutInflater.from(context).inflate(R.layout.preference_materialslider, null, false)
        measureAndLayout(itemView)
        return itemView
    }

    private fun measureAndLayout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun draw(view: View) {
        view.draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))
    }

    /**
     * min=0, step=1 (tick-aligned) recycled into min=1.5, step=2, value=4.0 (off-tick relative
     * to bare multiples of step, but valid relative to valueFrom=1.5: ticks are 1.5, 3.5, 5.5...).
     * Before the fix, the "corrected" value stayed at 4.0 and Slider.onDraw threw
     * IllegalStateException on this exact recycled rebind.
     */
    @Test
    fun `recycled slider with a non-tick-aligned min snaps instead of crashing`() {
        val context = themedContext()
        val itemView = inflateRow(context)
        val slider = itemView.findViewById<Slider>(R.id.seekbar)

        val prefA = MaterialSeekbarPreference(context)
        prefA.setMin(0f)
        prefA.setMax(10f)
        prefA.setSeekBarIncrement(1f)
        prefA.setValue(5f)
        prefA.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(itemView))
        measureAndLayout(itemView)
        draw(slider) // settles preference A's config, mirroring RecyclerView's post-bind pass

        val prefB = MaterialSeekbarPreference(context)
        prefB.setMin(1.5f)
        prefB.setMax(21.5f)
        prefB.setSeekBarIncrement(2f)
        prefB.setValue(4.0f)
        prefB.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(itemView))
        measureAndLayout(itemView)
        draw(slider) // must not throw

        assertEquals(1.5f, slider.valueFrom, 0f)
        assertEquals(21.5f, slider.valueTo, 0f)
        assertEquals(2f, slider.stepSize, 0f)

        val ticksFromMin = (slider.value - slider.valueFrom) / slider.stepSize
        assertEquals(ticksFromMin.roundToInt().toFloat(), ticksFromMin, 1e-4f)
    }

    @Test
    fun `getValue reflects the snapped value after an off-tick set`() {
        val context = themedContext()
        val itemView = inflateRow(context)

        val preference = MaterialSeekbarPreference(context)
        preference.setMin(1.5f)
        preference.setMax(21.5f)
        preference.setSeekBarIncrement(2f)

        preference.setValue(4.0f)

        // Nearest tick to 4.0 relative to min=1.5, step=2 is 3.5 (|4.0-3.5| < |4.0-5.5|).
        assertEquals(3.5f, preference.getValue(), 0f)

        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(itemView))
        measureAndLayout(itemView)
        val slider = itemView.findViewById<Slider>(R.id.seekbar)
        draw(slider) // must not throw

        assertEquals(3.5f, slider.value, 0f)
    }
}
