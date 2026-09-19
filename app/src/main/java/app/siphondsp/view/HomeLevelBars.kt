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
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import kotlin.math.roundToInt

/**
 * The front page's right-hand display: live L / R output level bars (post-DSP, from
 * [SpectrumEngine]'s analyzer, with a peak-hold tick) and the L / R post-gain readout beneath.
 *
 * The analyzer thread only runs while something holds [SpectrumEngine.acquire]; this view holds
 * it only while its window is actually visible, so nothing extra runs once the user has moved on
 * to a DSP screen.
 */
class HomeLevelBars @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

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
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
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

    private fun updateRunning() {
        if (isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE) start() else stop()
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

        // Three rows: L bar, R bar, gain readout. Sizes follow the box height, not dp, so the
        // layout holds on any display density.
        val pad = h * 0.10f
        val rowH = (h - pad * 2f) / 3f
        val labelW = rowH * 0.9f
        val barH = rowH * 0.56f
        val barLeft = pad + labelW
        val barRight = w - pad
        textPaint.textSize = rowH * 0.62f

        drawBar(canvas, "L", leftMeter, pad, pad, barLeft, barRight, barH, rowH)
        drawBar(canvas, "R", rightMeter, pad, pad + rowH, barLeft, barRight, barH, rowH)

        textPaint.textSize = rowH * 0.5f
        textPaint.color = Color.rgb(140, 150, 162)
        val gainText = "POST GAIN  L ${formatDb(gainL)}   R ${formatDb(gainR)} dB"
        canvas.drawText(gainText, pad, pad + rowH * 2f + rowH * 0.72f, textPaint)
        textPaint.color = Color.rgb(184, 196, 208)
    }

    private fun drawBar(
        canvas: Canvas, label: String, meter: PeakHoldMeter,
        left: Float, top: Float, barLeft: Float, barRight: Float, barH: Float, rowH: Float,
    ) {
        val barTop = top + (rowH - barH) / 2f
        canvas.drawText(label, left, top + rowH * 0.72f, textPaint)

        val radius = barH * 0.3f
        rect.set(barLeft, barTop, barRight, barTop + barH)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val fraction = PeakHoldMeter.fractionFor(meter.peakDb, FLOOR_DB, CEILING_DB)
        if (fraction > 0f) {
            fillPaint.shader = LinearGradient(
                barLeft, 0f, barRight, 0f,
                intArrayOf(GREEN, GREEN, AMBER, RED),
                floatArrayOf(0f, 0.6f, 0.85f, 1f),
                Shader.TileMode.CLAMP,
            )
            rect.set(barLeft, barTop, barLeft + (barRight - barLeft) * fraction, barTop + barH)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }

        val hold = PeakHoldMeter.fractionFor(meter.holdDb, FLOOR_DB, CEILING_DB)
        if (hold > 0f) {
            val x = barLeft + (barRight - barLeft) * hold
            val tickW = barH * 0.16f
            canvas.drawRect(x - tickW, barTop, x, barTop + barH, holdPaint)
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
        val GREEN = Color.rgb(0x22, 0xCC, 0x66)
        val AMBER = Color.rgb(0xDD, 0xCC, 0x22)
        val RED = Color.rgb(0xEE, 0x44, 0x33)
    }
}
