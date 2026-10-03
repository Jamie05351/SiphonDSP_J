package app.siphondsp.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
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
     * Front-page mode (`app:stacked="true"`): the top screen's GLOBAL STAGES block. A title, then
     * the three stage cells stacked at 18sp, one line each, with no separators and no health cell
     * (the power button shows whether the engine runs). A block too small for 18sp shrinks to fit (see
     * [fitStacked]).
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
    // stay a dim grey on the toolbar, and white on the front page (no grey text on its top screen).
    private val onColor = BmwDashboardSkin.TOGGLE_ON_GREEN
    private val offColor get() = if (stacked) Color.WHITE else Color.rgb(120, 128, 138)

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
        // Front page: top-aligned, so the title shares a line with the level readout's headings
        // (HomeArt gives both blocks the same top edge, centred on the top screen).
        gravity = Gravity.START or if (stacked) Gravity.TOP else Gravity.CENTER_VERTICAL
        if (!stacked) setPadding(0, dp(25), 0, 0)
        if (stacked) {
            addView(TextView(context).apply {
                text = "GLOBAL STAGES"
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(Color.WHITE)
                isSingleLine = true
                setPadding(dp(4), dp(4), dp(4), dp(6))
            })
        }

        segments.forEachIndexed { index, segment ->
            if (index > 0 && !stacked) addView(separator())
            val cell = TextView(context).apply {
                textSize = if (stacked) 18f else 11f
                includeFontPadding = false
                // Stacked: 2dp above/below keeps title + three 18sp rows inside ~110dp.
                if (stacked) setPadding(dp(4), dp(2), dp(6), dp(2)) else setPadding(dp(6), dp(4), dp(6), dp(4))
                if (stacked) isSingleLine = true
                setOnClickListener { open(segment) }
            }
            segment.view = cell
            cells += cell
            addView(cell)
        }
        if (!stacked) {
            addView(separator())
            cells += healthView
            addView(healthView)
            val sp = fitTextSp()
            for (i in 0 until childCount) (getChildAt(i) as TextView).textSize = sp
        }
    }

    /**
     * Toolbar mode: the largest text size, up to [MAX_TEXT_SP], at which the longest readout the
     * strip can show still fits R.dimen.dsp_status_strip_max_width (short of the page tabs). Worked
     * out once from the worst case rather than the live text, so the size never jumps as the
     * health cell's label changes.
     */
    private fun fitTextSp(): Float {
        val paint = TextPaint(cells.first().paint)
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MAX_TEXT_SP, resources.displayMetrics)
        val separators = cells.size - 1
        val textPx = WORST_CASE_CELLS.sumOf { paint.measureText(it).toDouble() }.toFloat() +
            separators * paint.measureText("·")
        val paddingPx = (cells.size * dp(12) + separators * dp(4)).toFloat()
        val roomPx = resources.getDimension(R.dimen.dsp_status_strip_max_width) - paddingPx
        return (MAX_TEXT_SP * (roomPx / textPx).coerceAtMost(1f)).coerceAtLeast(MIN_TEXT_SP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        refresh(null)
        if (!stacked) handler.post(healthPoll)
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

    /**
     * Asked before a cell opens its screen; false ignores the tap. The front page uses it to hold
     * the cells back while a DSP tile's screen is opening, so they can't open a second screen.
     */
    var canOpen: () -> Boolean = { true }

    private fun open(segment: Segment) {
        if (!canOpen()) return
        context.startActivity(
            Intent(context, segment.target).apply {
                segment.workspaceMode?.let { putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, it) }
            },
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Posted: resizing the text requests a layout, which must not happen inside this one.
        if (stacked && w > 0 && h > 0) post { fitStacked(w, h) }
    }

    /**
     * Front-page mode: the title and rows at 18sp, unless the block is too small for that (a small
     * phone). Then all four shrink together until the title and the widest possible row each fit
     * on one line and all four fit the height, so nothing wraps or is cut off.
     */
    private fun fitStacked(w: Int, h: Int) {
        val title = getChildAt(0) as TextView
        val full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, STACKED_TEXT_SP, resources.displayMetrics)
        val titlePaint = TextPaint(title.paint).apply { textSize = full }
        val cellPaint = TextPaint(cells.first().paint).apply { textSize = full }
        // Horizontal padding: the title's 4 + 4dp, a cell's 4 + 6dp. Vertical: 4 + 6dp, 2 + 2dp.
        // Only the text shrinks -- the padding stays its fixed dp -- so each scale is the room
        // left after the padding over the text's own size at full size. Scaling the padded total
        // instead (as before) under-shrank the text whenever the box was too small, and on a
        // 640dp phone cut off the bottom row.
        val titleText = titlePaint.measureText(title.text.toString())
        val cellText = WORST_CASE_CELLS.take(3).maxOf { cellPaint.measureText(it) }
        val line = cellPaint.fontMetrics.let { it.descent - it.ascent }
        val scaleW = minOf((w - dp(8)) / titleText, (w - dp(10)) / cellText)
        val fixedH = dp(10) + cells.size * dp(4)
        val scaleH = (h - fixedH) / (line * (1 + cells.size))
        // Never 0 or negative (a box smaller than its own padding): keep a sliver of text.
        val px = full * minOf(1f, scaleW, scaleH).coerceAtLeast(MIN_STACKED_SCALE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        cells.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, px) }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        /** The smallest the front-page text may shrink to, as a fraction of STACKED_TEXT_SP. */
        const val MIN_STACKED_SCALE = 0.3f
        const val HEALTH_POLL_MS = 1_000L
        const val LOG_LINES_SHOWN = 12
        val WARN_COLOR = Color.rgb(0xF2, 0xB3, 0x3D)
        /** Toolbar-mode text: as large as fits, never above MAX (the page tabs' height) or below MIN. */
        const val MAX_TEXT_SP = 18f
        const val MIN_TEXT_SP = 14f
        const val STACKED_TEXT_SP = 18f
        /** The widest each toolbar cell gets: stages off, a two-digit limiter threshold, and the
         *  longest DspHealthBadge label. */
        val WORST_CASE_CELLS = listOf("Tilt off", "MBC off", "Limiter -12.5 dB", "● DSP recovering")
    }
}
