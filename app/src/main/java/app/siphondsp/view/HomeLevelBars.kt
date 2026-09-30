package app.siphondsp.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The front page live panel's OUTPUT block: live purple segmented L / R output level bars (post-DSP
 * RMS illumination plus a peak-hold outline from [SpectrumEngine]'s analyzer) and the L / R
 * post-gain readout beneath. Text is at fixed sp sizes (CarUi floors), not scaled to the box.
 *
 * The analyzer thread only runs while something holds [SpectrumEngine.acquire]; this view holds
 * it only while it is attached, its window and view are visible, and [pageActive] is true (the
 * pager is on the artwork page), so nothing extra runs once the user has moved on to a DSP
 * screen or swiped to the settings page.
 */
class HomeLevelBars @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val leftMeter = PeakHoldMeter(floorDb = FLOOR_DB)
    private val rightMeter = PeakHoldMeter(floorDb = FLOOR_DB)
    private val levels = FloatArray(4)
    private var gainL = 0f
    private var gainR = 0f
    private var acquired = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            SpectrumEngine.channelLevelsInto(levels)
            val now = System.currentTimeMillis()
            leftMeter.update(levels[0], levels[1], now)
            rightMeter.update(levels[2], levels[3], now)
            invalidate()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readGains(intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES))
        }
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 0xB1, 0x4D, 0xFF) }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xED, 0xD8, 0xFF)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(184, 196, 208)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        readGains(null)
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        stop()
        context.unregisterLocalReceiver(receiver)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    /**
     * False while the pager is showing another page. The artwork page stays attached and
     * "visible" to the window when swiped away (ViewPager2 only translates it, and DspFragment
     * keeps offscreenPageLimit = 1), so window/view visibility alone can't tell whether the bars
     * are actually on screen; the fragment drives this from the pager selection.
     */
    var pageActive: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateRunning()
        }

    private fun updateRunning() {
        if (pageActive && isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (acquired) return
        acquired = true
        SpectrumEngine.acquire()
        handler.post(tick)
    }

    private fun stop() {
        if (!acquired) return
        acquired = false
        handler.removeCallbacks(tick)
        SpectrumEngine.release()
        leftMeter.reset()
        rightMeter.reset()
        invalidate()
    }

    private fun readGains(fromBroadcast: FloatArray?) {
        val values = fromBroadcast?.takeIf { it.size == NativeBmwDspValues.SIZE }
            ?: NativeBmwDspValues.load(context)
        gainL = values[NativeBmwDspValues.INDEX_POST_GAIN_L]
        gainR = values[NativeBmwDspValues.INDEX_POST_GAIN_R]
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Title, L bar, R bar, gain readout, top-aligned: 18sp, white (the split faceplate's top
        // screen: nothing under 18, no grey text). That needs ~110dp of height; a shorter box (a
        // small phone) scales the whole block down to fit rather than clipping the gain line.
        val pad = 4f * density
        val natural = pad + sp(18f) + 8f * density + 52f * density + 6f * density + sp(18f) * 1.25f
        val k = (h / natural).coerceAtMost(1f)
        val titlePx = sp(18f) * k
        val valuePx = sp(18f) * k
        val rowH = 26f * density * k
        val barH = 14f * density * k
        val labelW = 22f * density * k
        val barLeft = pad + labelW
        val barRight = w - pad

        textPaint.textSize = titlePx
        textPaint.color = TITLE_COLOR
        var y = pad + titlePx
        canvas.drawText("OUTPUT", pad, y, textPaint)
        y += 8f * density * k

        textPaint.textSize = valuePx
        textPaint.color = LABEL_COLOR
        drawBar(canvas, "L", leftMeter, pad, y, barLeft, barRight, barH, rowH)
        drawBar(canvas, "R", rightMeter, pad, y + rowH, barLeft, barRight, barH, rowH)
        y += rowH * 2f + 6f * density * k + valuePx

        val gainText = "Post gain  L ${formatDb(gainL)}   R ${formatDb(gainR)} dB"
        // Shrink to fit if the gain values are long (e.g. "-12.5"), never clip.
        val maxW = w - pad * 2f
        val textW = textPaint.measureText(gainText)
        if (textW > maxW) textPaint.textSize *= maxW / textW
        textPaint.color = VALUE_COLOR
        canvas.drawText(gainText, pad, y, textPaint)
    }

    private fun sp(value: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun drawBar(
        canvas: Canvas, label: String, meter: PeakHoldMeter,
        left: Float, top: Float, barLeft: Float, barRight: Float, barH: Float, rowH: Float,
    ) {
        val barTop = top + (rowH - barH) / 2f
        canvas.drawText(label, left, top + (rowH + textPaint.textSize * 0.7f) / 2f, textPaint)

        // Bar = RMS (average loudness); the outlined segment = peak hold. Filling to instantaneous
        // peak pinned the bar near full on any mastered music.
        val fraction = PeakHoldMeter.fractionFor(meter.rmsDb, FLOOR_DB, CEILING_DB)
        val span = barRight - barLeft
        val gap = barH * 0.19f
        val segmentCount = SEGMENT_COUNT
        val segmentW = ((span - gap * (segmentCount - 1)) / segmentCount).coerceAtLeast(1f)
        val radius = minOf(segmentW, barH) * 0.22f
        val active = if (fraction <= 0f) 0 else ceil(fraction * segmentCount).toInt().coerceAtMost(segmentCount)
        fillPaint.shader = LinearGradient(
            0f,
            barTop,
            0f,
            barTop + barH,
            intArrayOf(PURPLE_HIGHLIGHT, PURPLE, PURPLE_SHADOW),
            floatArrayOf(0f, 0.42f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        repeat(segmentCount) { index ->
            val segmentLeft = barLeft + index * (segmentW + gap)
            rect.set(segmentLeft, barTop, segmentLeft + segmentW, barTop + barH)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            if (index < active) {
                rect.inset(-gap * 0.34f, -gap * 0.32f)
                canvas.drawRoundRect(rect, radius + gap, radius + gap, glowPaint)
                rect.inset(gap * 0.34f, gap * 0.32f)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                canvas.drawLine(
                    rect.left + segmentW * 0.18f,
                    rect.top + density,
                    rect.right - segmentW * 0.18f,
                    rect.top + density,
                    highlightPaint,
                )
            }
        }

        val hold = PeakHoldMeter.fractionFor(meter.holdDb, FLOOR_DB, CEILING_DB)
        if (hold > 0f) {
            val index = (ceil(hold * segmentCount).toInt() - 1).coerceIn(0, segmentCount - 1)
            val segmentLeft = barLeft + index * (segmentW + gap)
            rect.set(segmentLeft, barTop, segmentLeft + segmentW, barTop + barH)
            canvas.drawRoundRect(rect, radius, radius, holdPaint)
        }
    }

    private fun formatDb(value: Float): String {
        val sign = if (value > 0f) "+" else ""
        return if (value == value.roundToInt().toFloat()) "$sign${value.roundToInt()}" else "$sign${"%.1f".format(value)}"
    }

    private companion object {
        const val FRAME_MS = 50L
        const val FLOOR_DB = -60f
        const val CEILING_DB = 0f
        const val SEGMENT_COUNT = 28
        val PURPLE = BmwDashboardSkin.SLIDER_HEADROOM_COLOR
        val PURPLE_HIGHLIGHT = Color.rgb(0xE2, 0xC2, 0xFF)
        val PURPLE_SHADOW = Color.rgb(0x54, 0x16, 0x88)
        val TITLE_COLOR = Color.WHITE
        val LABEL_COLOR = Color.WHITE
        val VALUE_COLOR = Color.rgb(230, 231, 232)
    }
}
