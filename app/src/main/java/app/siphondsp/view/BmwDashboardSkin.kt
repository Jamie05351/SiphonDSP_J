package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Shared visual skin for the dedicated BMW DSP workspaces. */
object BmwDashboardSkin {
    const val LIGHT_BLUE = 0xFF46B5E8.toInt()
    const val M_BLUE = 0xFF135BA7.toInt()
    const val M_RED = 0xFFE32B3B.toInt()
    const val PANEL_TOP = 0xFF20262D.toInt()
    const val PANEL_BOTTOM = 0xFF101419.toInt()

    fun brushedPanelDrawable(): Drawable = BrushedMetalDrawable()

    fun addMAccent(parent: LinearLayout) {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(LIGHT_BLUE, M_BLUE, M_RED).forEach { colour ->
            row.addView(View(context).apply { setBackgroundColor(colour) }, LinearLayout.LayoutParams(dp(context, 6), dp(context, 18)).apply {
                marginEnd = dp(context, 2)
            })
        }
        row.addView(TextView(context).apply {
            text = "M"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(context, 4), 0, 0, 0)
        })
        parent.addView(row)
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).roundToInt()

    private class BrushedMetalDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }

        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                intArrayOf(PANEL_TOP, 0xFF171C22.toInt(), PANEL_BOTTOM),
                floatArrayOf(0f, .45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, paint)
            paint.shader = null

            // Fine horizontal grain and a faint diagonal highlight produce the automotive
            // brushed-metal impression without requiring a bitmap texture asset.
            var y = b.top
            var index = 0
            while (y < b.bottom) {
                val alpha = if (index % 3 == 0) 22 else 10
                linePaint.color = Color.argb(alpha, 205, 215, 225)
                canvas.drawLine(b.left.toFloat(), y.toFloat(), b.right.toFloat(), y.toFloat(), linePaint)
                y += 3
                index++
            }

            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                intArrayOf(Color.argb(34, 255, 255, 255), Color.TRANSPARENT, Color.argb(28, 0, 0, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
}
