package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import app.siphondsp.R
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

/** BMW dashboard renderer shared by Routing, Gains & Delay and Crossovers & Tilt. */
class CrossoverDashboardBuilder(
    private val context: Context,
    private val root: LinearLayout,
    private val values: FloatArray,
    private val onChanged: (FloatArray) -> Unit,
) {
    private val format = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private lateinit var currentContent: LinearLayout

    private val accentBlue = BmwDashboardSkin.LIGHT_BLUE
    private val inactiveTrack = Color.rgb(98, 104, 112)
    private val thumbFillTop = Color.rgb(248, 249, 251)
    private val thumbFillBottom = Color.rgb(163, 169, 177)
    private val thumbStroke = Color.rgb(110, 116, 123)
    private val segmentIdle = Color.rgb(20, 23, 28)
    private val segmentStroke = Color.rgb(67, 73, 82)
    private val divider = Color.rgb(47, 53, 61)

    fun dashboardPanel(
        title: String,
        subtitle: String? = null,
        build: CrossoverDashboardBuilder.() -> Unit,
    ) {
        val card = MaterialCardView(context).apply {
            radius = dp(7).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.rgb(62, 72, 84)
            setCardBackgroundColor(Color.TRANSPARENT)
            background = BmwDashboardSkin.brushedPanelDrawable()
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(24), dp(20))
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        BmwDashboardSkin.addMAccent(titleRow)
        content.addView(titleRow)

        if (!subtitle.isNullOrBlank()) {
            content.addView(TextView(context).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Color.rgb(178, 187, 198))
                setPadding(0, dp(2), 0, dp(12))
            })
        } else {
            content.addView(space(10))
        }

        currentContent = content
        build()
        card.addView(content)
        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
                topMargin = dp(8)
                bottomMargin = dp(10)
            },
        )
    }

    fun sectionCard(title: String, subtitle: String? = null, build: CrossoverDashboardBuilder.() -> Unit) {
        dashboardPanel(title, subtitle, build)
    }

    /** Embeds an arbitrary view (e.g. an illustrative diagram) inside the current section card. */
    fun addCustomView(view: View, topMarginDp: Int = 4, bottomMarginDp: Int = 10) {
        currentContent.addView(
            view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(topMarginDp)
                bottomMargin = dp(bottomMarginDp)
            },
        )
    }

    fun sectionHeader(title: String) {
        if (currentContent.childCount > 2) currentContent.addView(space(8))
        currentContent.addView(TextView(context).apply {
            text = title
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentBlue)
            setPadding(0, dp(2), 0, dp(7))
        })
        currentContent.addView(View(context).apply {
            setBackgroundColor(divider)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            bottomMargin = dp(4)
        })
    }

    fun addSegmentedSwitchRow(
        title: String,
        subtitle: String?,
        index: Int,
        offLabel: String = "OFF",
        onLabel: String = "ON",
        mirrorIndices: IntArray = intArrayOf(),
    ) {
        val row = createRow()
        row.addView(labelBlock(title, subtitle), labelParams())

        val controlSlot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }

        if (offLabel == "OFF" && onLabel == "ON") {
            val toggle = MaterialSwitch(context).apply {
                isChecked = values[index] >= .5f
                contentDescription = title
                showText = false
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                thumbTintList = checkedColorStateList(accentBlue, Color.rgb(170, 177, 184))
                trackTintList = checkedColorStateList(Color.rgb(32, 78, 111), Color.rgb(45, 50, 57))
                setOnCheckedChangeListener { _, checked ->
                    writeValue(index, mirrorIndices, if (checked) 1f else 0f)
                }
            }
            controlSlot.addView(toggle)
        } else {
            val group = MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
            val off = segmentButton(offLabel)
            val on = segmentButton(onLabel)
            group.addView(off, LinearLayout.LayoutParams(dp(80), dp(34)))
            group.addView(on, LinearLayout.LayoutParams(dp(80), dp(34)))
            group.check(if (values[index] >= .5f) on.id else off.id)
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                writeValue(index, mirrorIndices, if (checkedId == on.id) 1f else 0f)
            }
            controlSlot.addView(group)
        }

        row.addView(controlSlot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(10)
        })
        row.addView(space(VALUE_WIDTH_DP))
        addRow(row)
    }

    fun addSliderRow(
        label: String,
        index: Int,
        min: Float,
        max: Float,
        step: Float,
        suffix: String,
        displayScale: Float = 1f,
        mirrorIndices: IntArray = intArrayOf(),
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(34)
        }

        val valueText = createInlineValueText(values[index] * displayScale, suffix)
        val labelValue = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(singleLineLabel(label))
            addView(valueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            })
        }
        topRow.addView(labelValue, labelParams())

        val slider = Slider(context).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = values[index].coerceIn(min, max)
            trackHeight = dp(8)
            thumbWidth = dp(22)
            thumbHeight = dp(22)
            setTrackActiveTintList(ColorStateList.valueOf(accentBlue))
            setTrackInactiveTintList(ColorStateList.valueOf(inactiveTrack))
            setHaloTintList(ColorStateList.valueOf(Color.argb(42, 70, 181, 232)))
            setCustomThumbDrawable(createRoundThumb())
            addOnChangeListener { _, newValue, fromUser ->
                if (fromUser) {
                    values[index] = newValue
                    mirrorIndices.forEach { values[it] = newValue }
                    updateValueBox(valueText, newValue * displayScale, suffix)
                    onChanged(values)
                }
            }
        }

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
                mirrorIndices.forEach { values[it] = stored }
                slider.value = stored
                updateValueBox(valueText, stored * displayScale, suffix)
                onChanged(values)
            }
        }

        topRow.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(4)
        })
        container.addView(topRow)

        val tickRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tickRow.addView(space(LABEL_WIDTH_DP + 14))
        tickRow.addView(tickLabel(format.format(min * displayScale), Gravity.START), tickParams())
        tickRow.addView(tickLabel(format.format((min + max) / 2f * displayScale), Gravity.CENTER_HORIZONTAL), tickParams())
        tickRow.addView(tickLabel(format.format(max * displayScale), Gravity.END), tickParams())
        container.addView(tickRow)

        currentContent.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** One LOW PASS / HIGH PASS mini-card: corner frequency (tap to edit, same numeric-entry
     *  dialog addSliderRow's value uses) plus a decorative rolloff sparkline. [slopeIndex] null
     *  means there's no real topology switch backing a slope choice for this band (e.g. the
     *  mid-band highpass is a fixed-order filter at the native layer) -- the slope reads as a
     *  fixed, non-interactive label instead of a working dropdown in that case. */
    class CrossoverBandSpec(
        val title: String,
        val freqIndex: Int,
        val freqMin: Float,
        val freqMax: Float,
        @DrawableRes val curveDrawableRes: Int,
        val freqMirrorIndices: IntArray = intArrayOf(),
        val slopeIndex: Int? = null,
        val slopeMirrorIndices: IntArray = intArrayOf(),
        val slopeOptions: List<Pair<String, Float>> = listOf("18 dB/Oct" to 0f, "24 dB/Oct" to 1f),
        val fixedSlopeLabel: String = "24 dB/Oct",
    )

    /** Places two CrossoverBandSpec cards side by side, matching the reference "LOW PASS" /
     *  "HIGH PASS" panel style. */
    fun addCrossoverBandPair(low: CrossoverBandSpec, high: CrossoverBandSpec) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            crossoverBandCard(low),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
        )
        row.addView(
            crossoverBandCard(high),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) },
        )
        currentContent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })
    }

    private fun crossoverBandCard(spec: CrossoverBandSpec): View {
        val card = MaterialCardView(context).apply {
            radius = dp(6).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = segmentStroke
            setCardBackgroundColor(Color.rgb(16, 19, 24))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        content.addView(TextView(context).apply {
            text = spec.title
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(160, 168, 178))
            letterSpacing = 0.04f
        })
        content.addView(vspace(4))

        fun freqLabel() = "${values[spec.freqIndex].roundToInt()} Hz"
        val freqText = TextView(context).apply {
            text = freqLabel()
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentBlue)
            isClickable = true
            isFocusable = true
        }
        val freqRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(freqText)
            addView(space(8))
            addView(
                ImageView(context).apply { setImageResource(spec.curveDrawableRes) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        freqText.setOnClickListener {
            context.showInputAlert(
                android.view.LayoutInflater.from(context),
                spec.title,
                "${spec.freqMin.roundToInt()}–${spec.freqMax.roundToInt()}",
                values[spec.freqIndex].roundToInt().toString(),
                true,
                "Hz",
            ) { entered ->
                val parsed = entered?.toFloatOrNull() ?: return@showInputAlert
                val stored = parsed.coerceIn(spec.freqMin, spec.freqMax)
                values[spec.freqIndex] = stored
                spec.freqMirrorIndices.forEach { values[it] = stored }
                freqText.text = freqLabel()
                onChanged(values)
            }
        }
        content.addView(freqRow)
        content.addView(vspace(10))

        content.addView(TextView(context).apply {
            text = "SLOPE"
            textSize = 9.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(130, 138, 148))
            letterSpacing = 0.04f
        })
        content.addView(vspace(4))

        fun currentSlopeLabel(): String {
            val slopeIndex = spec.slopeIndex ?: return spec.fixedSlopeLabel
            val current = values[slopeIndex]
            return spec.slopeOptions.minByOrNull { kotlin.math.abs(it.second - current) }?.first
                ?: spec.slopeOptions.first().first
        }
        val slopeButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = currentSlopeLabel()
            textSize = 12f
            isAllCaps = false
            minHeight = dp(32)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(4)
            strokeColor = ColorStateList.valueOf(segmentStroke)
            backgroundTintList = ColorStateList.valueOf(segmentIdle)
            setTextColor(Color.rgb(220, 226, 232))
            iconGravity = MaterialButton.ICON_GRAVITY_END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        if (spec.slopeIndex != null) {
            slopeButton.icon = ContextCompat.getDrawable(context, R.drawable.ic_baseline_keyboard_arrow_down_24dp)
            slopeButton.setOnClickListener { anchor ->
                val popup = PopupMenu(context, anchor)
                spec.slopeOptions.forEachIndexed { i, (optionLabel, _) -> popup.menu.add(0, i, i, optionLabel) }
                popup.setOnMenuItemClickListener { item ->
                    val stored = spec.slopeOptions[item.itemId].second
                    values[spec.slopeIndex] = stored
                    spec.slopeMirrorIndices.forEach { values[it] = stored }
                    slopeButton.text = currentSlopeLabel()
                    onChanged(values)
                    true
                }
                popup.show()
            }
        } else {
            slopeButton.alpha = 0.55f
            slopeButton.isClickable = false
        }
        content.addView(slopeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(content)
        return card
    }

    private fun writeValue(index: Int, mirrorIndices: IntArray, value: Float) {
        values[index] = value
        mirrorIndices.forEach { values[it] = value }
        onChanged(values)
    }

    private fun createRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun addRow(row: LinearLayout) {
        currentContent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun labelParams() = LinearLayout.LayoutParams(dp(LABEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun singleLineLabel(label: String) = TextView(context).apply {
        text = label
        textSize = 13f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(Color.rgb(231, 235, 239))
    }

    private fun segmentButton(textValue: String) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        id = View.generateViewId()
        text = textValue
        textSize = 11f
        isAllCaps = true
        minHeight = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(6)
        strokeWidth = dp(1)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(6), 0, dp(6), 0)
        backgroundTintList = checkedColorStateList(Color.rgb(28, 70, 107), segmentIdle)
        strokeColor = checkedColorStateList(accentBlue, segmentStroke)
        setTextColor(checkedColorStateList(Color.WHITE, Color.rgb(180, 188, 197)))
    }

    private fun labelBlock(title: String, subtitle: String?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(singleLineLabel(title))
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(context).apply {
                text = subtitle
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.rgb(170, 179, 189))
            })
        }
    }

    /** Bold accent-coloured value reading next to a slider's label -- tap to edit, same as the
     *  old boxed value display, just without the box background. */
    private fun createInlineValueText(value: Float, suffix: String) = TextView(context).apply {
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        setTextColor(accentBlue)
        updateValueBox(this, value, suffix)
    }

    /** Min/center/max reading below a slider's track, aligned under it. */
    private fun tickLabel(text: String, alignment: Int) = TextView(context).apply {
        this.text = text
        textSize = 10f
        gravity = alignment
        setTextColor(Color.rgb(124, 132, 142))
    }

    private fun tickParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** Chrome-ball thumb: a light-to-dark vertical gradient on a circle reads as a shiny sphere
     *  without needing a real radial highlight, which GradientDrawable can't do directly. */
    private fun createRoundThumb() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = intArrayOf(thumbFillTop, thumbFillBottom)
        setStroke(dp(1), thumbStroke)
        setSize(dp(22), dp(22))
    }

    private fun checkedColorStateList(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked),
    )

    private fun updateValueBox(valueText: TextView, value: Float, suffix: String) {
        valueText.text = "${format.format(value)} $suffix".trim()
    }

    private fun space(widthDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(1))
    }

    private fun vspace(heightDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val LABEL_WIDTH_DP = 225
        private const val VALUE_WIDTH_DP = 88
    }
}
