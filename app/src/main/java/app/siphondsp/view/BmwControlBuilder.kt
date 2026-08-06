package app.siphondsp.view

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/** Reusable builder for the native BMW-DSP control screens. */
class BmwControlBuilder(
    private val context: Context,
    private val root: LinearLayout,
    private val values: FloatArray,
    private val onChanged: (FloatArray) -> Unit,
) {
    private val format = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private lateinit var currentContent: LinearLayout

    var loading = false

    fun sectionCard(title: String, build: BmwControlBuilder.() -> Unit) {
        root.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setPadding(dp(4), dp(14), dp(4), dp(8))
        })
        val card = createCard()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        currentContent = content
        build()
        card.addView(content)
        root.addView(card, cardParams(dp(4)))
    }

    fun addSwitchRow(title: String, subtitle: String?, index: Int) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        row.addView(
            labelBlock(title, subtitle),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(createSwitch(index))
        currentContent.addView(row)
    }

    fun addSliderRow(label: String, index: Int, min: Float, max: Float, step: Float, suffix: String) {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(
            TextView(context).apply {
                text = label
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val valueText = createValueText()
        fun updateValue() {
            valueText.text = "${format.format(values[index])}$suffix"
        }
        updateValue()
        labelRow.addView(valueText)
        block.addView(labelRow)

        val slider = createSlider(index, min, max, step, updateValue)
        block.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        configureNumericEntry(valueText, label, index, min, max, suffix, slider, updateValue)
        currentContent.addView(block)
    }

    /**
     * Routing fader with the source channel on the left and destination channel on the right.
     * It deliberately uses a heavier rail and a short rectangular handle so routing controls
     * are visually distinct from gain and delay controls.
     */
    fun addRoutingSliderRow(sourceLabel: String, destinationLabel: String, index: Int) {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(TextView(context).apply {
            text = sourceLabel
            textSize = 14f
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val destination = TextView(context).apply {
            text = destinationLabel
            textSize = 14f
            gravity = Gravity.END
            maxLines = 1
        }
        labelRow.addView(destination, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        block.addView(labelRow)

        val valueText = createValueText().apply { minWidth = dp(52) }
        fun updateValue() {
            valueText.text = "${format.format(values[index] * 100f)}%"
        }
        updateValue()

        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val slider = createSlider(index, -2f, 2f, .01f, updateValue).apply {
            trackHeight = dp(8)
            thumbWidth = dp(14)
            thumbHeight = dp(28)
            thumbRadius = dp(3)
        }
        sliderRow.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sliderRow.addView(valueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        block.addView(sliderRow)

        configureNumericEntry(valueText, "$sourceLabel → $destinationLabel", index, -2f, 2f, "%", slider, updateValue, displayScale = 100f)
        currentContent.addView(block)
    }

    fun addActionRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
        val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = title
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        currentContent.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        if (!subtitle.isNullOrBlank()) {
            currentContent.addView(TextView(context).apply {
                text = subtitle
                textSize = 11f
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(resolveColor(android.R.attr.textColorSecondary))
                setPadding(dp(8), dp(4), dp(8), 0)
            })
        }
    }

    private fun createSlider(index: Int, min: Float, max: Float, step: Float, updateValue: () -> Unit) =
        Slider(context).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = values[index].coerceIn(min, max)
            addOnChangeListener { _, newValue, fromUser ->
                if (fromUser && !loading) {
                    values[index] = newValue
                    updateValue()
                    onChanged(values)
                }
            }
        }

    private fun createValueText() = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.END
        maxLines = 1
        minWidth = dp(56)
        isClickable = true
        isFocusable = true
        setBackgroundResource(resolveDrawableResId(android.R.attr.selectableItemBackground))
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }

    private fun configureNumericEntry(
        valueText: TextView,
        label: String,
        index: Int,
        min: Float,
        max: Float,
        suffix: String,
        slider: Slider,
        updateValue: () -> Unit,
        displayScale: Float = 1f,
    ) {
        valueText.setOnClickListener {
            val displayMin = min * displayScale
            val displayMax = max * displayScale
            val displayValue = values[index] * displayScale
            context.showInputAlert(
                android.view.LayoutInflater.from(context),
                label,
                "${format.format(displayMin)}–${format.format(displayMax)}",
                format.format(displayValue),
                true,
                suffix,
            ) { entered ->
                val parsed = entered?.toFloatOrNull() ?: return@showInputAlert
                val clamped = (parsed / displayScale).coerceIn(min, max)
                values[index] = clamped
                slider.value = clamped
                updateValue()
                onChanged(values)
            }
        }
    }

    private fun createCard() = MaterialCardView(context).apply {
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = resolveColor(com.google.android.material.R.attr.colorOutline)
    }

    private fun createSwitch(index: Int) = SwitchCompat(context).apply {
        isChecked = values[index] >= .5f
        setOnCheckedChangeListener { _, checked ->
            if (!loading) {
                values[index] = if (checked) 1f else 0f
                onChanged(values)
            }
        }
    }

    private fun labelBlock(title: String, subtitle: String?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = title; textSize = 14f })
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(resolveColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(2), dp(12), 0)
            })
        }
    }

    private fun cardParams(bottomMargin: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.bottomMargin = bottomMargin
        }

    private fun resolveColor(attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun resolveDrawableResId(attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.resourceId
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
