package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
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

    fun styleWorkspace(root: View) {
        root.background = brushedPanelDrawable()
        styleTree(root)
    }

    /** Apply automotive chrome to existing XML-driven cards and controls without touching behavior. */
    fun styleTree(root: View) {
        when (root) {
            is MaterialCardView -> {
                root.radius = dp(root.context, 7).toFloat()
                root.cardElevation = 0f
                root.strokeWidth = dp(root.context, 1)
                root.strokeColor = Color.rgb(61, 71, 82)
                root.setCardBackgroundColor(Color.argb(215, 18, 23, 29))
            }
            is Slider -> styleSlider(root)
            is MaterialSwitch -> styleSwitch(root)
            is SwitchCompat -> styleSwitch(root)
        }

        if (root is ViewGroup) {
            for (index in 0 until root.childCount) styleTree(root.getChildAt(index))
        }
    }

    private fun styleSlider(slider: Slider) {
        val context = slider.context
        slider.trackHeight = dp(context, 6)
        slider.thumbWidth = dp(context, 13)
        slider.thumbHeight = dp(context, 24)
        slider.setTrackActiveTintList(ColorStateList.valueOf(LIGHT_BLUE))
        slider.setTrackInactiveTintList(ColorStateList.valueOf(Color.rgb(31, 35, 41)))
        slider.setHaloTintList(ColorStateList.valueOf(Color.argb(42, 70, 181, 232)))
        slider.setCustomThumbDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.rgb(190, 196, 203))
            setStroke(dp(context, 1), Color.rgb(232, 236, 240))
            cornerRadius = dp(context, 1).toFloat()
            setSize(dp(context, 13), dp(context, 24))
        })
    }

    private fun styleSwitch(toggle: SwitchCompat) {
        toggle.thumbTintList = checkedColours(LIGHT_BLUE, Color.rgb(170, 177, 184))
        toggle.trackTintList = checkedColours(Color.rgb(32, 78, 111), Color.rgb(45, 50, 57))
    }

    private fun checkedColours(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked),
    )

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
