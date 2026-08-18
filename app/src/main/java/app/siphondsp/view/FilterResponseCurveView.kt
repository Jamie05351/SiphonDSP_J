package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import app.siphondsp.dsp.BiquadCascade
import app.siphondsp.dsp.ComplexAcc
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Live low-pass/high-pass response curve for a single crossover band card -- built from the same
 * BiquadCascade/ComplexAcc primitives BmwResponseCalculator uses for the full multi-band chain
 * (see rebuildLowCascade/rebuildMidCascade), but for just the one filter stage this card
 * represents, so it can redraw cheaply at card size. Replaces what used to be a static decorative
 * sparkline icon.
 */
class FilterResponseCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Kind { LOW_PASS, HIGH_PASS }

    var kind: Kind = Kind.LOW_PASS
        set(value) { field = value; invalidate() }

    var frequencyHz: Float = 100f
        set(value) { field = value; invalidate() }

    /** 24dB/oct (LR4, two Butterworth sections) vs 18dB/oct (BW3, one Butterworth section plus
     *  one-pole) -- mirrors rebuildLowCascade's own crossoverLr4 branch. Ignored for [Kind.HIGH_PASS]:
     *  the native engine always runs the mid/high branch as fixed two-section LR4. */
    var steep: Boolean = true
        set(value) { field = value; invalidate() }

    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val cascade = BiquadCascade(2)
    private val acc = ComplexAcc()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        cascade.clear()
        val fc = frequencyHz.toDouble()
        when (kind) {
            Kind.LOW_PASS -> if (steep) {
                cascade.addLowPass(fc, BUTTERWORTH_Q, SAMPLE_RATE)
                cascade.addLowPass(fc, BUTTERWORTH_Q, SAMPLE_RATE)
            } else {
                cascade.addLowPass(fc, 1.0, SAMPLE_RATE)
                cascade.addOnePoleLow(fc, SAMPLE_RATE)
            }
            Kind.HIGH_PASS -> {
                cascade.addHighPass(fc, BUTTERWORTH_Q, SAMPLE_RATE)
                cascade.addHighPass(fc, BUTTERWORTH_Q, SAMPLE_RATE)
            }
        }
        paint.color = if (kind == Kind.LOW_PASS) LOW_PASS_COLOR else HIGH_PASS_COLOR

        val path = Path()
        for (i in 0 until POINT_COUNT) {
            val fraction = i.toDouble() / (POINT_COUNT - 1)
            val freq = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(fraction)
            val wAngle = 2.0 * Math.PI * freq / SAMPLE_RATE
            val cw = cos(wAngle)
            val sw = sin(wAngle)
            acc.setUnity()
            cascade.accumulate(cw, sw, 2.0 * cw * cw - 1.0, 2.0 * sw * cw, acc)
            val db = acc.magnitudeDb(FLOOR_DB).toFloat()

            val x = fraction.toFloat() * w
            val y = valueToY(db, h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun valueToY(db: Float, h: Float): Float {
        val clamped = db.coerceIn(MIN_DB, MAX_DB)
        return h - (clamped - MIN_DB) / (MAX_DB - MIN_DB) * h
    }

    companion object {
        private const val SAMPLE_RATE = 48_000.0
        private const val BUTTERWORTH_Q = 0.7071067812
        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20_000.0
        private const val POINT_COUNT = 32
        private const val FLOOR_DB = -60.0
        private const val MIN_DB = -48f
        private const val MAX_DB = 3f
        // Same colors as the decorative sparklines these curves replace.
        private val LOW_PASS_COLOR = Color.parseColor("#E8623B")
        private val HIGH_PASS_COLOR = Color.parseColor("#46B5E8")
    }
}
