package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Flat AMOLED screen mask used on the home page. It deliberately has no fake lighting,
 * reflection or texture: only pure black glass and a restrained 3dp dark-metal edge.
 */
class HomeAmoledSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.rgb(70, 73, 77)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = bezel.strokeWidth / 2f
        val r = 7f * density
        val rect = RectF(half, half, width - half, height - half)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, bezel)
    }
}

/**
 * Crisp vector-drawn home navigation artwork. Click handling remains in the transparent Views
 * above this layer, so visuals and navigation stay independently testable.
 */
class HomeDashboardTilesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val tileFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 12, 14) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.rgb(88, 92, 98)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val neutral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(225, 228, 232)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(188, 193, 200)
        textAlign = Paint.Align.CENTER
    }

    private val labels = arrayOf("PEQ", "Delay/Gains", "Xovers", "Compressor", "Allpass", "Settings", "More")
    private val colors = intArrayOf(
        Color.rgb(0, 166, 255),
        Color.rgb(30, 205, 115),
        Color.rgb(255, 190, 0),
        Color.rgb(245, 45, 55),
        Color.rgb(178, 55, 238),
        Color.rgb(170, 175, 182),
        Color.rgb(170, 175, 182),
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cellW = width / 7f
        val tile = min(cellW * .76f, height * .92f)
        val top = (height - tile) / 2f
        val radius = tile * .105f

        repeat(7) { i ->
            val left = cellW * i + (cellW - tile) / 2f
            val rect = RectF(left, top, left + tile, top + tile)
            canvas.drawRoundRect(rect, radius, radius, tileFill)
            canvas.drawRoundRect(rect, radius, radius, rim)

            accent.color = colors[i]
            val inset = 4f * density
            val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            canvas.drawRoundRect(inner, radius * .82f, radius * .82f, accent)

            val art = RectF(
                rect.left + tile * .13f,
                rect.top + tile * .12f,
                rect.right - tile * .13f,
                rect.top + tile * .68f,
            )
            when (i) {
                0 -> drawPeq(canvas, art)
                1 -> drawDelay(canvas, art)
                2 -> drawXovers(canvas, art)
                3 -> drawCompressor(canvas, art)
                4 -> drawAllpass(canvas, art)
                5 -> drawSettings(canvas, art)
                6 -> drawMore(canvas, art)
            }

            text.textSize = if (i == 1 || i == 3) tile * .092f else tile * .105f
            canvas.drawText(labels[i], rect.centerX(), rect.bottom - tile * .095f, text)
        }
    }

    private fun drawPeq(canvas: Canvas, r: RectF) {
        drawEqBell(canvas, r, .18f, .36f, .22f, Color.rgb(206, 63, 223))
        drawEqBell(canvas, r, .48f, .24f, .25f, Color.rgb(0, 166, 255))
        drawEqBell(canvas, r, .76f, .40f, .22f, Color.rgb(52, 210, 120))
        drawEqBell(canvas, r, .58f, .78f, .24f, Color.rgb(255, 183, 0), negative = true)
    }

    private fun drawEqBell(canvas: Canvas, r: RectF, cx: Float, cy: Float, widthF: Float, color: Int, negative: Boolean = false) {
        val p = Path()
        val paint = Paint(accent).apply { this.color = color }
        val baseY = r.top + r.height() * .60f
        val amp = r.height() * .35f
        val centerX = r.left + r.width() * cx
        val spread = r.width() * widthF
        var first = true
        for (n in 0..30) {
            val x = r.left + r.width() * n / 30f
            val dx = (x - centerX) / spread
            val bell = kotlin.math.exp((-3.2f * dx * dx).toDouble()).toFloat()
            val y = baseY + (if (negative) amp else -amp) * bell
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
        canvas.drawPath(p, paint)
        canvas.drawCircle(centerX, r.top + r.height() * cy, r.width() * .035f, paint)
    }

    private fun drawDelay(canvas: Canvas, r: RectF) {
        val base = r.bottom - r.height() * .18f
        canvas.drawLine(r.left, base, r.right, base, neutral)
        val x1 = r.left + r.width() * .28f
        val x2 = r.left + r.width() * .76f
        drawImpulse(canvas, x1, base, r.height() * .72f, Color.WHITE)
        drawImpulse(canvas, x2, base, r.height() * .48f, Color.rgb(30, 205, 115))
        val y = r.top + r.height() * .18f
        neutral.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
        canvas.drawLine(x1 + r.width() * .07f, y, x2 - r.width() * .05f, y, neutral)
        neutral.pathEffect = null
        canvas.drawLine(x2 - r.width() * .10f, y - r.height() * .08f, x2 - r.width() * .02f, y, neutral)
        canvas.drawLine(x2 - r.width() * .10f, y + r.height() * .08f, x2 - r.width() * .02f, y, neutral)
    }

    private fun drawImpulse(canvas: Canvas, x: Float, base: Float, h: Float, color: Int) {
        val p = Paint(accent).apply { this.color = color }
        val path = Path()
        path.moveTo(x - 10f * density, base)
        path.lineTo(x - 3f * density, base)
        path.lineTo(x, base - h)
        path.lineTo(x + 4f * density, base)
        path.lineTo(x + 12f * density, base)
        canvas.drawPath(path, p)
    }

    private fun drawXovers(canvas: Canvas, r: RectF) {
        val midY = r.centerY()
        val splitX = r.left + r.width() * .36f
        canvas.drawLine(r.left, midY, splitX, midY, neutral)
        drawBranch(canvas, splitX, midY, r.right, r.top + r.height() * .18f, Color.rgb(0, 166, 255))
        drawBranch(canvas, splitX, midY, r.right, midY, Color.rgb(255, 190, 0))
        drawBranch(canvas, splitX, midY, r.right, r.bottom - r.height() * .14f, Color.rgb(255, 115, 0))
    }

    private fun drawBranch(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        val p = Path()
        p.moveTo(x0, y0)
        val xm = x0 + (x1 - x0) * .55f
        p.cubicTo(x0 + (x1 - x0) * .20f, y0, xm - (x1 - x0) * .10f, y1, xm, y1)
        p.lineTo(x1, y1)
        canvas.drawPath(p, Paint(accent).apply { this.color = color })
    }

    private fun drawCompressor(canvas: Canvas, r: RectF) {
        val center = r.centerY()
        val p = Path()
        val points = 56
        for (i in 0..points) {
            val t = i / points.toFloat()
            val x = r.left + r.width() * t
            val envelope = if (t < .48f) (1f - t * .6f) else .35f
            val amp = r.height() * .42f * envelope
            val y = center - sin((t * PI.toFloat() * 10f).toDouble()).toFloat() * amp
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        canvas.drawPath(p, neutral)
        val red = Paint(accent).apply { color = Color.rgb(245, 45, 55) }
        val p2 = Path()
        for (i in 26..points) {
            val t = i / points.toFloat()
            val x = r.left + r.width() * t
            val amp = r.height() * .18f
            val y = center - sin((t * PI.toFloat() * 10f).toDouble()).toFloat() * amp
            if (i == 26) p2.moveTo(x, y) else p2.lineTo(x, y)
        }
        canvas.drawPath(p2, red)
    }

    private fun drawAllpass(canvas: Canvas, r: RectF) {
        drawSine(canvas, r, Color.rgb(225, 228, 232), 0f)
        drawSine(canvas, r, Color.rgb(178, 55, 238), PI.toFloat() * .55f)
    }

    private fun drawSine(canvas: Canvas, r: RectF, color: Int, phase: Float) {
        val path = Path()
        for (i in 0..48) {
            val t = i / 48f
            val x = r.left + r.width() * t
            val y = r.centerY() - sin((t * PI.toFloat() * 2f + phase).toDouble()).toFloat() * r.height() * .32f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, Paint(accent).apply { this.color = color })
    }

    private fun drawSettings(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val outer = min(r.width(), r.height()) * .26f
        val inner = outer * .43f
        neutral.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, outer, neutral)
        canvas.drawCircle(cx, cy, inner, neutral)
        repeat(8) { i ->
            val angle = i * PI.toFloat() / 4f
            val cosA = kotlin.math.cos(angle.toDouble()).toFloat()
            val sinA = kotlin.math.sin(angle.toDouble()).toFloat()
            val x0 = cx + cosA * outer
            val y0 = cy + sinA * outer
            val x1 = cx + cosA * outer * 1.34f
            val y1 = cy + sinA * outer * 1.34f
            canvas.drawLine(x0, y0, x1, y1, neutral)
        }
    }

    private fun drawMore(canvas: Canvas, r: RectF) {
        val cy = r.centerY()
        val rad = min(r.width(), r.height()) * .055f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 228, 232)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(r.left + r.width() * .30f, cy, rad, fill)
        canvas.drawCircle(r.centerX(), cy, rad, fill)
        canvas.drawCircle(r.left + r.width() * .70f, cy, rad, fill)
    }
}
