package app.siphondsp.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ThreeWayCrossover
import app.siphondsp.model.debug.RootlessPipelineRuntimeSnapshot
import app.siphondsp.service.DspHealthBadge
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import kotlin.math.roundToInt

/**
 * Seven-column at-a-glance status strip above the home navigation tiles.
 * Order matches the row below: PEQ, Gains/Delay, Xovers, Compressor, Allpass, Settings. The
 * column above More is left empty.
 */
class HomeDashboardStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val handler = Handler(Looper.getMainLooper())
    private var values = NativeBmwDspValues.load(context)
    private var powered = false
    private var healthLabel = "OFF"
    private var runtime: RootlessPipelineRuntimeSnapshot? = null
    private val columnCenters = HomeArt.liveColumnCenters(phone = !context.isHeadUnitDisplay())
    private var active = true

    var powerOn: Boolean
        get() = powered
        set(value) {
            if (powered != value) {
                powered = value
                refreshHealth()
                invalidate()
            }
        }

    var pageActive: Boolean
        get() = active
        set(value) {
            active = value
            handler.removeCallbacks(healthPoll)
            if (value && isAttachedToWindow) handler.post(healthPoll)
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(215, 220, 226)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(112, 121, 132)
        textAlign = Paint.Align.CENTER
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 210, 220, 230)
        strokeWidth = resources.displayMetrics.density
    }
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.TOGGLE_ON_GREEN
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_RED
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(132, 140, 150)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            values = intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)
                ?.takeIf { it.size == NativeBmwDspValues.SIZE }
                ?: NativeBmwDspValues.load(context)
            invalidate()
        }
    }

    private val healthPoll = object : Runnable {
        override fun run() {
            refreshHealth()
            invalidate()
            if (active && isAttachedToWindow) handler.postDelayed(this, 1_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        values = NativeBmwDspValues.load(context)
        refreshHealth()
        if (active) handler.post(healthPoll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(healthPoll)
        context.unregisterLocalReceiver(receiver)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            values = NativeBmwDspValues.load(context)
            refreshHealth()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // Each cell is centred over its own tile (the tiles aren't evenly spaced); cellW is the
        // average tile pitch, which sizes each cell's contents.
        val centers = columnCenters
        val cellW = (centers.last() - centers.first()) * width / (centers.size - 1)
        for (i in 1 until 6) {
            val x = (centers[i - 1] + centers[i]) / 2f * width
            canvas.drawLine(x, height * .12f, x, height * .88f, dividerPaint)
        }

        drawDspState(canvas, cellW, 0)
        drawGains(canvas, cellW, 1)
        drawXovers(canvas, cellW, 2)
        drawCompressor(canvas, cellW, 3)
        drawAllpass(canvas, cellW, 4)
        drawSettings(canvas, cellW, 5)
    }

    private fun center(index: Int): Float = columnCenters[index] * width

    private fun title(canvas: Canvas, cx: Float, value: String) {
        labelPaint.textSize = height * .15f
        canvas.drawText(value, cx, height * .24f, labelPaint)
    }

    private fun line(canvas: Canvas, cx: Float, y: Float, value: String) {
        textPaint.textSize = height * .16f
        canvas.drawText(value, cx, y, textPaint)
    }

    private fun smallLine(canvas: Canvas, cx: Float, y: Float, value: String) {
        textPaint.textSize = height * .13f
        canvas.drawText(value, cx, y, textPaint)
    }

    private fun drawDspState(canvas: Canvas, cellW: Float, index: Int) {
        val cx = center(index)
        title(canvas, cx, "DSP")
        val paint = when (healthLabel) {
            "ON" -> onPaint
            "OFF" -> offPaint
            else -> idlePaint
        }
        canvas.drawCircle(cx - cellW * .16f, height * .62f, height * .045f, paint)
        textPaint.textSize = height * .21f
        canvas.drawText(healthLabel, cx + cellW * .05f, height * .69f, textPaint)
    }

    private fun drawGains(canvas: Canvas, cellW: Float, index: Int) {
        val cx = center(index)
        title(canvas, cx, "HEADROOM / GAIN")
        val hr = values[NativeBmwDspValues.INDEX_HEADROOM]
        val low = average(values[NativeBmwDspValues.INDEX_LOW_GAIN_L], values[NativeBmwDspValues.INDEX_LOW_GAIN_R])
        val mid = average(values[NativeBmwDspValues.INDEX_MID_GAIN_L], values[NativeBmwDspValues.INDEX_MID_GAIN_R])
        val high = average(values[NativeBmwDspValues.INDEX_HIGH_GAIN_L], values[NativeBmwDspValues.INDEX_HIGH_GAIN_R])
        line(canvas, cx, height * .52f, "HR " + db(hr))
        line(canvas, cx, height * .76f, "L " + db(low) + "  M " + db(mid) + "  H " + db(high))
    }

    /** One line per setting on the Crossovers pages: Low/Mid, Mid/High and Tilt. */
    private fun drawXovers(canvas: Canvas, cellW: Float, index: Int) {
        val cx = center(index)
        title(canvas, cx, "XOVERS")
        val midHigh = if (ThreeWayCrossover.isEnabled(values)) {
            hz(values[ThreeWayCrossover.cornerIndex])
        } else {
            "OFF"
        }
        val tilt = if (values[NativeBmwDspValues.INDEX_TILT_ENABLED] >= .5f) {
            db(values[NativeBmwDspValues.INDEX_TILT_AMOUNT]) + " dB"
        } else {
            "OFF"
        }
        smallLine(canvas, cx, height * .44f, "LOW " + xoText(NativeBmwDspValues.OUTPUT_LOW_LEFT))
        smallLine(canvas, cx, height * .60f, "MID " + xoText(NativeBmwDspValues.OUTPUT_MID_LEFT))
        smallLine(canvas, cx, height * .76f, "M/H " + midHigh)
        smallLine(canvas, cx, height * .92f, "TILT " + tilt)
    }

    private fun drawCompressor(canvas: Canvas, cellW: Float, index: Int) {
        val cx = center(index)
        title(canvas, cx, "COMPRESSOR")
        val master = values[NativeBmwDspValues.INDEX_MBC_ENABLED] >= .5f
        val box = minOf(cellW * .18f, height * .23f)
        val gap = box * .22f
        val startX = cx - box - gap / 2f
        val startY = height * .43f
        repeat(4) { band ->
            val col = band % 2
            val row = band / 2
            val left = startX + col * (box + gap)
            val top = startY + row * (box + gap)
            val enabled = master &&
                values[NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_ENABLED)] >= .5f
            canvas.drawRoundRect(
                RectF(left, top, left + box, top + box),
                box * .16f,
                box * .16f,
                if (enabled) onPaint else offPaint,
            )
            textPaint.textSize = box * .34f
            canvas.drawText("B" + (band + 1), left + box / 2f, top + box * .64f, textPaint)
        }
    }

    private fun drawAllpass(canvas: Canvas, cellW: Float, index: Int) {
        val cx = center(index)
        title(canvas, cx, "ALLPASS")
        val outputs = intArrayOf(
            NativeBmwDspValues.OUTPUT_LOW_LEFT,
            NativeBmwDspValues.OUTPUT_MID_LEFT,
            NativeBmwDspValues.OUTPUT_HIGH_LEFT,
            NativeBmwDspValues.OUTPUT_LOW_RIGHT,
            NativeBmwDspValues.OUTPUT_MID_RIGHT,
            NativeBmwDspValues.OUTPUT_HIGH_RIGHT,
        )
        val labels = arrayOf("L", "M", "H")
        val box = minOf(cellW * .19f, height * .22f)
        val gap = box * .16f
        val total = box * 3 + gap * 2
        val startX = cx - total / 2f
        val startY = height * .44f
        outputs.forEachIndexed { i, output ->
            val col = i % 3
            val row = i / 3
            val left = startX + col * (box + gap)
            val top = startY + row * (box + gap)
            canvas.drawRoundRect(
                RectF(left, top, left + box, top + box),
                box * .16f,
                box * .16f,
                if (anyAllPassEnabled(output)) onPaint else offPaint,
            )
            textPaint.textSize = box * .34f
            canvas.drawText(labels[col], left + box / 2f, top + box * .64f, textPaint)
        }
    }

    /** What the running pipeline really uses, not the requested setting; dashes while it's stopped. */
    private fun drawSettings(canvas: Canvas, cellW: Float, index: Int) {
        val cx = center(index)
        title(canvas, cx, "AUDIO")
        val snapshot = runtime
        val format = when (snapshot?.pcmFloat) {
            true -> "FLOAT 32"
            false -> "PCM 16"
            null -> "FORMAT --"
        }
        val buffer = snapshot?.bufferSamples?.takeIf { it > 0 }?.toString() ?: "--"
        line(canvas, cx, height * .52f, format)
        line(canvas, cx, height * .76f, "BUFFER " + buffer)
    }

    private fun refreshHealth() {
        runtime = RootlessAudioProcessorService.pipelineRuntimeSnapshot()
        healthLabel = if (!powered) {
            "OFF"
        } else {
            when (DspHealthBadge.evaluate(runtime).level) {
                DspHealthBadge.Level.IDLE -> "IDLE"
                else -> "ON"
            }
        }
    }

    private fun xoText(output: Int): String {
        val freq = values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)]
        val type = values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)]
        return hz(freq) + " " + slope(type)
    }

    private fun slope(value: Float): String = when (value.roundToInt()) {
        0 -> "BW2"
        1 -> "BW3"
        2 -> "LR4"
        3 -> "BW1"
        4 -> "BW4"
        else -> "?"
    }

    private fun hz(value: Float): String =
        if (value >= 1000f) String.format("%.1fk", value / 1000f) else value.roundToInt().toString()

    private fun db(value: Float): String {
        val rounded = value.roundToInt()
        val body = if (value == rounded.toFloat()) rounded.toString() else String.format("%.1f", value)
        return if (value > 0f) "+" + body else body
    }

    private fun average(a: Float, b: Float): Float = (a + b) * .5f

    private fun anyAllPassEnabled(output: Int): Boolean {
        repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
            val base = if (output <= NativeBmwDspValues.OUTPUT_MID_RIGHT) {
                NativeBmwDspValues.INDEX_ALL_PASS +
                    (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) *
                    NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
            } else {
                NativeBmwDspValues.highAllPassIndex(output, section, 0)
            }
            if (values[base] >= .5f) return true
        }
        return false
    }
}
