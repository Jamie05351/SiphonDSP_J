package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.constraintlayout.widget.ConstraintLayout
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
    private val valueBoxBackground = Color.rgb(16, 19, 24)
    private val segmentIdle = Color.rgb(20, 23, 28)
    private val segmentStroke = Color.rgb(67, 73, 82)
    private val divider = Color.rgb(47, 53, 61)

    /** A compact vertical slider docked to a [dashboardPanel]'s right-hand border (e.g. Headroom
     *  on Gains & Delay), like a fader on the edge of a channel strip, instead of taking up space
     *  in the title row or a full row of its own further down the card. */
    class HeaderSliderSpec(
        val label: String,
        val index: Int,
        val min: Float,
        val max: Float,
        val step: Float,
        val suffix: String,
    )

    fun dashboardPanel(
        title: String,
        subtitle: String? = null,
        headerSlider: HeaderSliderSpec? = null,
        build: CrossoverDashboardBuilder.() -> Unit,
    ) {
        val card = MaterialCardView(context).apply {
            radius = dp(7).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.rgb(62, 72, 84)
            setCardBackgroundColor(Color.TRANSPARENT)
            background = BmwDashboardSkin.brushedPanelDrawable(context)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(24), dp(20))
        }

        // Blank title skips the row entirely -- for a page whose toolbar already shows this same
        // title (Gains & Delay), repeating it inside the card too just wastes vertical space.
        if (title.isNotBlank()) {
            content.addView(TextView(context).apply {
                text = title
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
        }
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

        // The whole card is a horizontal split when there's a header slider: the regular content
        // (title + build()) on the left filling whatever width is left, a vertical fader docked to
        // the card's own right-hand edge -- rather than a full-width row of its own, which is the
        // whole point (saves vertical space the page badly needs, per this page's fit-without-
        // scrolling requirement).
        if (headerSlider == null) {
            card.addView(content)
        } else {
            val outer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            outer.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            outer.addView(
                buildVerticalHeaderSlider(headerSlider),
                LinearLayout.LayoutParams(dp(VERTICAL_HEADER_SLIDER_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            card.addView(outer)
        }
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
        dashboardPanel(title, subtitle, build = build)
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

        // Title, slider, and value all on one row, always in that order: the title sits in its
        // own fixed-width column, the slider fills whatever room is left, and the value box sits
        // at the very end -- never in the middle of the row.
        val valueText = createBoxedValueText(values[index] * displayScale, suffix)
        topRow.addView(singleLineLabel(label), labelParams())

        val slider = Slider(context).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = snapToStep(values[index], min, max, step)
            trackHeight = dp(8)
            thumbWidth = dp(BmwDashboardSkin.SLIDER_THUMB_WIDTH_DP)
            thumbHeight = dp(BmwDashboardSkin.SLIDER_THUMB_HEIGHT_DP)
            setTrackActiveTintList(ColorStateList.valueOf(accentBlue))
            setTrackInactiveTintList(ColorStateList.valueOf(Color.BLACK))
            setHaloTintList(ColorStateList.valueOf(Color.argb(42, 70, 181, 232)))
            setCustomThumbDrawable(BmwDashboardSkin.sliderThumbDrawable(context))
            BmwDashboardSkin.applyTrackOutline(this)
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
                // Material Slider requires its value to land on a stepSize-aligned multiple of
                // valueFrom, or `slider.value = stored` below throws IllegalStateException.
                // Snap to the nearest step before storing/assigning rather than the raw typed
                // value, which is very unlikely to happen to already be aligned (eg. any
                // 2-decimal routing percentage against a .01f step is fine, but most other
                // fields have a coarser step than what a user can type).
                val raw = (parsed / displayScale).coerceIn(min, max)
                val stored = (min + ((raw - min) / step).roundToInt() * step).coerceIn(min, max)
                values[index] = stored
                mirrorIndices.forEach { values[it] = stored }
                slider.value = stored
                updateValueBox(valueText, stored * displayScale, suffix)
                onChanged(values)
            }
        }

        topRow.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
        })
        topRow.addView(valueText)
        container.addView(topRow)

        val tickRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tickRow.addView(space(LABEL_WIDTH_DP + 14))
        tickRow.addView(tickLabel(format.format(min * displayScale), Gravity.START), tickParams())
        tickRow.addView(tickLabel(format.format((min + max) / 2f * displayScale), Gravity.CENTER_HORIZONTAL), tickParams())
        tickRow.addView(tickLabel(format.format(max * displayScale), Gravity.END), tickParams())
        tickRow.addView(space(VALUE_WIDTH_DP + 14))
        container.addView(tickRow)

        currentContent.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Compact vertical label-slider-valuebox fader docked to a [dashboardPanel]'s right-hand
     *  border (see [HeaderSliderSpec]) -- label on top, slider filling the available height,
     *  value box (tap-to-edit) at the bottom. Doesn't call [BmwDashboardSkin.applyTrackOutline]:
     *  that helper insets top/bottom to reveal a *horizontal* track band, which is the wrong axis
     *  once the slider is vertical, so this fader keeps the plain black inactive track without the
     *  white outline rather than drawing a misaligned one. */
    private fun buildVerticalHeaderSlider(spec: HeaderSliderSpec): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        column.addView(TextView(context).apply {
            text = spec.label
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(210, 216, 222))
            gravity = Gravity.CENTER
        })
        column.addView(vspace(8))

        val valueText = createBoxedValueText(values[spec.index], spec.suffix)
        val slider = Slider(context).apply {
            setOrientation(LinearLayout.VERTICAL)
            valueFrom = spec.min
            valueTo = spec.max
            stepSize = spec.step
            value = snapToStep(values[spec.index], spec.min, spec.max, spec.step)
            trackHeight = dp(6)
            thumbWidth = dp(BmwDashboardSkin.SLIDER_THUMB_HEIGHT_DP)
            thumbHeight = dp(BmwDashboardSkin.SLIDER_THUMB_WIDTH_DP)
            setTrackActiveTintList(ColorStateList.valueOf(accentBlue))
            setTrackInactiveTintList(ColorStateList.valueOf(Color.BLACK))
            setHaloTintList(ColorStateList.valueOf(Color.argb(42, 70, 181, 232)))
            setCustomThumbDrawable(BmwDashboardSkin.sliderThumbDrawable(context))
            addOnChangeListener { _, newValue, fromUser ->
                if (fromUser) {
                    values[spec.index] = newValue
                    updateValueBox(valueText, newValue, spec.suffix)
                    onChanged(values)
                }
            }
        }
        column.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f))
        column.addView(vspace(8))
        column.addView(valueText)
        return column
    }

    /** Single-line [label][slider][boxed value] row, scaled down to fit inside a narrow channel
     *  card (see [addChannelCard]'s Gain row) -- same title-slider-valuebox order as
     *  [addSliderRow], just with the label in a narrower fixed column instead of [LABEL_WIDTH_DP]. */
    private fun buildMiniSliderRow(label: String, index: Int, min: Float, max: Float, step: Float, suffix: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(smallLabel(label), LinearLayout.LayoutParams(dp(ROW_LABEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))

        val valueText = createBoxedValueText(values[index], suffix)
        val slider = Slider(context).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = snapToStep(values[index], min, max, step)
            trackHeight = dp(6)
            thumbWidth = dp(BmwDashboardSkin.SLIDER_THUMB_WIDTH_DP)
            thumbHeight = dp(BmwDashboardSkin.SLIDER_THUMB_HEIGHT_DP)
            setTrackActiveTintList(ColorStateList.valueOf(accentBlue))
            setTrackInactiveTintList(ColorStateList.valueOf(Color.BLACK))
            setHaloTintList(ColorStateList.valueOf(Color.argb(42, 70, 181, 232)))
            setCustomThumbDrawable(BmwDashboardSkin.sliderThumbDrawable(context))
            addOnChangeListener { _, newValue, fromUser ->
                if (fromUser) {
                    values[index] = newValue
                    updateValueBox(valueText, newValue, suffix)
                    onChanged(values)
                }
            }
        }
        row.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        row.addView(valueText)

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
                val stored = (min + ((parsed.coerceIn(min, max) - min) / step).roundToInt() * step).coerceIn(min, max)
                values[index] = stored
                slider.value = stored
                updateValueBox(valueText, stored, suffix)
                onChanged(values)
            }
        }
        return row
    }

    /** Single-line [label][tap-to-edit value] row for [addChannelCard]'s Delay row -- no drag
     *  slider, matching the original delay diagram's own tap-only rationale (this is about showing
     *  *where* a delay applies, not fine adjustment). */
    private fun buildMiniValueRow(label: String, index: Int, min: Float, max: Float, suffix: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(smallLabel(label), LinearLayout.LayoutParams(dp(ROW_LABEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        val valueText = createBoxedValueText(values[index], suffix)
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
                val stored = parsed.coerceIn(min, max)
                values[index] = stored
                updateValueBox(valueText, stored, suffix)
                onChanged(values)
            }
        }
        row.addView(valueText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Single-line [label][NORMAL/INVERT toggle] row for [addChannelCard]'s Polarity row. */
    private fun buildMiniToggleRow(label: String, index: Int, mirrorIndices: IntArray, onToggled: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(smallLabel(label), LinearLayout.LayoutParams(dp(ROW_LABEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        val group = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val off = segmentButton("NORMAL")
        val on = segmentButton("INVERT")
        group.addView(off, LinearLayout.LayoutParams(0, dp(30), 1f))
        group.addView(on, LinearLayout.LayoutParams(0, dp(30), 1f))
        group.check(if (values[index] >= .5f) on.id else off.id)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            writeValue(index, mirrorIndices, if (checkedId == on.id) 1f else 0f)
            onToggled()
        }
        row.addView(group, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        return row
    }

    /** One "Left Mid"/"Right Mid"/"Left Low"/"Right Low" channel card for the Gains & Delay car
     *  diagram (see [addChannelDiagramSection]): a coloured title (matching that channel's
     *  connector-line colour) plus one compact single-line row each for Delay, Gain, and Polarity. */
    fun addChannelCard(
        title: String,
        accentColor: Int,
        delayIndex: Int, delayMin: Float, delayMax: Float,
        gainIndex: Int, gainMin: Float, gainMax: Float, gainStep: Float,
        polarityIndex: Int, polarityMirror: IntArray,
        // Low/mid polarity is one value shared by both the Left and Right card of that band (see
        // NativeBmwDspValues.INDEX_LOW_INVERT/INDEX_MID_INVERT), not 4 independent per-channel
        // flags -- so toggling it on one card also needs the OTHER card sharing this same
        // polarityIndex to update. Rebuilding the whole page is simpler and more robust than
        // holding a second toggle-group reference to keep in sync by hand.
        onPolarityChanged: () -> Unit = {},
    ): View {
        val card = MaterialCardView(context).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = segmentStroke
            setCardBackgroundColor(Color.rgb(14, 17, 21))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        content.addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor)
        })
        content.addView(vspace(6))
        content.addView(buildMiniValueRow("DELAY", delayIndex, delayMin, delayMax, "ms"))
        content.addView(vspace(4))
        content.addView(buildMiniSliderRow("GAIN", gainIndex, gainMin, gainMax, gainStep, "dB"))
        content.addView(vspace(4))
        content.addView(buildMiniToggleRow("POL", polarityIndex, polarityMirror, onPolarityChanged))

        card.addView(content)
        return card
    }

    /** Places the 4 [addChannelCard] views around the car/speaker diagram (mid pair above the low
     *  pair on each side, matching the physical door-mid/underseat-low layout) and draws a
     *  coloured connector line from each card to its speaker's approximate position on the image
     *  -- each line's colour matches that card's own title colour, so which line belongs to which
     *  card is unambiguous without a legend. Speaker positions are fixed fractional coordinates
     *  within R.drawable.bmw_gains_delay_car (tuned by eye against that asset, not computed). */
    fun addChannelDiagramSection(
        midLeft: View, lowLeft: View, midRight: View, lowRight: View,
        midLeftColor: Int, lowLeftColor: Int, midRightColor: Int, lowRightColor: Int,
    ) {
        val leftColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        leftColumn.addView(midLeft, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        leftColumn.addView(vspace(14))
        leftColumn.addView(lowLeft, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rightColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rightColumn.addView(midRight, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rightColumn.addView(vspace(14))
        rightColumn.addView(lowRight, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val carImage = ImageView(context).apply {
            setImageResource(R.drawable.bmw_gains_delay_car)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val innerRow = ChannelConnectorRow(context, midLeftColor, lowLeftColor, midRightColor, lowRightColor)
        innerRow.addView(
            leftColumn,
            LinearLayout.LayoutParams(dp(CHANNEL_CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(18) },
        )
        innerRow.addView(carImage, LinearLayout.LayoutParams(dp(CAR_DIAGRAM_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        innerRow.addView(
            rightColumn,
            LinearLayout.LayoutParams(dp(CHANNEL_CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(18) },
        )

        val centeredFrame = android.widget.FrameLayout(context).apply {
            addView(innerRow, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        addCustomView(centeredFrame, topMarginDp = 4, bottomMarginDp = 4)
    }

    private fun smallLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 10f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(150, 158, 168))
        letterSpacing = 0.03f
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

    /** MDC's Slider throws IllegalStateException if its initial `value` isn't exactly
     *  `valueFrom` plus a whole multiple of `stepSize` -- persisted values can violate that for a
     *  row whose step size changed since the value was saved (e.g. gain used to be a 0.1 step on
     *  the old Gain Structure page; a value like -5.6 is valid there but not against this page's
     *  coarser 0.5 step), so every raw persisted value feeding a Slider's `value=` is snapped here
     *  first rather than assumed already aligned. */
    private fun snapToStep(value: Float, min: Float, max: Float, step: Float): Float {
        val clamped = value.coerceIn(min, max)
        return (min + ((clamped - min) / step).roundToInt() * step).coerceIn(min, max)
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

    /** Bold accent-coloured value reading in a darkened box between a slider's title and its
     *  track -- tap to edit. Typography (bold, accent blue, 17sp) matches the LOW PASS/HIGH
     *  PASS crossover cards' own large frequency readout; the box background is new, using
     *  those same cards' dark card background color so it reads as part of the same family. */
    private fun createBoxedValueText(value: Float, suffix: String) = TextView(context).apply {
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        setTextColor(accentBlue)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(4), dp(14), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(valueBoxBackground)
            setStroke(dp(1), accentBlue)
        }
        updateValueBox(this, value, suffix)
    }

    /** A larger boxed value reading with a small caption above it (e.g. "MID LEFT" / "0 ms") --
     *  used by the delay car diagram, where createBoxedValueText's plain number reads too small
     *  and unlabeled once it's no longer sitting directly next to a text title on the same row. */
    private fun createLabeledValueBox(caption: String, value: Float, suffix: String): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(valueBoxBackground)
                setStroke(dp(1), accentBlue)
            }
        }
        box.addView(TextView(context).apply {
            text = caption
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(150, 158, 168))
            letterSpacing = 0.03f
        })
        box.addView(TextView(context).apply {
            id = View.generateViewId()
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentBlue)
            gravity = Gravity.CENTER
        })
        updateLabeledValueBox(box, value, suffix)
        return box
    }

    private fun updateLabeledValueBox(box: LinearLayout, value: Float, suffix: String) {
        (box.getChildAt(1) as TextView).text = "${format.format(value)} $suffix".trim()
    }

    /** Min/center/max reading below a slider's track, aligned under it. */
    private fun tickLabel(text: String, alignment: Int) = TextView(context).apply {
        this.text = text
        textSize = 10f
        gravity = alignment
        setTextColor(Color.rgb(124, 132, 142))
    }

    private fun tickParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

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
        private const val VERTICAL_HEADER_SLIDER_WIDTH_DP = 64
        private const val ROW_LABEL_WIDTH_DP = 46
        private const val CHANNEL_CARD_WIDTH_DP = 340
        private const val CAR_DIAGRAM_WIDTH_DP = 190
    }
}

/** Draws a coloured connector line from each of [ChannelConnectorRow]'s two flanking columns
 *  (2 stacked channel cards each) to a fixed fractional position on the car diagram in the
 *  middle child -- see [CrossoverDashboardBuilder.addChannelDiagramSection]. Expects exactly 3
 *  children in this order: left column, car image, right column, each column itself holding
 *  [mid card, vertical spacer, low card]. */
private class ChannelConnectorRow(
    context: Context,
    private val midLeftColor: Int,
    private val lowLeftColor: Int,
    private val midRightColor: Int,
    private val lowRightColor: Int,
) : LinearLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (childCount < 3) return
        val leftColumn = getChildAt(0) as? LinearLayout ?: return
        val carImage = getChildAt(1)
        val rightColumn = getChildAt(2) as? LinearLayout ?: return
        if (leftColumn.childCount < 3 || rightColumn.childCount < 3) return

        fun anchorY(column: LinearLayout, index: Int): Float {
            val child = column.getChildAt(index)
            return column.top + child.top + child.height / 2f
        }

        val carLeft = carImage.left.toFloat()
        val carTop = carImage.top.toFloat()
        val carW = carImage.width.toFloat()
        val carH = carImage.height.toFloat()

        fun drawLine(startX: Float, startY: Float, speakerFx: Float, speakerFy: Float, color: Int) {
            paint.color = color
            canvas.drawLine(startX, startY, carLeft + carW * speakerFx, carTop + carH * speakerFy, paint)
        }

        // Fractional speaker positions within bmw_gains_delay_car.png, tuned by eye.
        drawLine(leftColumn.right.toFloat(), anchorY(leftColumn, 0), 0.186f, 0.336f, midLeftColor)
        drawLine(leftColumn.right.toFloat(), anchorY(leftColumn, 2), 0.152f, 0.488f, lowLeftColor)
        drawLine(rightColumn.left.toFloat(), anchorY(rightColumn, 0), 0.811f, 0.336f, midRightColor)
        drawLine(rightColumn.left.toFloat(), anchorY(rightColumn, 2), 0.844f, 0.488f, lowRightColor)
    }
}
