package app.siphondsp.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import app.siphondsp.R
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import com.google.android.material.button.MaterialButton
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

    // Every addSliderRow() call within the *current* dashboardPanel appends here; once that
    // panel's build() lambda returns, dashboardPanel sizes every title box (and the matching tick
    // -row spacer that has to align under it) to whatever the single longest title in the group
    // actually needs -- not a fixed dp guess -- so every row in that group still lines up, but the
    // column is only as wide as it needs to be for its own labels (Gains' 5 short labels don't
    // reserve the same width Compressor's "Makeup gain" needs).
    private val pendingTitleBoxes = mutableListOf<TextView>()
    private val pendingTitleSpacers = mutableListOf<View>()

    private val accentBlue = BmwDashboardSkin.LIGHT_BLUE
    private val valueBoxBackground = Color.rgb(16, 19, 24)
    private val segmentIdle = Color.rgb(20, 23, 28)
    private val segmentStroke = Color.rgb(67, 73, 82)
    private val divider = Color.rgb(47, 53, 61)

    fun dashboardPanel(
        title: String,
        subtitle: String? = null,
        // Recolors the panel's own title text -- used for the All-pass workspace's per-output
        // pages (Left Low/Right Low blue, Left Mid/Right Mid yellow), same Low/Mid band colors
        // addSliderRow's accentColor already uses elsewhere. null keeps the default white title,
        // unaffected for every other page's call site.
        titleColor: Int? = null,
        build: CrossoverDashboardBuilder.() -> Unit,
    ) {
        // Transparent, not its own copy of the photo background: the workspace root already
        // paints that once, full-screen (paintWorkspaceBackground/styleWorkspace) -- a card
        // painting an independent opaque copy across its own (smaller) bounds both hides that
        // real background behind an opaque duplicate and, since each card gets its own pinned
        // M-badge, shrinks the badge down to card scale instead of it reading at one full-screen
        // size. Text, value boxes, sliders, and any live visualizer keep their own solid
        // backgrounds regardless -- only the card's own body becomes see-through.
        val card = MaterialCardView(context).apply {
            radius = dp(7).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.TRANSPARENT)
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
                setTextColor(titleColor ?: Color.WHITE)
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
        pendingTitleBoxes.clear()
        pendingTitleSpacers.clear()
        build()

        // Every addSliderRow() in this panel queued its title box and tick-row spacer above --
        // now that the whole panel's title strings are known, size all of them to whichever one
        // actually needs the most room, so every row's title column (and therefore its slider's
        // start x) lines up without hand-picking a width per page.
        if (pendingTitleBoxes.isNotEmpty()) {
            val padding = dp(18) * 2
            val maxTextWidth = pendingTitleBoxes.maxOf { it.paint.measureText(it.text.toString()) }
            val boxWidth = maxTextWidth.roundToInt() + padding
            pendingTitleBoxes.forEach { (it.layoutParams as LinearLayout.LayoutParams).width = boxWidth }
            // The tick-row spacer stands in for the title box *and* the gap beside it (the
            // slider's own marginStart), so the tick labels line up under where the slider
            // actually starts, not just under the title box's own right edge.
            val spacerWidth = boxWidth + dp(BmwDashboardSkin.SLIDER_TITLE_GAP_DP)
            pendingTitleSpacers.forEach { (it.layoutParams as LinearLayout.LayoutParams).width = spacerWidth }
        }

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

    fun sectionCard(title: String, subtitle: String? = null, titleColor: Int? = null, build: CrossoverDashboardBuilder.() -> Unit) {
        dashboardPanel(title, subtitle, titleColor, build = build)
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

    fun sectionHeader(title: String, accentColor: Int? = null) {
        if (currentContent.childCount > 2) currentContent.addView(space(8))
        currentContent.addView(TextView(context).apply {
            text = title
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor ?: accentBlue)
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
        // Recolors the toggle's own ON/selected indicator to match a colour-coded row (eg. the
        // All-pass workspace's per-output Enabled switch). null keeps the default neutral blue,
        // unaffected for every other call site.
        accentColor: Int? = null,
        // Called after the value is written -- for callers whose other rows need to be rebuilt to
        // reflect this switch's new state (see the Gains & Delay Link L/R toggle).
        onToggled: () -> Unit = {},
    ) {
        val row = createRow()
        row.addView(labelBlock(title, subtitle), labelParams())

        // Left-aligned to sit directly under the label column, at the same horizontal offset
        // addSliderRow's slider starts at (label column + 14dp), rather than hugging the row's
        // right edge -- so a toggle row and a slider row stack with their controls flush.
        val controlSlot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (offLabel == "OFF" && onLabel == "ON") {
            val toggle = MaterialSwitch(context).apply {
                isChecked = values[index] >= .5f
                contentDescription = title
                showText = false
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                // null clears the default tint lists so the custom glass drawables' own colors
                // show through unfiltered.
                thumbTintList = null
                trackTintList = null
                thumbDrawable = BmwDashboardSkin.glassSwitchThumbDrawable(context, accentColor)
                trackDrawable = BmwDashboardSkin.glassSwitchTrackDrawable(context)
                setOnCheckedChangeListener { _, checked ->
                    writeValue(index, mirrorIndices, if (checked) 1f else 0f)
                    onToggled()
                }
            }
            controlSlot.addView(toggle)
        } else {
            val group = buildGlassSegmentGroup(
                offLabel, onLabel, values[index] >= .5f,
                segmentWidth = dp(80), segmentHeight = dp(34),
                accentColor = accentColor,
            ) { onSelected ->
                writeValue(index, mirrorIndices, if (onSelected) 1f else 0f)
                onToggled()
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
        // Recolors this row's title text -- used for the Low=blue/Mid=yellow band sliders' labels.
        // null keeps the default off-white title.
        accentColor: Int? = null,
        // Recolors this row's slider handle + capsule outline (see BmwDashboardSkin.styleSlider
        // and SLIDER_LOW_BAND_COLOR/SLIDER_MID_BAND_COLOR/SLIDER_HEADROOM_COLOR) -- deliberately
        // separate from [accentColor]: the dedicated slider art uses its own exact hues, distinct
        // from the LIGHT_BLUE/MID_BAND_YELLOW used for title text elsewhere, and Headroom gets a
        // slider accent (purple) with no title color at all. null keeps the default grey thumb
        // and standard blue capsule border every other slider still uses.
        sliderAccentColor: Int? = null,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(BmwDashboardSkin.SLIDER_ROW_MIN_HEIGHT_DP)
        }

        // Title, slider, and value all on one row, always in that order: the title sits in its
        // own boxed column (width finalized by dashboardPanel once every row's title in this
        // panel is known -- see pendingTitleBoxes), the slider fills whatever room is left, and
        // the value box sits at the very end -- never in the middle of the row.
        // A stored value can sit outside [min,max] after a row's range is narrowed post-release
        // (eg. the All-pass Frequency slider's 20-20000Hz range shrinking to 20-1000Hz) -- without
        // this, the value box kept showing the old raw number (eg. "16330 Hz") while the slider
        // itself visually clamped to its new max, reading as two controls disagreeing. Clamping
        // what's *displayed* here (not rewriting values[index] itself) is enough to keep them in
        // sync; the stored value corrects for real the moment the user touches this row.
        val displayValue = values[index].coerceIn(min, max)
        val valueText = createBoxedValueText(displayValue * displayScale, suffix, accentColor)
        val titleBox = createBoxedTitleText(label, accentColor)
        topRow.addView(titleBox, LinearLayout.LayoutParams(0, dp(BmwDashboardSkin.SLIDER_TITLE_HEIGHT_DP)))
        pendingTitleBoxes += titleBox

        val slider = Slider(context).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = snapToStep(values[index], min, max, step)
            BmwDashboardSkin.styleSlider(this, sliderAccentColor)
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
                format.format(displayValue * displayScale),
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

        // No horizontal padding on the slider itself. No gap on the title side (its own fixed
        // width and padding already separate it) and a 24dp gap on the value side, from margins
        // matching the title box's and value box's own edges exactly.
        topRow.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(BmwDashboardSkin.SLIDER_TITLE_GAP_DP)
            marginEnd = dp(BmwDashboardSkin.SLIDER_VALUE_GAP_DP)
        })
        topRow.addView(valueText)
        container.addView(topRow)

        val tickRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        // Placeholder width, 0 until dashboardPanel finalizes it alongside the title boxes above
        // -- has to match whatever width they end up at so the tick labels stay under the slider.
        val titleSpacer = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(1)) }
        tickRow.addView(titleSpacer)
        pendingTitleSpacers += titleSpacer
        tickRow.addView(tickLabel(format.format(min * displayScale), Gravity.START), tickParams())
        tickRow.addView(tickLabel(format.format((min + max) / 2f * displayScale), Gravity.CENTER_HORIZONTAL), tickParams())
        tickRow.addView(tickLabel(format.format(max * displayScale), Gravity.END), tickParams())
        tickRow.addView(space(VALUE_WIDTH_DP + BmwDashboardSkin.SLIDER_VALUE_GAP_DP))
        container.addView(tickRow)

        currentContent.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Single-line [label][tap-to-edit value] row for [addChannelCard]'s Delay row -- no drag
     *  slider, matching the original delay diagram's own tap-only rationale (this is about showing
     *  *where* a delay applies, not fine adjustment).
     *
     *  [linkedIndex], when given, is the paired L/R channel's own delay index (see the Gains &
     *  Delay "Link L/R Delay" toggle) -- committing a new value here also writes it there. That
     *  other card's own boxed value Text isn't reachable from this closure, so [onLinked] (a full
     *  page rebuild, same reasoning as [buildMiniToggleRow]'s Polarity linking) is called instead
     *  of the normal single-box [updateValueBox] to refresh it. */
    private fun buildMiniValueRow(
        label: String,
        index: Int,
        min: Float,
        max: Float,
        suffix: String,
        linkedIndex: Int? = null,
        onLinked: () -> Unit = {},
    ): View {
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
                if (linkedIndex != null) {
                    values[linkedIndex] = stored
                    onChanged(values)
                    onLinked()
                } else {
                    updateValueBox(valueText, stored, suffix)
                    onChanged(values)
                }
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
        val group = buildGlassSegmentGroup(
            "NORMAL", "INVERT", values[index] >= .5f,
            segmentWidth = 0, segmentHeight = dp(24), segmentWeight = 1f,
        ) { onSelected ->
            writeValue(index, mirrorIndices, if (onSelected) 1f else 0f)
            onToggled()
        }
        row.addView(group, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        return row
    }

    /** One "Left Mid"/"Right Mid"/"Left Low"/"Right Low" channel card for the Gains & Delay car
     *  diagram (see [addChannelDiagramSection]): a coloured title (matching that channel's
     *  connector-line colour) plus one compact single-line row each for Delay and Polarity. Gain
     *  used to have its own mini-slider row here too, but moved to its own full horizontal slider
     *  on a dedicated Gain page instead, alongside Headroom. */
    fun addChannelCard(
        title: String,
        accentColor: Int,
        delayIndex: Int, delayMin: Float, delayMax: Float,
        polarityIndex: Int, polarityMirror: IntArray,
        // Polarity is fully independent per physical driver -- each of the 4 cards passes its own
        // outputIndex(output, FIELD_INVERT) slot with an empty polarityMirror, deliberately NOT
        // mirrored to its band's other side. A real reversed-polarity wiring fault can land on
        // just one driver (this codebase already has a documented example of exactly that class
        // of real-world fault, see NativeBmwDspProcessor.cpp's "Deliberate final-output swap"
        // comment), and flipping both sides of a band together can never compensate for that --
        // it leaves their phase relative to *each other* completely unchanged. (This used to be
        // one value shared by both the Left and Right card of a band -- INDEX_LOW_INVERT/
        // INDEX_MID_INVERT, still kept as one-time migration seeds -- but that meant a single-
        // driver wiring fault was simply unfixable from this screen. onPolarityChanged is no
        // longer needed for keeping a sibling card in sync now that nothing is shared, but stays
        // available for a caller with some other reason to react to a polarity edit.)
        onPolarityChanged: () -> Unit = {},
        // Delay is normally 4 fully independent values (unlike polarity above) -- this band's
        // OTHER side's delay index, only when the page's "Link L/R Delay" toggle is on; null
        // (the default) keeps this card's delay independent of its sibling, same as always.
        delayLinkedIndex: Int? = null,
        onDelayChanged: () -> Unit = {},
    ): View {
        val card = MaterialCardView(context).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = segmentStroke
            // No fill: same reasoning as dashboardPanel/crossoverBandCard -- lets the page's
            // designed background show through instead of hiding it behind a solid card.
            setCardBackgroundColor(Color.TRANSPARENT)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        content.addView(TextView(context).apply {
            text = title
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor)
        })
        content.addView(vspace(6))
        content.addView(buildMiniValueRow("DELAY", delayIndex, delayMin, delayMax, "ms", delayLinkedIndex, onDelayChanged))
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

        // Weighted (0dp + weight), not fixed dp widths: this row's total width used to be a fixed
        // ~906dp (340+190+340 plus margins) centered in a FrameLayout, which comfortably fit the
        // wide reference emulator this was tuned against but hard-clipped off-screen on a phone
        // whose landscape width in dp is narrower than that -- nothing here scrolls, so overflow
        // just vanishes past the screen edge instead of shrinking to fit. Weights preserve the
        // same relative proportions (reusing the old fixed dp values as weight ratios) while
        // making the whole row stretch or shrink to whatever width is actually available.
        val innerRow = ChannelConnectorRow(context, midLeftColor, lowLeftColor, midRightColor, lowRightColor)
        innerRow.addView(
            leftColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, CHANNEL_CARD_WIDTH_DP.toFloat()).apply { marginEnd = dp(18) },
        )
        innerRow.addView(carImage, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, CAR_DIAGRAM_WIDTH_DP.toFloat()))
        innerRow.addView(
            rightColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, CHANNEL_CARD_WIDTH_DP.toFloat()).apply { marginStart = dp(18) },
        )

        addCustomView(innerRow, topMarginDp = 4, bottomMarginDp = 4)
    }

    private fun smallLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 9f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(150, 158, 168))
        letterSpacing = 0.03f
    }

    /** Dropdown row: label + tap-to-open PopupMenu offering a fixed set of stored values (e.g.
     *  All-pass section order, 1st/2nd). Lifted from the old crossover slope selector, which
     *  this replaced -- same PopupMenu-off-a-MaterialButton interaction, generalized. */
    fun addDropdownRow(label: String, index: Int, options: List<Pair<String, Float>>, mirrorIndices: IntArray = intArrayOf()) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(BmwDashboardSkin.SLIDER_ROW_MIN_HEIGHT_DP)
        }
        val titleBox = createBoxedTitleText(label)
        row.addView(titleBox, LinearLayout.LayoutParams(0, dp(BmwDashboardSkin.SLIDER_TITLE_HEIGHT_DP)))
        pendingTitleBoxes += titleBox

        fun currentLabel(): String {
            val current = values[index]
            return options.minByOrNull { kotlin.math.abs(it.second - current) }?.first ?: options.first().first
        }
        val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = currentLabel()
            textSize = 12f
            isAllCaps = false
            minHeight = dp(32)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(4)
            strokeColor = ColorStateList.valueOf(segmentStroke)
            backgroundTintList = ColorStateList.valueOf(segmentIdle)
            setTextColor(Color.rgb(220, 226, 232))
            icon = ContextCompat.getDrawable(context, R.drawable.ic_baseline_keyboard_arrow_down_24dp)
            iconGravity = MaterialButton.ICON_GRAVITY_END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setOnClickListener { anchor ->
                val popup = PopupMenu(context, anchor)
                options.forEachIndexed { i, (optionLabel, _) -> popup.menu.add(0, i, i, optionLabel) }
                popup.setOnMenuItemClickListener { item ->
                    val stored = options[item.itemId].second
                    writeValue(index, mirrorIndices, stored)
                    text = currentLabel()
                    true
                }
                popup.show()
            }
        }
        row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(BmwDashboardSkin.SLIDER_TITLE_GAP_DP)
            marginEnd = dp(BmwDashboardSkin.SLIDER_VALUE_GAP_DP)
        })
        row.addView(space(VALUE_WIDTH_DP))
        addRow(row)
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

    /** Two-way capsule toggle (NORMAL/INVERT polarity, or a custom off/on label pair) built from
     *  plain TextViews rather than MaterialButtonToggleGroup + MaterialButton: the glass pill's
     *  gradient+glow background needs a directly-set custom Drawable, and MaterialButtonToggleGroup
     *  throws IllegalStateException ("Attempted to get ShapeAppearance from a MaterialButton which
     *  has an overwritten background") the instant a child's .background is overwritten that way --
     *  confirmed via crash, not a guess. A plain View has no such restriction, and single-selection
     *  between exactly two segments is simple enough to hand-roll here instead. */
    private fun buildGlassSegmentGroup(
        offLabel: String,
        onLabel: String,
        initiallyOn: Boolean,
        segmentWidth: Int,
        segmentHeight: Int,
        segmentWeight: Float = 0f,
        accentColor: Int? = null,
        onSelectionChanged: (onSelected: Boolean) -> Unit,
    ): LinearLayout {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = BmwDashboardSkin.glassSegmentTrackDrawable(context)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val off = glassSegmentView(offLabel, accentColor)
        val on = glassSegmentView(onLabel, accentColor)

        fun select(onSelected: Boolean) {
            off.isSelected = !onSelected
            off.setTextColor(if (onSelected) Color.rgb(180, 188, 197) else Color.WHITE)
            on.isSelected = onSelected
            on.setTextColor(if (onSelected) Color.WHITE else Color.rgb(180, 188, 197))
        }
        select(initiallyOn)
        off.setOnClickListener { if (!off.isSelected) { select(false); onSelectionChanged(false) } }
        on.setOnClickListener { if (!on.isSelected) { select(true); onSelectionChanged(true) } }

        group.addView(off, LinearLayout.LayoutParams(segmentWidth, segmentHeight, segmentWeight))
        group.addView(on, LinearLayout.LayoutParams(segmentWidth, segmentHeight, segmentWeight))
        return group
    }

    private fun glassSegmentView(text: String, accentColor: Int? = null) = TextView(context).apply {
        this.text = text
        textSize = 10f
        isAllCaps = true
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(5), 0, dp(5), 0)
        background = BmwDashboardSkin.glassSegmentDrawable(context, accentColor)
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

    /** Bold accent-coloured value reading in a glass-panel box at the end of a slider row -- tap
     *  to edit. Same [BmwDashboardSkin.glassBoxDrawable] the title box uses, so both read as one
     *  family; [minimumWidth]/[minimumHeight] (not fixed layout dimensions) let it grow for a
     *  longer reading while still meeting the spec's floor size for a short one. [color] matches
     *  this to a colour-coded row's title text and border (null keeps the default blue/neutral
     *  border, unaffected for every uncoloured row) -- see [createBoxedTitleText]. Text size
     *  matches the title box's 14sp: a smaller value reading was hard to read at a glance in a
     *  car, and there's no reason for the number to read smaller than its own label. */
    private fun createBoxedValueText(value: Float, suffix: String, color: Int? = null) = TextView(context).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        setTextColor(color ?: accentBlue)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(2), dp(10), dp(2))
        minimumWidth = dp(BmwDashboardSkin.SLIDER_VALUE_MIN_WIDTH_DP)
        minimumHeight = dp(BmwDashboardSkin.SLIDER_VALUE_HEIGHT_DP)
        background = BmwDashboardSkin.glassBoxDrawable(context, accentColor = color)
        updateValueBox(this, value, suffix)
    }

    /** Boxed slider-row title -- same [BmwDashboardSkin.glassBoxDrawable] language as
     *  [createBoxedValueText], with a border only when [color] is given (a colour-coded row) --
     *  every other, uncoloured title box keeps its original no-border look. Its width is left
     *  to the caller: dashboardPanel sizes it (via pendingTitleBoxes) once the whole panel's
     *  titles are known, not fixed here. */
    private fun createBoxedTitleText(title: String, color: Int? = null) = TextView(context).apply {
        text = title
        textSize = 14f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(color ?: Color.rgb(0xE7, 0xEB, 0xEF))
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(18), dp(3), dp(18), dp(3))
        background = BmwDashboardSkin.glassBoxDrawable(context, showBorder = color != null, accentColor = color)
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
        private const val ROW_LABEL_WIDTH_DP = 46
        private const val CHANNEL_CARD_WIDTH_DP = 340
        private const val CAR_DIAGRAM_WIDTH_DP = 190
    }
}

/** Draws a coloured connector line -- ending in a speaker-cone marker icon, natural silver/black,
 *  not tinted per channel -- from each of [ChannelConnectorRow]'s two flanking columns (2 stacked
 *  channel cards each) to a fixed fractional position on the car diagram in the middle child --
 *  see [CrossoverDashboardBuilder.addChannelDiagramSection]. Expects exactly 3 children in this
 *  order: left column, car image, right column, each column itself holding [mid card, vertical
 *  spacer, low card]. */
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
    private val markerBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_speaker_marker)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val markerSize = context.resources.displayMetrics.density * 22f
    private val markerRect = RectF()

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
            val endX = carLeft + carW * speakerFx
            val endY = carTop + carH * speakerFy
            paint.color = color
            canvas.drawLine(startX, startY, endX, endY, paint)
            markerRect.set(endX - markerSize / 2f, endY - markerSize / 2f, endX + markerSize / 2f, endY + markerSize / 2f)
            canvas.drawBitmap(markerBitmap, null, markerRect, markerPaint)
        }

        // Fractional speaker positions within bmw_gains_delay_car.png, tuned by eye.
        drawLine(leftColumn.right.toFloat(), anchorY(leftColumn, 0), 0.13f, 0.40f, midLeftColor)
        drawLine(leftColumn.right.toFloat(), anchorY(leftColumn, 2), 0.16f, 0.52f, lowLeftColor)
        drawLine(rightColumn.left.toFloat(), anchorY(rightColumn, 0), 0.87f, 0.40f, midRightColor)
        drawLine(rightColumn.left.toFloat(), anchorY(rightColumn, 2), 0.84f, 0.52f, lowRightColor)
    }
}
