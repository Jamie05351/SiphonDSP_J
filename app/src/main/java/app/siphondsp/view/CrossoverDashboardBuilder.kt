package app.siphondsp.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Isolated BMW-style dashboard renderer for the Crossovers & Tilt prototype.
 * It intentionally does not modify BmwControlBuilder so the rest of the DSP UI
 * remains unchanged while this screen is evaluated on the head unit.
 */
class CrossoverDashboardBuilder(
    private val context: Context,
    private val root: LinearLayout,
    private val values: FloatArray,
    private val onChanged: (FloatArray) -> Unit,
) {
    private val format = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private lateinit var currentContent: LinearLayout

    fun sectionCard(title: String, subtitle: String? = null, build: CrossoverDashboardBuilder.() -> Unit) {
        val card = MaterialCardView(context).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = resolveColor(com.google.android.material.R.attr.colorOutline)
            setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        content.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
        })
        if (!subtitle.isNullOrBlank()) {
            content.addView(TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(2), 0, dp(10))
            })
        } else {
            content.setPadding(dp(18), dp(14), dp(18), dp(12))
        }
        currentContent = content
        build()
        card.addView(content)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
    }

    fun addSegmentedSwitchRow(title: String, subtitle: String?, index: Int) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(labelBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val group = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val off = segmentButton("OFF")
        val on = segmentButton("ON")
        group.addView(off, LinearLayout.LayoutParams(dp(86), dp(44)))
        group.addView(on, LinearLayout.LayoutParams(dp(86), dp(44)))
        val checked = if (values[index] >= .5f) on.id else off.id
        group.check(checked)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            values[index] = if (checkedId == on.id) 1f else 0f
            onChanged(values)
        }
        row.addView(group)
        currentContent.addView(row)
    }

    fun addSliderRow(label: String, index: Int, min: Float, max: Float, step: Float, suffix: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(0, dp(4), 0, dp(4))
        }

        row.addView(TextView(context).apply {
            text = label
            textSize = 13.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
        }, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))

        val slider = Slider(context).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = values[index].coerceIn(min, max)
            trackHeight = dp(9)
            thumbWidth = dp(14)
            thumbHeight = dp(30)
            addOnChangeListener { _, newValue, fromUser ->
                if (fromUser) {
                    values[index] = newValue
                    updateValueBox(valueText = tag as TextView, value = newValue, suffix = suffix)
                    onChanged(values)
                }
            }
        }
        row.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(12)
        })

        val valueText = createValueBox(values[index], suffix)
        slider.tag = valueText
        valueText.setOnClickListener {
            context.showInputAlert(
                android.view.LayoutInflater.from(context),
                label,
                "${format.format(min)}–${format.format(max)}",
                format.format(values[index]),
                true,
                suffix,
            ) { entered ->
                val parsed = entered?.toFloatOrNull() ?: return@showInputAlert
                val clamped = parsed.coerceIn(min, max)
                values[index] = clamped
                slider.value = clamped
                updateValueBox(valueText, clamped, suffix)
                onChanged(values)
            }
        }
        row.addView(valueText)
        currentContent.addView(row)
    }

    private fun segmentButton(textValue: String) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        id = View.generateViewId()
        text = textValue
        textSize = 12f
        isAllCaps = false
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(8)
        strokeWidth = dp(1)
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun labelBlock(title: String, subtitle: String?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 13.5f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
        })
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(context).apply {
                text = subtitle
                textSize = 10.5f
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(2), dp(12), 0)
            })
        }
    }

    private fun createValueBox(value: Float, suffix: String) = TextView(context).apply {
        minWidth = dp(88)
        minHeight = dp(40)
        gravity = Gravity.CENTER
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
        setBackgroundResource(app.siphondsp.R.drawable.background_dsp_value_box)
        updateValueBox(this, value, suffix)
    }

    private fun updateValueBox(valueText: TextView, value: Float, suffix: String) {
        valueText.text = "${format.format(value)} $suffix".trim()
    }

    private fun resolveColor(attribute: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else Color.TRANSPARENT
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
