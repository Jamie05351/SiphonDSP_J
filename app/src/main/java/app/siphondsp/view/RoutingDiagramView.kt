package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * Static illustration of the BMW routing matrix's signal path: the two incoming input channels
 * fan out through the routing matrix into the four band-processing outputs below, which then
 * sum back down to the two physical speaker channels.
 *
 * Purely explanatory -- no interaction, no live data. It exists because the routing screen's
 * sliders are a flat list of "input -> band" weights, which doesn't make the matrix's actual
 * shape (2 inputs, 4 band outputs, fixed 2-way summation back to speakers) obvious at a glance.
 */
class RoutingDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = context.resources.displayMetrics.density

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 23, 28)
        style = Paint.Style.FILL
    }
    private val boxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(67, 73, 82)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val boxTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(231, 235, 239)
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
    }
    private val matrixLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.LIGHT_BLUE
        style = Paint.Style.STROKE
        strokeWidth = density
        alpha = 110
    }
    private val sumLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 179, 189)
        style = Paint.Style.STROKE
        strokeWidth = density
        alpha = 150
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 158, 168)
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * density
    }

    private val inputLabels = listOf("Input L", "Input R")
    private val bandLabels = listOf("Low L", "Low R", "Mid L", "Mid R")
    private val speakerLabels = listOf("Final L", "Final R")

    private val boxWidth get() = 64f * density
    private val boxHeight get() = 28f * density
    private val rowGap get() = 32f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = (boxHeight * 3 + rowGap * 2 + 6f * density).roundToInt()
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0f) return

        val topY = boxHeight / 2f
        val midY = topY + rowGap + boxHeight
        val bottomY = midY + rowGap + boxHeight

        val inputXs = centeredXs(w, inputLabels.size)
        val bandXs = centeredXs(w, bandLabels.size)
        val speakerXs = centeredXs(w, speakerLabels.size)

        // Routing matrix: every input can feed every band (the sliders below set these weights).
        for (ix in inputXs) {
            for (bx in bandXs) {
                canvas.drawLine(ix, topY + boxHeight / 2f, bx, midY - boxHeight / 2f, matrixLinePaint)
            }
        }
        // Fixed summation: Low L + Mid L -> Left speaker, Low R + Mid R -> Right speaker.
        canvas.drawLine(bandXs[0], midY + boxHeight / 2f, speakerXs[0], bottomY - boxHeight / 2f, sumLinePaint)
        canvas.drawLine(bandXs[2], midY + boxHeight / 2f, speakerXs[0], bottomY - boxHeight / 2f, sumLinePaint)
        canvas.drawLine(bandXs[1], midY + boxHeight / 2f, speakerXs[1], bottomY - boxHeight / 2f, sumLinePaint)
        canvas.drawLine(bandXs[3], midY + boxHeight / 2f, speakerXs[1], bottomY - boxHeight / 2f, sumLinePaint)

        inputLabels.forEachIndexed { i, label -> drawBox(canvas, inputXs[i], topY, label) }
        bandLabels.forEachIndexed { i, label -> drawBox(canvas, bandXs[i], midY, label) }
        speakerLabels.forEachIndexed { i, label -> drawBox(canvas, speakerXs[i], bottomY, label) }

        canvas.drawText("routing matrix -- adjustable below", w / 2f, topY + rowGap / 2f + 3.5f * density, captionPaint)
        canvas.drawText("fixed sum -> final stereo output", w / 2f, midY + rowGap / 2f + 3.5f * density, captionPaint)
    }

    private fun centeredXs(totalWidth: Float, count: Int): List<Float> {
        val step = totalWidth / (count + 1)
        return (1..count).map { it * step }
    }

    private fun drawBox(canvas: Canvas, cx: Float, cy: Float, label: String) {
        val rect = RectF(cx - boxWidth / 2f, cy - boxHeight / 2f, cx + boxWidth / 2f, cy + boxHeight / 2f)
        canvas.drawRoundRect(rect, 5f * density, 5f * density, boxFillPaint)
        canvas.drawRoundRect(rect, 5f * density, 5f * density, boxStrokePaint)
        canvas.drawText(label, cx, cy + 3.5f * density, boxTextPaint)
    }
}
