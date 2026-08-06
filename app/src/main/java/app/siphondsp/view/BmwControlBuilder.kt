package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.siphondsp.R
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.LabelFormatter
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

    fun sectionCard(title: String, build: BmwControlBuilder.() -> Unit) = section(title, wrapInCard = true, build)

    /** Same as [sectionCard] but without its own MaterialCardView -- for hosts that already
     *  provide the outer card themselves (e.g. NativeBmwDspCardFragment, embedded inline inside
     *  a MaterialCardView from fragment_dsp.xml; nesting sectionCard's card in there would
     *  double up the border/corner chrome). */
    fun flatSection(title: String, build: BmwControlBuilder.() -> Unit) = section(title, wrapInCard = false, build)

    private fun section(title: String, wrapInCard: Boolean, build: BmwControlBuilder.() -> Unit) {
        root.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setPadding(dp(4), dp(14), dp(4), dp(8))
        })
        if (wrapInCard) {
            val card = createCard()
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(12))
            }
            currentContent = content
            build()
            card.addView(content)
            root.addView(card, cardParams(dp(4)))
        } else {
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(0), dp(4), dp(4))
            }
            currentContent = content
            build()
            root.addView(content, cardParams(dp(4)))
        }
    }

    fun addSwitchRow(title: String, subtitle: String?, index: Int, offLabel: String = "OFF", onLabel: String = "ON") {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        row.addView(
            labelBlock(title, subtitle),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(createSwitch(index, offLabel, onLabel))
        currentContent.addView(row)
    }

    /** One option in an [addChoiceRow]. [value] is what gets written to `values[index]` --
     *  not necessarily 0/1/2/... (e.g. all-pass section order is stored as 1 or 2). */
    data class ChoiceOption(val label: String, val value: Float)

    /** Full-width label-above-choices row for a value with more than two named options
     *  (e.g. channel isolation: Both/Mute L/Mute R). Segmented like [addSwitchRow] but the
     *  choices sit on their own line below the title since 3-4 of them won't fit beside it. */
    fun addChoiceRow(title: String, subtitle: String?, index: Int, options: List<ChoiceOption>) {
        addChoiceRow(title, subtitle, options, current = { values[index] }) { chosen ->
            values[index] = chosen
            onChanged(values)
        }
    }

    /** Same as the [index]-bound overload above but for transient UI state that isn't stored
     *  in [values] at all -- e.g. which all-pass output channel is currently being edited. */
    fun addChoiceRow(title: String, subtitle: String?, options: List<ChoiceOption>, current: () -> Float, onSelect: (Float) -> Unit) {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        block.addView(labelBlock(title, subtitle))

        val group = MaterialButtonToggleGroup(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply {
                topMargin = dp(6)
            }
            isSingleSelection = true
            isSelectionRequired = true
        }
        val buttons = options.map { option ->
            createSegmentButton(option.label).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
            }
        }
        buttons.forEach { group.addView(it) }

        val currentValue = current()
        val closestIndex = options.indices.minBy { kotlin.math.abs(options[it].value - currentValue) }
        group.check(buttons[closestIndex].id)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || loading) return@addOnButtonCheckedListener
            val chosen = buttons.indexOfFirst { it.id == checkedId }
            if (chosen < 0) return@addOnButtonCheckedListener
            onSelect(options[chosen].value)
        }

        block.addView(group)
        currentContent.addView(block)
    }

    /** Tappable row with no bound value -- e.g. "Reset to stereo defaults". */
    fun addActionRow(title: String, subtitle: String?, onClick: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setBackgroundResource(resolveDrawableResId(android.R.attr.selectableItemBackground))
            setOnClickListener { onClick() }
        }
        row.addView(
            labelBlock(title, subtitle),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        currentContent.addView(row)
    }

    /** A nested content region inside the current section card whose rows can later be torn
     *  down and rebuilt against different [values] indices -- for fields whose bound index
     *  depends on other transient UI state (e.g. which all-pass output channel is selected)
     *  rather than being fixed at build time like every other row type here. */
    fun subContainer(build: BmwControlBuilder.() -> Unit): LinearLayout {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rebuildInto(container, build)
        currentContent.addView(container)
        return container
    }

    /** Clears [container] and re-runs [build] into it, with row-adding calls (addSwitchRow etc.)
     *  scoped to target it instead of the enclosing section card. Pair with [subContainer]. */
    fun rebuildInto(container: LinearLayout, build: BmwControlBuilder.() -> Unit) {
        container.removeAllViews()
        val previousContent = currentContent
        currentContent = container
        build()
        currentContent = previousContent
    }

    /** Full-width fader row: label + tappable value on one line, a full-width slider below it
     *  (matching the Output Control slider style/size -- see preference_materialslider.xml).
     *  [min]/[max]/[step] are in the raw units stored in [values]; [scale] only affects what's
     *  displayed/typed (e.g. a -2f..2f raw routing coefficient shown/edited as -200..200 "%"). */
    fun addSliderRow(label: String, index: Int, min: Float, max: Float, step: Float, suffix: String, scale: Float = 1f) {
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
            valueText.text = "${format.format(values[index] * scale)}$suffix"
        }
        updateValue()
        labelRow.addView(valueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        block.addView(labelRow)

        val slider = Slider(context).apply {
            // Match Widget.SiphonDSP.DspSlider (styles_dsp_controls.xml) so BMW screens'
            // sliders look the same as the Output Control ones -- applied here in code
            // rather than via a style resource since this Slider is built programmatically.
            isTickVisible = false
            labelBehavior = LabelFormatter.LABEL_GONE
            trackHeight = dp(10)
            trackActiveTintList = ColorStateList.valueOf(resolveColor(androidx.appcompat.R.attr.colorPrimary))
            trackInactiveTintList = ColorStateList.valueOf(resolveColor(com.google.android.material.R.attr.colorSurfaceVariant))
            thumbTintList = ColorStateList.valueOf(resolveColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            thumbStrokeColor = ColorStateList.valueOf(resolveColor(androidx.appcompat.R.attr.colorPrimary))
            thumbStrokeWidth = dp(2).toFloat()
            thumbWidth = dp(14)
            thumbHeight = dp(30)
            thumbTrackGapSize = dp(2)
            trackInsideCornerSize = dp(3)
            haloRadius = dp(22)

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
                "${format.format(min * scale)}–${format.format(max * scale)}",
                format.format(values[index] * scale),
                true,
                suffix,
            ) { entered ->
                val parsed = entered?.toFloatOrNull() ?: return@showInputAlert
                val clamped = (parsed / scale).coerceIn(min, max)
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

    /** Matches the Output Control segmented style -- see preference_materialswitch.xml
     *  and Widget.SiphonDSP.SegmentedButton -- so BMW gain/crossover switches look the same. */
    private fun createSwitch(index: Int, offLabel: String, onLabel: String): MaterialButtonToggleGroup {
        val offButton = createSegmentButton(offLabel)
        val onButton = createSegmentButton(onLabel)

        return MaterialButtonToggleGroup(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
            isSingleSelection = true
            isSelectionRequired = true

            addView(offButton)
            addView(onButton)

            check(if (values[index] >= .5f) onButton.id else offButton.id)

            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || loading) return@addOnButtonCheckedListener
                values[index] = if (checkedId == onButton.id) 1f else 0f
                onChanged(values)
            }
        }
    }

    private fun createSegmentButton(label: String) =
        MaterialButton(ContextThemeWrapper(context, R.style.Widget_SiphonDSP_SegmentedButton), null, 0).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(32))

            // Set explicitly (not just via the style above) since these are the colors the
            // visible OFF/ON contrast depends on -- see selector_segmented_button_background.xml.
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.selector_segmented_button_background)
            setTextColor(ContextCompat.getColorStateList(context, R.color.selector_segmented_button_text))
            strokeColor = ColorStateList.valueOf(resolveColor(com.google.android.material.R.attr.colorOutline))
            strokeWidth = dp(1)
            cornerRadius = dp(8)
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
