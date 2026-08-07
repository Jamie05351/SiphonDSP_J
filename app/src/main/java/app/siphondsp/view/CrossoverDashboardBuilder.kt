package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
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

    private val accentBlue = Color.rgb(63, 174, 229)
    private val inactiveTrack = Color.rgb(55, 58, 66)
    private val thumbFill = Color.rgb(188, 195, 203)
    private val thumbStroke = Color.rgb(232, 236, 240)
    private val segmentIdle = Color.rgb(29, 29, 35)
    private val segmentStroke = Color.rgb(64, 67, 76)

    fun sectionCard(title: String, subtitle: String? = null, build: CrossoverDashboardBuilder.() -> Unit) {
        val card = MaterialCardView(context).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = resolveColor(com.google.android.material.R.attr.colorOutline)
            setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        content.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, dp(4))
        })
        if (!subtitle.isNullOrBlank()) {
            content.addView(TextView(context).apply {
                text = subtitle
                textSize = 10.5f
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, 0, 0, dp(6))
            })
        }
        currentContent = content
        build()
        card.addView(content)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    fun addSegmentedSwitchRow(
        title: String,
        subtitle: String?,
        index: Int,
        offLabel: String = "OFF",
        onLabel: String = "ON",
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(38)
            setPadding(0, dp(1), 0, dp(1))
        }
        row.addView(labelBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (offLabel == "OFF" && onLabel == "ON") {
            val toggle = MaterialSwitch(context).apply {
                isChecked = values[index] >= .5f
                contentDescription = title
                showText = false
                setOnCheckedChangeListener { _, checked ->
                    values[index] = if (checked) 1f else 0f
                    onChanged(values)
                }
            }
            row.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val group = MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
            val off = segmentButton(offLabel)
            val on = segmentButton(onLabel)
            group.addView(off, LinearLayout.LayoutParams(dp(54), dp(28)))
            group.addView(on, LinearLayout.LayoutParams(dp(54), dp(28)))
            val checked = if (values[index] >= .5f) on.id else off.id
            group.check(checked)
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                values[index] = if (checkedId == on.id) 1f else 0f
                onChanged(values)
            }
            row.addView(group)
        }
        currentContent.addView(row)
    }

    fun addSliderRow(
        label: String,
        index: Int,
        min: Float,
        max: Float,
        step: Float,
        suffix: String,
        displayScale: Float = 1f,
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50)
            setPadding(0, dp(2), 0, dp(2))
        }

        row.addView(TextView(context).apply {
            text = label
            textSize = 13.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.29f))

        val valueText = createValueBox(values[index] * displayScale, suffix)
        val slider = Slider(context).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = values[index].coerceIn(min, max)
            trackHeight = dp(7)
            thumbWidth = dp(12)
            thumbHeight = dp(26)
            setTrackActiveTintList(ColorStateList.valueOf(accentBlue))
            setTrackInactiveTintList(ColorStateList.valueOf(inactiveTrack))
            setHaloTintList(ColorStateList.valueOf(Color.argb(48, 63, 174, 229)))
            setCustomThumbDrawable(createRectangularThumb())
            addOnChangeListener { _, newValue, fromUser ->
                if (fromUser) {
                    values[index] = newValue
                    updateValueBox(valueText, newValue * displayScale, suffix)
                    onChanged(values)
                }
            }
        }
        row.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.71f).apply {
            marginStart = dp(4)
            marginEnd = dp(6)
        })

        valueText.setOnClickListener {
            context.showInputAlert(
                android.view.LayoutInflater.from(context),
                label,
                "${format.format(min * displayScale)}–${format.format(max * displayScale)}",
                format.format(values[index] * displayScale),
                true,
                suffix,
            ) { entered ->
                val parsed = entered?.toFloatOrNull() ?: return@showInputAlert
                val stored = (parsed / displayScale).coerceIn(min, max)
                values[index] = stored
                slider.value = stored
                updateValueBox(valueText, stored * displayScale, suffix)
                onChanged(values)
            }
        }
        row.addView(valueText, LinearLayout.LayoutParams(dp(70), dp(36)))
        currentContent.addView(row)
    }

    private fun segmentButton(textValue: String) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        id = View.generateViewId()
        text = textValue
        textSize = 11f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(5)
        strokeWidth = dp(1)
        setPadding(dp(4), 0, dp(4), 0)
        backgroundTintList = checkedColorStateList(accentBlue, segmentIdle)
        strokeColor = checkedColorStateList(accentBlue, segmentStroke)
        setTextColor(checkedColorStateList(Color.WHITE, resolveColor(com.google.android.material.R.attr.colorOnSurface)))
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
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
    }

    private fun createValueBox(value: Float, suffix: String) = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
        setBackgroundResource(app.siphondsp.R.drawable.background_dsp_value_box)
        updateValueBox(this, value, suffix)
    }

    private fun createRectangularThumb() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(thumbFill)
        setStroke(dp(1), thumbStroke)
        cornerRadius = dp(2).toFloat()
        setSize(dp(12), dp(26))
    }

    private fun checkedColorStateList(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(checked, unchecked),
    )

    private fun updateValueBox(valueText: TextView, value: Float, suffix: String) {
        valueText.text = "${format.format(value)} $suffix".trim()
    }

    private fun resolveColor(attribute: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else Color.TRANSPARENT
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
