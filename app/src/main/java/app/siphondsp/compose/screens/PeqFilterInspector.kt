package app.siphondsp.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.GlassDivider
import app.siphondsp.compose.controls.GlassItem
import app.siphondsp.compose.controls.GlassMenu
import app.siphondsp.compose.controls.bmwFocusRing
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.dsp.AutoHeadroomCalculator
import app.siphondsp.fragment.PeqBandEditResult
import app.siphondsp.fragment.PeqBandEditor
import app.siphondsp.fragment.PeqDialogs
import app.siphondsp.fragment.PeqScope
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.utils.extensions.ContextExtensions.toast
import java.util.Locale
import kotlin.math.asinh
import kotlin.math.ln
import kotlin.math.log10

// The inspector is sized for the head unit's 348dp graph row: everything fits without scrolling.
internal val PeqInspectorWidth = 300.dp
private val InspectorRowHeight = 44.dp
private val InspectorLabelWidth = 40.dp

/** One-tap Q values: Butterworth, 1.0, one octave, 2.0 and a third of an octave. */
private val QPresets = listOf(0.71, 1.0, 1.41, 2.0, 4.3)

/**
 * The selected filter's editor: identity + prev/next, type and channel as one-tap segments,
 * frequency / gain / Q with range bars (drag the value to scrub), Q presets and the filter's own
 * actions (bypass, copy to another bank, duplicate / move, delete).
 */
@Composable
internal fun PeqSelectedFilter(
    holder: PeqStateHolder,
    index: Int,
    onClose: () -> Unit,
    onSelect: (ParametricEqBand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bands = holder.visibleBands
    val band = bands.getOrNull(index) ?: return
    val actions = rememberPeqFilterActions(holder, index)
    val number = holder.filterNumberOffset + index + 1
    val colour = Color(peqFilterColor(number - 1))
    Column(
        modifier.testTag("peq-filter-editor").clip(RoundedCornerShape(12.dp)).background(PeqPanelFill)
            .drawBehind { drawRect(colour, Offset.Zero, Size(4.dp.toPx(), size.height)) }
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Identity, prev / next, close.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(colour))
            Column(Modifier.weight(1f)) {
                Text("Filter %02d".format(Locale.ROOT, number), color = PeqControlText, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${holder.selectedScope.label.uppercase(Locale.ROOT)} · ${index + 1} of ${bands.size}" +
                    if (band.enabled) "" else " · BYPASSED",
                    color = PeqMutedText, fontSize = 10.sp, maxLines = 1)
            }
            PeqActionButton("‹", { bands.getOrNull(index - 1)?.let(onSelect) },
                Modifier.width(40.dp).semantics { contentDescription = "Previous filter" },
                contentPadding = 0.dp, enabled = index > 0)
            PeqActionButton("›", { bands.getOrNull(index + 1)?.let(onSelect) },
                Modifier.width(40.dp).semantics { contentDescription = "Next filter" },
                contentPadding = 0.dp, enabled = index < bands.lastIndex)
            PeqActionButton("✕", onClose, Modifier.width(40.dp).semantics { contentDescription = "Close filter" },
                contentPadding = 0.dp)
        }
        // Type (shape icons) + channel.
        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(Modifier.fillMaxHeight()) {
                ParametricEqFilterType.entries.forEach { type ->
                    SegmentOption(type == band.filterType, type.displayName, { actions.setType(type) },
                        Modifier.width(34.dp)) { selected -> FilterTypeIcon(type, if (selected) Color.White else PeqMutedText) }
                }
            }
            Segmented(Modifier.weight(1f).fillMaxHeight()) {
                ParametricEqChannel.entries.sortedBy { channelOrder(it) }.forEach { channel ->
                    SegmentOption(channel == band.channel, "Channel ${channel.displayLabel}", { actions.setChannel(channel) },
                        Modifier.weight(1f)) { selected ->
                        Text(channel.displayLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (selected) Color.White else channelColour(channel))
                    }
                }
            }
        }
        InspectorParameter("FREQ") {
            PeqNumericControl("Frequency", "${formatFrequency(band.frequency)}", actions::frequency,
                actions::stepFrequency, actions::commit, Modifier.fillMaxWidth(), stepWidth = 40.dp,
                valueFontSize = 18.sp, height = InspectorRowHeight,
                rangeFraction = (log10(band.frequency / 20.0) / 3.0).toFloat(), rangeColor = colour, scrubbable = true)
        }
        InspectorParameter("GAIN") {
            PeqNumericControl("Gain", "${signed(band.gain)} dB", actions::gain, actions::stepGain, actions::commit,
                Modifier.fillMaxWidth(), stepWidth = 40.dp, valueFontSize = 18.sp, height = InspectorRowHeight,
                rangeFraction = ((band.gain + 30.0) / 60.0).toFloat(), rangeCentred = true, rangeColor = colour,
                scrubbable = true)
        }
        InspectorParameter("Q") {
            PeqNumericControl("Q", formatPeqValue(band.q), actions::q, actions::stepQ, actions::commit,
                Modifier.fillMaxWidth(), stepWidth = 40.dp, valueFontSize = 18.sp, height = InspectorRowHeight,
                rangeFraction = (ln(band.q / 0.1) / ln(300.0)).toFloat(), rangeColor = colour,
                trailing = bandwidthLabel(band), scrubbable = true)
        }
        Row(Modifier.fillMaxWidth().padding(start = InspectorLabelWidth).height(28.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            QPresets.forEach { preset ->
                val selected = kotlin.math.abs(band.q - preset) < 0.005
                SmallButton(formatPeqValue(preset), { actions.setQ(preset) }, Modifier.weight(1f).fillMaxHeight(), selected)
            }
        }
        FilterActionsRow(holder, index, band, onClose)
    }
}

@Composable
private fun InspectorParameter(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PeqMutedText, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(InspectorLabelWidth))
        Box(Modifier.weight(1f)) { control() }
    }
}

@Composable
private fun FilterActionsRow(holder: PeqStateHolder, index: Int, band: ParametricEqBand, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = holder.selectedScope
    var copyMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    fun run(result: PeqBandEditResult) {
        copyMenu = false
        moreMenu = false
        holder.apply(result)
    }
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BypassSwitch(band.enabled, { holder.setBandEnabled(index, !band.enabled) }, Modifier.fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight()) {
            SmallButton("Copy to…", { copyMenu = true }, Modifier.fillMaxSize())
            GlassMenu(expanded = copyMenu, onDismissRequest = { copyMenu = false }) {
                PeqScope.entries.filter { it != scope }.forEach { target ->
                    GlassItem(
                        text = target.label,
                        colour = Color(scopeColour(target)),
                        onClick = { run(PeqBandEditor.copyFilter(holder.peqState, scope, index, target)) },
                    )
                }
            }
        }
        Box(Modifier.width(36.dp).fillMaxHeight()) {
            SmallButton("⋯", { moreMenu = true }, Modifier.fillMaxSize())
            GlassMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                GlassItem("Duplicate", onClick = { run(PeqBandEditor.duplicateFilter(holder.peqState, scope, index)) })
                GlassItem("Move up", enabled = index > 0,
                    onClick = { run(PeqBandEditor.moveFilter(holder.peqState, scope, index, index - 1)) })
                GlassItem("Move down", enabled = index < holder.visibleBands.lastIndex,
                    onClick = { run(PeqBandEditor.moveFilter(holder.peqState, scope, index, index + 1)) })
                GlassDivider()
                GlassItem("Delete filter", danger = true, onClick = {
                    moreMenu = false
                    holder.deleteBand(index)
                    onClose()
                    context.toast("Filter deleted (Undo restores it)")
                })
            }
        }
    }
}

/** On = the filter is heard; off = bypassed (kept, but out of the engine and the curves). */
@Composable
private fun BypassSwitch(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(PeqControlFill)
            .clickable(interactionSource = source, indication = null, role = Role.Switch, onClick = onToggle)
            .bmwFocusRing(source)
            .semantics { contentDescription = if (on) "Filter on, tap to bypass" else "Filter bypassed, tap to enable" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.width(32.dp).height(18.dp).clip(RoundedCornerShape(9.dp))
                .background(if (on) PeqControlBlue else PeqValueFill)
                .border(1.dp, if (on) PeqControlBlue else PeqMutedText, RoundedCornerShape(9.dp)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(2.dp).size(14.dp).clip(CircleShape).background(if (on) Color.White else PeqMutedText))
        }
        Text(if (on) "On" else "Off", color = PeqControlText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Segmented(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(PeqValueFill).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun SegmentOption(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(6.dp))
            .background(if (selected) PeqControlBlue else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .bmwFocusRing(source)
            .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { content(selected) }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (selected) PeqControlBlue else PeqControlFill)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .bmwFocusRing(source)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) PeqControlText else PeqMutedText, fontSize = 12.sp, maxLines = 1)
    }
}

/** The filter's response shape as a small line icon. */
@Composable
private fun FilterTypeIcon(type: ParametricEqFilterType, colour: Color) {
    Canvas(Modifier.width(22.dp).height(14.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            when (type) {
                ParametricEqFilterType.PEAKING -> {
                    moveTo(0f, h); cubicTo(w * .3f, h, w * .35f, 0f, w * .5f, 0f); cubicTo(w * .65f, 0f, w * .7f, h, w, h)
                }
                ParametricEqFilterType.LOW_SHELF -> {
                    moveTo(0f, 0f); lineTo(w * .3f, 0f); cubicTo(w * .5f, 0f, w * .5f, h, w * .7f, h); lineTo(w, h)
                }
                ParametricEqFilterType.HIGH_SHELF -> {
                    moveTo(0f, h); lineTo(w * .3f, h); cubicTo(w * .5f, h, w * .5f, 0f, w * .7f, 0f); lineTo(w, 0f)
                }
                ParametricEqFilterType.NOTCH -> {
                    moveTo(0f, h * .2f); lineTo(w * .4f, h * .2f); lineTo(w * .5f, h); lineTo(w * .6f, h * .2f); lineTo(w, h * .2f)
                }
            }
        }
        drawPath(path, colour, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

private val ParametricEqFilterType.displayName: String
    get() = when (this) {
        ParametricEqFilterType.PEAKING -> "Peak"
        ParametricEqFilterType.LOW_SHELF -> "Low shelf"
        ParametricEqFilterType.HIGH_SHELF -> "High shelf"
        ParametricEqFilterType.NOTCH -> "Notch"
    }

private fun channelOrder(channel: ParametricEqChannel) = when (channel) {
    ParametricEqChannel.LEFT -> 0
    ParametricEqChannel.LEFT_RIGHT -> 1
    ParametricEqChannel.RIGHT -> 2
}

/** L and R in the graph's L / R curve colours; L+R neutral. */
internal fun channelColour(channel: ParametricEqChannel): Color = when (channel) {
    ParametricEqChannel.LEFT -> Color(LeftChannelColor)
    ParametricEqChannel.RIGHT -> Color(RightChannelColor)
    ParametricEqChannel.LEFT_RIGHT -> PeqControlText
}

/** A bank's colour, as the graph legend and crossover tint use it. */
internal fun scopeColour(scope: PeqScope): Int = when (scope) {
    PeqScope.FULL -> android.graphics.Color.WHITE
    PeqScope.LOW -> app.siphondsp.view.BmwDashboardSkin.LIGHT_BLUE
    PeqScope.MID -> app.siphondsp.view.BmwDashboardSkin.MID_BAND_YELLOW
    PeqScope.HIGH -> app.siphondsp.view.BmwDashboardSkin.HIGH_BAND_PINK
}

/** Bandwidth in octaves for a bell or notch (a shelf's Q isn't a bandwidth). */
internal fun bandwidthOctaves(q: Double): Double = 2.0 / ln(2.0) * asinh(1.0 / (2.0 * q))

private fun bandwidthLabel(band: ParametricEqBand): String? = when (band.filterType) {
    ParametricEqFilterType.PEAKING, ParametricEqFilterType.NOTCH ->
        String.format(Locale.ROOT, "%.2g oct", bandwidthOctaves(band.q))
    else -> null
}

internal fun formatFrequency(hz: Double): String =
    if (hz >= 1000.0) "${formatPeqValue(hz / 1000.0)} kHz" else "${formatPeqValue(hz)} Hz"

private fun signed(value: Double): String = (if (value > 0) "+" else "") + formatPeqValue(value)

// --- filter bar -----------------------------------------------------------------------------------

/** 64dp filter bar chip: colour bar, number, type, channel and a freq / gain summary. */
@Composable
internal fun PeqFilterChip(
    number: Int,
    band: ParametricEqBand,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val colour = Color(peqFilterColor(number - 1))
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.width(118.dp).fillMaxHeight()
            .alpha(if (band.enabled) 1f else .5f)
            .clip(shape)
            .background(if (selected) PeqPanelFill else PeqControlFill)
            .then(if (selected) Modifier.border(2.dp, PeqControlBlue, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .bmwFocusRing(source)
            .semantics {
                this.selected = selected
                contentDescription = "Filter $number ${band.filterType.displayName} ${band.channel.displayLabel}" +
                    if (band.enabled) "" else " bypassed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(colour))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("%02d".format(Locale.ROOT, number), color = PeqControlText, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Text(if (band.enabled) band.filterType.displayLabel else "${band.filterType.displayLabel}·off",
                    color = PeqMutedText, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(if (band.channel == ParametricEqChannel.LEFT_RIGHT) "LR" else band.channel.displayLabel,
                    color = if (band.channel == ParametricEqChannel.LEFT_RIGHT) PeqMutedText else channelColour(band.channel),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            val hz = if (band.frequency >= 1000.0) "${formatPeqValue(band.frequency / 1000.0)}k" else formatPeqValue(band.frequency)
            Text("$hz ${signed(band.gain)}", color = PeqMutedText, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

/**
 * Pre EQ preamp (the same value the Output page sets) with the loudest point of the response it
 * leads to after the engine's headroom: green while it stays at or below 0 dBFS, red when it clips.
 */
@Composable
internal fun PeqPreampControl(holder: PeqStateHolder, systemValues: FloatArray, sampleRate: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dialogs = remember(context) { PeqDialogs(context) }
    val state = holder.peqState
    val calculator = remember { AutoHeadroomCalculator() }
    val outputPeakDb = remember(state, systemValues, sampleRate) {
        if (systemValues.size != NativeBmwDspValues.SIZE) null else runCatching {
            val effective = calculator.effectiveValues(systemValues, state, sampleRate)
            calculator.peakDb(systemValues, state, sampleRate) + effective[NativeBmwDspValues.INDEX_HEADROOM]
        }.getOrNull()
    }
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("PREAMP", color = PeqMutedText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        PeqNumericControl(
            "Preamp", "${signed(state.preampDb.toDouble())} dB",
            onValueClick = {
                dialogs.showValueInput("Preamp", state.preampDb.toDouble(), PREAMP_MIN_DB, PREAMP_MAX_DB, "dB") { value ->
                    holder.setPreamp(value.toFloat())
                }
            },
            onStep = { direction, times ->
                val next = (holder.peqState.preampDb + direction * times * 0.5f)
                    .coerceIn(PREAMP_MIN_DB.toFloat(), PREAMP_MAX_DB.toFloat())
                holder.previewPreamp(next)
            },
            onCommit = { holder.commitPreview("preamp_step") },
            modifier = Modifier.width(170.dp), stepWidth = 40.dp, valueFontSize = 16.sp, height = 64.dp,
        )
        Column(
            Modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(PeqControlFill).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("PEAK", color = PeqMutedText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            val clipping = outputPeakDb != null && outputPeakDb > 0.05
            Text(
                outputPeakDb?.let { "${signed(String.format(Locale.ROOT, "%.1f", it).toDouble())} dBFS" } ?: "—",
                color = when {
                    outputPeakDb == null -> PeqMutedText
                    clipping -> Color(0xFFFF4D4D)
                    else -> Color(RightChannelColor)
                },
                fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private const val PREAMP_MIN_DB = -30.0
private const val PREAMP_MAX_DB = 12.0

/** How many filters each bank holds, for the bank tabs. */
internal fun BmwPeqState.bandCount(scope: PeqScope): Int = PeqBandEditor.bandsFor(this, scope).size
