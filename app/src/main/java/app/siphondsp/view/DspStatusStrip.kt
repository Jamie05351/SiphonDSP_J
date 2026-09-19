package app.siphondsp.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.EngineLauncherActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.AudioHealthLog
import app.siphondsp.service.DspHealthBadge
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import kotlin.math.roundToInt

/**
 * Persistent bypass-state readout riding the DSP workspace toolbar line, between the back arrow
 * and the centred title (see activity_parametric_eq.xml -- it sits in the AppBarLayout's toolbar
 * overlay, above the ///M stripe divider, with no background of its own). Shows the on/off state
 * of the global stages that are otherwise only visible after navigating to their own screen --
 * Tilt, MBC, and the master limiter (with its threshold) -- dimmed when off, each
 * tappable to jump straight to the screen that owns it.
 *
 * A fourth cell shows the audio path's health (DSP ok / idle / starting / recovering / NO AUDIO /
 * DSP off), polled once a second while attached; tapping it opens the details -- reason, underruns,
 * recoveries, the saved event log -- with a Restart button. The log outlives a head-unit reset.
 *
 * Refreshes itself: on attach, whenever the window regains focus (returning from another
 * screen), and on the ACTION_NATIVE_BMW_DSP_UPDATED local broadcast that every edit sends.
 */
class DspStatusStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /**
     * Front-page mode (`app:stacked="true"`): the same cells stacked vertically inside the
     * artwork's left display, with no separators and text sized from the box height instead of
     * fixed sp.
     */
    private val stacked: Boolean = context.obtainStyledAttributes(attrs, R.styleable.DspStatusStrip).let {
        try {
            it.getBoolean(R.styleable.DspStatusStrip_stacked, false)
        } finally {
            it.recycle()
        }
    }

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
    // Active stages read in the app's "lit" green (same neon as the ON/OFF switch); inactive
    // stay a dim grey.
    private val onColor = BmwDashboardSkin.TOGGLE_ON_GREEN
    private val offColor = Color.rgb(120, 128, 138)

    private val segments = listOf(
        Segment("Tilt", NativeBmwDspValues.INDEX_TILT_ENABLED, CrossoverTiltActivity::class.java, CrossoverTiltActivity.MODE_CROSSOVER),
        Segment("MBC", NativeBmwDspValues.INDEX_MBC_ENABLED, NativeBmwCompressorActivity::class.java),
        Segment(
            "Limiter", NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED, GainLimiterActivity::class.java,
            valueIndex = NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD,
        ),
    )

    private val healthView = TextView(context).apply {
        textSize = 11f
        includeFontPadding = false
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setOnClickListener { showHealthDialog() }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val healthPoll = object : Runnable {
        override fun run() {
            refreshHealth()
            handler.postDelayed(this, HEALTH_POLL_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh(intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES))
        }
    }

    private val cells = mutableListOf<TextView>()

    init {
        orientation = if (stacked) VERTICAL else HORIZONTAL
        // Sits just past the toolbar's back arrow, centred on the toolbar line. No background of
        // its own -- the toolbar it rides paints the header colour behind it. Top padding matches
        // the toolbar's own (see activity_parametric_eq.xml / dsp_workspace_toolbar_height) so this
        // strip's text lines up with the toolbar's (bezel-clearance-padded) content band.
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        if (!stacked) setPadding(0, dp(25), 0, 0)

        segments.forEachIndexed { index, segment ->
            if (index > 0 && !stacked) addView(separator())
            val cell = TextView(context).apply {
                textSize = 11f
                includeFontPadding = false
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { open(segment) }
            }
            segment.view = cell
            cells += cell
            addView(cell)
        }
        if (!stacked) addView(separator())
        cells += healthView
        addView(healthView)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!stacked || h <= 0) return
        // Four rows share the display's height: text takes ~62% of a row, the rest is padding.
        // Posted, not applied here: a requestLayout() raised from inside the layout pass doesn't
        // reliably re-measure the wrap_content cells, which left the three bypass rows at their
        // old height with the enlarged text clipped (only the health row, which re-lays itself
        // out every second, came out right).
        val rowPx = h / cells.size.toFloat()
        val padX = (rowPx * 0.25f).roundToInt()
        val padY = (rowPx * 0.08f).roundToInt()
        post {
            cells.forEach {
                it.setTextSize(TypedValue.COMPLEX_UNIT_PX, rowPx * 0.62f)
                it.setPadding(padX, padY, padX, padY)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        refresh(null)
        handler.post(healthPoll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(healthPoll)
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

    private fun refreshHealth() {
        val badge = DspHealthBadge.evaluate(RootlessAudioProcessorService.pipelineRuntimeSnapshot())
        healthView.text = "\u25CF ${badge.label}"
        healthView.setTextColor(
            when (badge.level) {
                DspHealthBadge.Level.OK -> onColor
                DspHealthBadge.Level.IDLE -> offColor
                DspHealthBadge.Level.WARN -> WARN_COLOR
                DspHealthBadge.Level.BAD -> BmwDashboardSkin.M_RED
            },
        )
    }

    private fun showHealthDialog() {
        val snapshot = RootlessAudioProcessorService.pipelineRuntimeSnapshot()
        val badge = DspHealthBadge.evaluate(snapshot)
        val message = buildString {
            appendLine("${badge.label}: ${badge.detail}")
            if (snapshot != null) {
                appendLine()
                appendLine("State: ${snapshot.pipelineHealthState ?: "-"}")
                appendLine(
                    "Last audio out: " +
                        if (snapshot.lastFlowAgeMs < 0) "none yet" else "${snapshot.lastFlowAgeMs / 1000} s ago",
                )
                appendLine("Underruns: ${snapshot.underrunCount}")
                appendLine("Recoveries this session: ${snapshot.recoveriesThisSession}")
                appendLine(
                    "Recorder: ${if (snapshot.recorderRecording) "recording" else "not recording"}  " +
                        "Track: ${if (snapshot.trackPlaying) "playing" else "not playing"}",
                )
            }
            val log = AudioHealthLog.read(context)
            appendLine()
            if (log.isEmpty()) {
                append("No events recorded yet.")
            } else {
                appendLine("Recent events (newest last):")
                log.takeLast(LOG_LINES_SHOWN).forEach { appendLine(it) }
            }
        }
        AlertDialog.Builder(context)
            .setTitle("DSP audio health")
            .setMessage(message.trimEnd())
            .setPositiveButton(if (snapshot == null) "Start audio" else "Restart audio") { _, _ ->
                if (snapshot == null || !RootlessAudioProcessorService.requestPipelineRestart()) {
                    // Not running (or gone since the dialog opened): the engine needs a fresh
                    // capture permission, which the launcher activity requests.
                    context.startActivity(
                        Intent(context, EngineLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .setNegativeButton("Close", null)
            .show()
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

    private companion object {
        const val HEALTH_POLL_MS = 1_000L
        const val LOG_LINES_SHOWN = 12
        val WARN_COLOR = Color.rgb(0xF2, 0xB3, 0x3D)
    }
}
