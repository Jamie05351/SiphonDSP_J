package app.siphondsp.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import kotlin.math.roundToInt

/**
 * Persistent bypass-state strip pinned to the bottom of every DSP workspace's content column
 * (see activity_parametric_eq.xml -- it spans from the right edge of dsp_sidebar to the far
 * right border). Shows the on/off state of the global stages that are otherwise only visible
 * after navigating to their own screen -- Tilt, Mono Bass, MBC, and the master limiter (with
 * its threshold) -- dimmed when off, each tappable to jump straight to the screen that owns it.
 *
 * Refreshes itself: on attach, whenever the window regains focus (returning from another
 * screen), and on the ACTION_NATIVE_BMW_DSP_UPDATED local broadcast that every edit sends.
 */
class DspStatusStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private class Segment(
        val label: String,
        val enabledIndex: Int,
        val target: Class<out AppCompatActivity>,
        val workspaceMode: String? = null,
        /** When set, the label reads "<label> <value> dB" instead of "<label> on/off". */
        val valueIndex: Int? = null,
    ) {
        lateinit var view: TextView
    }

    private val density = resources.displayMetrics.density
    private val onColor = Color.WHITE
    private val offColor = Color.rgb(120, 128, 138)

    private val segments = listOf(
        Segment("Tilt", NativeBmwDspValues.INDEX_TILT_ENABLED, CrossoverTiltActivity::class.java, CrossoverTiltActivity.MODE_CROSSOVER),
        Segment("Mono Bass", NativeBmwDspValues.INDEX_MONO_BASS_ENABLED, CrossoverTiltActivity::class.java, CrossoverTiltActivity.MODE_CROSSOVER),
        Segment("MBC", NativeBmwDspValues.INDEX_MBC_ENABLED, NativeBmwCompressorActivity::class.java),
        Segment(
            "Limiter", NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED, GainLimiterActivity::class.java,
            valueIndex = NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD,
        ),
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh(intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES))
        }
    }

    init {
        orientation = HORIZONTAL
        // Right-aligned: the info line butts up against the far border instead of trailing off
        // the sidebar edge.
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.dsp_workspace_header_bg))
        setPadding(dp(12), dp(5), dp(4), dp(5))

        segments.forEachIndexed { index, segment ->
            if (index > 0) addView(separator())
            val cell = TextView(context).apply {
                textSize = 11f
                includeFontPadding = false
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { open(segment) }
            }
            segment.view = cell
            addView(cell)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        refresh(null)
    }

    override fun onDetachedFromWindow() {
        context.unregisterLocalReceiver(receiver)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refresh(null)
    }

    private fun refresh(fromBroadcast: FloatArray?) {
        val values = fromBroadcast?.takeIf { it.size == NativeBmwDspValues.SIZE }
            ?: NativeBmwDspValues.load(context)
        segments.forEach { segment ->
            val on = values[segment.enabledIndex] >= 0.5f
            segment.view.text = if (segment.valueIndex != null) {
                "${segment.label} ${formatDb(values[segment.valueIndex])} dB"
            } else {
                "${segment.label} ${if (on) "on" else "off"}"
            }
            segment.view.setTextColor(if (on) onColor else offColor)
        }
    }

    private fun formatDb(value: Float): String =
        if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else "%.1f".format(value)

    private fun separator(): TextView = TextView(context).apply {
        text = "·"
        textSize = 11f
        setTextColor(Color.rgb(90, 96, 104))
        setPadding(dp(2), 0, dp(2), 0)
    }

    private fun open(segment: Segment) {
        context.startActivity(
            Intent(context, segment.target).apply {
                segment.workspaceMode?.let { putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, it) }
            },
        )
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
