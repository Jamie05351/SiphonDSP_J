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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reusable builder for the small BMW-DSP control screens: Gain & Limiter,
 * Delay & Polarity, and the Parametric EQ graph's Crossovers/Tilt tabs. Lifted
 * out of the former NativeBmwDspBottomSheet so all destinations that edit the
 * same [values] FloatArray (see NativeBmwDspValues) share one rendering style
 * instead of duplicating it.
 *
 * [sectionCard]'s builder lambda has [BmwControlBuilder] itself as receiver
 * (not the section's LinearLayout) so addSwitchRow/addSliderRow calls read
 * the same as before with no explicit receiver at call sites --
 * the currently-open section's container is tracked internally instead.
 *
 * [addSliderRow] renders a full-width fader row (label + tappable numeric value
 * on one line, a full-width Slider below) using the app's global slider style
 * (see Widget.SiphonDSP.Slider) -- the same look as every other slider in the
 * app, sized to actually be draggable rather than squeezed into a narrow row.
 */
class BmwControlBuilder(
    private val context: Context,
    private val root: LinearLayout,
    private val values: FloatArray,
    private val onChanged: (FloatArray) -> Unit,
) {
    private val format = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private lateinit var currentContent: LinearLayout

    /** Set true while populating initial values so listeners don't fire onChanged. */
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

    /** Full-width fader row: label + tappable value on one line, a full-width slider below it
     *  (matching the Output Control slider style/size -- see preference_materialslider.xml). */
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

        val valueText = TextView(context).apply {
            textSize = 13f
            gravity = Gravity.END
            maxLines = 1
            minWidth = dp(56)
            isClickable = true
            isFocusable = true
            setBackgroundResource(resolveDrawableResId(android.R.attr.selectableItemBackground))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        fun updateValue() {
            valueText.text = "${format.format(values[index])}$suffix"
        }
        updateValue()
        labelRow.addView(valueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        block.addView(labelRow)

        val slider = Slider(context).apply {
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
        block.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
                updateValue()
                onChanged(values)
            }
        }

        currentContent.addView(block)
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
