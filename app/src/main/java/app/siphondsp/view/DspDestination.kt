package app.siphondsp.view

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/** The 4 main DSP screens reachable from the bottom nav, and their cross-navigation icon --
 *  reusing the exact drawables already used on the bottom nav (menu_main_bottom*.xml) so the
 *  iconography stays consistent app-wide. */
enum class DspDestination(
    val label: String,
    @DrawableRes val icon: Int,
    val activityClass: KClass<out AppCompatActivity>,
) {
    PARAMETRIC_EQ("Parametric EQ", R.drawable.ic_twotone_peq_sliders_28dp, ParametricEqualizerActivity::class),
    GAINS_DELAY("Gains & Delay", R.drawable.ic_twotone_gain_knob_28dp, GainLimiterActivity::class),
    COMPRESSOR("Compressor", R.drawable.ic_twotone_compressor_pulse_28dp, NativeBmwCompressorActivity::class),
    CROSSOVER_TILT("Crossovers & Tilt", R.drawable.ic_twotone_crossover_tilt_28dp, CrossoverTiltActivity::class),
}

/** Builds a vertical column of icon-only buttons -- one per [DspDestination] other than
 *  [current] -- into [container], each hopping laterally to that screen. The current activity
 *  is finished rather than left on the back stack, so back from any of the 4 DSP screens
 *  always returns straight to MainActivity regardless of how many times you've hopped
 *  sideways between them. */
object DspCrossNavBar {
    fun populate(
        activity: FragmentActivity,
        container: LinearLayout,
        current: DspDestination,
        canNavigate: () -> Boolean = { true },
    ) {
        container.removeAllViews()
        // Same defStyleAttr a plain `style="?attr/materialIconButtonOutlinedStyle"` in XML
        // would resolve to -- keeps these buttons themed identically to mode_tab_strip's.
        val outlinedIconButtonStyleAttr = com.google.android.material.R.attr.materialIconButtonOutlinedStyle
        DspDestination.entries.filter { it != current }.forEach { destination ->
            container.addView(
                MaterialButton(activity, null, outlinedIconButtonStyleAttr).apply {
                    icon = ContextCompat.getDrawable(activity, destination.icon)
                    contentDescription = destination.label
                    setOnClickListener {
                        if (!canNavigate()) return@setOnClickListener
                        activity.startActivity(Intent(activity, destination.activityClass.java))
                        activity.finish()
                    }
                },
                LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply { bottomMargin = activity.dp(8) },
            )
        }
        container.visibility = View.VISIBLE
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
