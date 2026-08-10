package app.siphondsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.min

/**
 * CompoundButton-compatible power glyph used anywhere legacy code expects a MaterialSwitch.
 * It deliberately keeps MaterialSwitch behaviour/listeners while rendering only a power icon:
 * red when unchecked and BMW light blue when checked.
 */
class PowerIconSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle,
) : MaterialSwitch(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f * density
    }
    private val arc = RectF()

    init {
        text = null
        showText = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        thumbDrawable = null
        trackDrawable = null
        setPadding(0, 0, 0, 0)
        isClickable = true
        isFocusable = true
        contentDescription = contentDescription ?: "Power"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (42f * density).toInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size * 0.29f
        paint.color = if (isChecked) BmwDashboardSkin.LIGHT_BLUE else BmwDashboardSkin.M_RED
        paint.setShadowLayer(5f * density, 0f, 0f, paint.color)
        setLayerType(LAYER_TYPE_SOFTWARE, paint)

        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, -40f, 260f, false, paint)
        canvas.drawLine(cx, cy - radius * 1.35f, cx, cy - radius * 0.12f, paint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
