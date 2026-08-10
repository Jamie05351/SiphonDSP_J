package app.siphondsp.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/** Compact brushed-aluminium slider thumb that sits inline with the track. */
class BrushedMetalThumbDrawable(
    private val widthPx: Int,
    private val heightPx: Int,
) : Drawable() {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, heightPx / 10f)
        color = Color.rgb(228, 233, 238)
    }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
    }

    override fun getIntrinsicWidth(): Int = widthPx
    override fun getIntrinsicHeight(): Int = heightPx

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        val radius = heightPx * .16f
        body.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            intArrayOf(
                Color.rgb(116, 124, 132),
                Color.rgb(222, 226, 230),
                Color.rgb(151, 158, 166),
                Color.rgb(235, 238, 241),
                Color.rgb(105, 112, 120),
            ),
            floatArrayOf(0f, .18f, .48f, .72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(r, radius, radius, body)
        body.shader = null

        var x = r.left + 2f
        var line = 0
        while (x < r.right - 1f) {
            brush.color = if (line % 3 == 0) Color.argb(46, 255, 255, 255) else Color.argb(30, 48, 53, 58)
            canvas.drawLine(x, r.top + 2f, x, r.bottom - 2f, brush)
            x += 2f
            line++
        }
        canvas.drawRoundRect(r, radius, radius, edge)
    }

    override fun setAlpha(alpha: Int) {
        body.alpha = alpha
        edge.alpha = alpha
        brush.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        body.colorFilter = colorFilter
        edge.colorFilter = colorFilter
        brush.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
