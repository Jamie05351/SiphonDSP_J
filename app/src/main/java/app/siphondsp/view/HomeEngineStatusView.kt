package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import app.siphondsp.service.DspHealthBadge
import app.siphondsp.service.RootlessAudioProcessorService

/**
 * The front page live panel's AUDIO ENGINE block: whether audio is really flowing through the DSP
 * (Running / Idle / starting... / NO AUDIO, or Off while the power button is off) and the format
 * and buffer size the running pipeline actually uses. Polled once a second while [pageActive].
 */
class HomeEngineStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var status = "Off"
    private var statusColor = OFF_COLOR
    private var detail = "--"

    /** Mirrors MainActivity's power state: the engine reads Off while the DSP is switched off. */
    var powerOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    /** False while the pager is on another page, so the poll stops when the panel is off screen. */
    var pageActive: Boolean = true
        set(value) {
            field = value
            handler.removeCallbacks(poll)
            if (value && isAttachedToWindow) handler.post(poll)
        }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (pageActive && isAttachedToWindow) handler.postDelayed(this, POLL_MS)
        }
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TITLE_COLOR
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = sp(18f)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = sp(20f)
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = VALUE_COLOR
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = sp(18f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pageActive) handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val snapshot = RootlessAudioProcessorService.pipelineRuntimeSnapshot()
        if (!powerOn) {
            status = "Off"
            statusColor = OFF_COLOR
        } else {
            val badge = DspHealthBadge.evaluate(snapshot)
            status = when (badge.level) {
                DspHealthBadge.Level.OK -> "Running"
                DspHealthBadge.Level.IDLE -> "Idle"
                else -> badge.label
            }
            statusColor = when (badge.level) {
                DspHealthBadge.Level.OK -> BmwDashboardSkin.TOGGLE_ON_GREEN
                DspHealthBadge.Level.IDLE -> IDLE_COLOR
                DspHealthBadge.Level.WARN -> WARN_COLOR
                DspHealthBadge.Level.BAD -> BmwDashboardSkin.M_RED
            }
        }
        val format = when (snapshot?.pcmFloat) {
            true -> "Float 32"
            false -> "PCM 16"
            null -> "--"
        }
        val buffer = snapshot?.bufferSamples?.takeIf { it > 0 }?.toString()
        detail = if (buffer != null) "$format · $buffer" else format
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val pad = 4f * density
        val maxW = width - pad * 2f
        var y = pad + titlePaint.textSize
        canvas.drawText("AUDIO ENGINE", pad, y, titlePaint)

        y += 10f * density + statusPaint.textSize
        statusPaint.color = statusColor
        val statusText = "● $status"
        drawFitted(canvas, statusText, pad, y, maxW, statusPaint, sp(20f))

        y += 10f * density + detailPaint.textSize
        drawFitted(canvas, detail, pad, y, maxW, detailPaint, sp(18f))
    }

    /** Draws [text] at [fullPx], shrinking it only if it would overrun [maxW]. */
    private fun drawFitted(canvas: Canvas, text: String, x: Float, y: Float, maxW: Float, paint: Paint, fullPx: Float) {
        paint.textSize = fullPx
        val w = paint.measureText(text)
        if (w > maxW) paint.textSize = fullPx * maxW / w
        canvas.drawText(text, x, y, paint)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val POLL_MS = 1_000L
        val TITLE_COLOR = Color.WHITE
        val VALUE_COLOR = Color.rgb(230, 231, 232)
        val IDLE_COLOR = Color.rgb(132, 140, 150)
        val WARN_COLOR = Color.rgb(0xF2, 0xB3, 0x3D)
        val OFF_COLOR = BmwDashboardSkin.M_RED
    }
}
