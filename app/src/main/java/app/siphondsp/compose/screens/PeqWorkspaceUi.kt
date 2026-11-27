package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.DspSidebarRail
import app.siphondsp.compose.controls.HoldStepHalf
import app.siphondsp.compose.controls.LocalGlassAccent
import app.siphondsp.compose.controls.StepMath
import app.siphondsp.compose.controls.bmwFocusRing
import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.fragment.PeqDialogs
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.view.DspDestination
import app.siphondsp.view.DspRail

internal val PeqControlBlue = Color(0xFF0A8EDF)
internal val PeqControlText = Color(0xFFF3F6FA)
internal val PeqMutedText = Color(0xFF9AA1AB)
internal val PeqControlFill = Color(0xFF222530)
internal val PeqValueFill = Color(0xFF090C12)
internal val PeqPanelFill = Color(0xFF181A22)
// The filter bar takes what used to be a 24dp bottom clearance (44 -> 64dp chips); a small inset
// keeps it off the content frame's border.
internal val PeqBottomClearance = 4.dp

/** The same global numbering used for graph node and filter-shape colours. */
internal val PeqStateHolder.filterNumberOffset: Int
    get() = when (selectedScope) {
        app.siphondsp.fragment.PeqScope.FULL -> 0
        app.siphondsp.fragment.PeqScope.LOW -> peqState.fullRangeBands.size
        app.siphondsp.fragment.PeqScope.MID -> peqState.fullRangeBands.size + peqState.lowBandBands.size
        app.siphondsp.fragment.PeqScope.HIGH -> peqState.fullRangeBands.size + peqState.lowBandBands.size + peqState.midBandBands.size
    }

/** PEQ's compact controls use the same rail geometry as the other DSP workspaces. */
@Composable
internal fun PeqWorkspaceLayout(holder: PeqStateHolder, showSpectrum: Boolean = true) {
    val navigator = LocalAppNavigator.current
    CompositionLocalProvider(LocalGlassAccent provides PeqControlBlue) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 720.dp
            val railWidth = DspRail.sidebarWidthDp(maxWidth.value).dp
            Row(Modifier.fillMaxSize().testTag("peq-workspace").background(Color(0xFF101010))) {
                DspSidebarRail(
                    destinations = DspDestination.entries.filter { it.showInPrimaryNav },
                    current = DspDestination.PARAMETRIC_EQ,
                    canNavigate = { true },
                    onNavigate = { navigator.openWorkspace(it) },
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .testTag("peq-content-frame")
                        .border(2.dp, PeqControlBlue, RoundedCornerShape(10.dp))
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        PeqActionButton(if (compact) "‹" else "‹ Parametric EQ", navigator::back,
                            Modifier.width(if (compact) 44.dp else 190.dp))
                        PeqToolbarActions(holder, Modifier.weight(1f), if (compact) 220.dp else 400.dp)
                    }
                    ParametricEqScreen(holder, Modifier.weight(1f).fillMaxWidth(), showSpectrum)
                }
            }
        }
    }
}

/** Plain charcoal controls with a solid blue selected state; no graph/section recolouring. */
@Composable
internal fun PeqActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.height(44.dp)
            .alpha(if (enabled) 1f else .4f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PeqControlBlue else PeqControlFill)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled, onClick = onClick)
            .bmwFocusRing(interaction)
            .semantics { this.selected = selected }
            .padding(horizontal = contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PeqControlText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Value in the middle, independent hold-to-repeat buttons at either side.
 *
 * [rangeFraction] (0..1) draws a thin bar along the value's bottom edge showing where it sits in its
 * range -- from the start, or from the centre when [rangeCentred] (a ±dB value). [trailing] is a
 * small secondary readout at the value's right (Q's bandwidth). With [scrubbable], a sideways drag
 * on the value steps it like the buttons (one step per [ScrubStepDp]), previewing as it goes and
 * committing once on release; a tap still opens exact entry.
 */
@Composable
internal fun PeqNumericControl(
    label: String,
    value: String,
    onValueClick: () -> Unit,
    onStep: (Int, Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    showSteppers: Boolean = true,
    stepWidth: Dp = 48.dp,
    valueFontSize: TextUnit = 20.sp,
    height: Dp = 48.dp,
    rangeFraction: Float? = null,
    rangeCentred: Boolean = false,
    rangeColor: Color = PeqControlBlue,
    trailing: String? = null,
    scrubbable: Boolean = false,
) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(8.dp)).background(PeqControlFill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSteppers) HoldStepHalf(
            "−", "Decrease $label", PeqMutedText, true, Modifier.width(stepWidth).fillMaxHeight(),
            { onStep(-1, it) }, StepMath::defaultHoldMultiplier, {}, onCommit,
        )
        val source = remember { MutableInteractionSource() }
        val step by rememberUpdatedState(onStep)
        val commit by rememberUpdatedState(onCommit)
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(PeqValueFill)
                .border(1.dp, PeqMutedText.copy(alpha = .55f))
                .then(if (rangeFraction != null) Modifier.drawBehind {
                    val barHeight = 3.dp.toPx()
                    val top = size.height - barHeight
                    val x = rangeFraction.coerceIn(0f, 1f) * size.width
                    val from = if (rangeCentred) size.width / 2 else 0f
                    drawRect(PeqRangeTrack, Offset(0f, top), Size(size.width, barHeight))
                    drawRect(rangeColor, Offset(minOf(from, x), top), Size(kotlin.math.abs(x - from), barHeight))
                    drawRect(Color.White, Offset(x - 1.5.dp.toPx(), top), Size(3.dp.toPx(), barHeight))
                } else Modifier)
                .then(if (scrubbable) Modifier.pointerInput(Unit) {
                    val stepPx = ScrubStepDp.toPx()
                    var accumulated = 0f
                    var moved = false
                    detectHorizontalDragGestures(
                        onDragStart = { accumulated = 0f; moved = false },
                        onDragEnd = { if (moved) commit() },
                        onDragCancel = { if (moved) commit() },
                    ) { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount
                        val steps = (accumulated / stepPx).toInt()
                        if (steps != 0) {
                            accumulated -= steps * stepPx
                            moved = true
                            step(if (steps > 0) 1 else -1, kotlin.math.abs(steps))
                        }
                    }
                } else Modifier)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onValueClick)
                .semantics { contentDescription = "Edit $label" }
                .bmwFocusRing(source),
            contentAlignment = Alignment.Center,
        ) {
            Text(value, color = PeqControlText, fontSize = valueFontSize, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false)
            if (trailing != null) {
                Text(trailing, color = PeqMutedText, fontSize = 10.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
            }
        }
        if (showSteppers) HoldStepHalf(
            "+", "Increase $label", PeqControlBlue, true, Modifier.width(stepWidth).fillMaxHeight(),
            { onStep(1, it) }, StepMath::defaultHoldMultiplier, {}, onCommit,
        )
    }
}

private val PeqRangeTrack = Color(0xFF1C1F27)
private val ScrubStepDp = 14.dp

/** Reuses the holder's validation/preview/save path in both the table and selected-node editor. */
internal class PeqFilterActions(
    private val holder: PeqStateHolder,
    index: Int,
    private val dialogs: PeqDialogs,
) {
    private val scope = holder.selectedScope
    private val identity = holder.visibleBands.getOrNull(index)?.uuid
    private val currentIndex: Int
        get() = if (holder.selectedScope != scope || identity == null) -1
            else holder.visibleBands.indexOfFirst { it.uuid == identity }
    private val band get() = holder.visibleBands.getOrNull(currentIndex)

    private fun edit(source: String, transform: (ParametricEqBand) -> ParametricEqBand) {
        val index = currentIndex
        if (index >= 0) holder.commitBandEdit(index, source, transform)
    }

    private fun preview(source: String, transform: (ParametricEqBand) -> ParametricEqBand) {
        val index = currentIndex
        if (index >= 0) holder.previewBandEdit(index, source, transform)
    }

    fun channel() {
        val current = band ?: return
        val options = ParametricEqChannel.entries
        dialogs.showChoice(
            app.siphondsp.R.string.peq_channel, options.map { it.displayLabel }, options.indexOf(current.channel),
            android.graphics.Color.rgb(10, 142, 223),
        ) { picked -> options.getOrNull(picked)?.let { channel ->
            edit("edit_channel") { it.withChannel(channel) }
        } }
    }

    fun type() {
        val current = band ?: return
        val options = ParametricEqFilterType.entries
        dialogs.showChoice(
            app.siphondsp.R.string.peq_col_filter, options.map { it.displayLabel }, options.indexOf(current.filterType),
            android.graphics.Color.rgb(10, 142, 223),
        ) { picked -> options.getOrNull(picked)?.let { type ->
            edit("edit_type") { it.withType(type) }
        } }
    }

    fun frequency() {
        val current = band ?: return
        dialogs.showValueInput("Frequency", current.frequency, 20.0, 20000.0, "Hz") { value ->
            edit("edit_frequency") { it.withFrequency(value) }
        }
    }

    fun gain() {
        val current = band ?: return
        dialogs.showValueInput("Gain", current.gain, -30.0, 30.0, "dB") { value ->
            edit("edit_gain") { it.withGain(value) }
        }
    }

    fun q() {
        val current = band ?: return
        dialogs.showValueInput("Q", current.q, 0.1, 30.0, null) { value ->
            edit("edit_q") { it.withQ(value) }
        }
    }

    fun setType(type: ParametricEqFilterType) = edit("edit_type") { it.withType(type) }
    fun setChannel(channel: ParametricEqChannel) = edit("edit_channel") { it.withChannel(channel) }
    fun setQ(value: Double) = edit("edit_q") { it.withQ(value.coerceIn(0.1, 30.0)) }

    fun stepFrequency(direction: Int, times: Int) = preview("step_frequency") { band ->
        var frequency = band.frequency
        repeat(times) { frequency = StepMath.nextFrequency(frequency, direction) }
        band.withFrequency(frequency.coerceIn(20.0, 20000.0))
    }

    fun stepGain(direction: Int, times: Int) = preview("step_gain") {
        it.withGain((it.gain + direction * times * 0.5).coerceIn(-30.0, 30.0))
    }

    fun stepQ(direction: Int, times: Int) = preview("step_q") {
        it.withQ((it.q + direction * times * 0.1).coerceIn(0.1, 30.0))
    }

    fun commit() = holder.commitPreview("filter_step")
}

@Composable
internal fun rememberPeqFilterActions(holder: PeqStateHolder, index: Int): PeqFilterActions {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dialogs = remember(context) { PeqDialogs(context) }
    return remember(holder, holder.visibleBands.getOrNull(index)?.uuid, holder.selectedScope, dialogs) { PeqFilterActions(holder, index, dialogs) }
}

internal fun formatPeqValue(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
internal fun ParametricEqBand.withFrequency(value: Double) = ParametricEqBand(value, gain, q, filterType, channel, uuid, enabled)
internal fun ParametricEqBand.withGain(value: Double) = ParametricEqBand(frequency, value, q, filterType, channel, uuid, enabled)
internal fun ParametricEqBand.withQ(value: Double) = ParametricEqBand(frequency, gain, value, filterType, channel, uuid, enabled)
internal fun ParametricEqBand.withType(value: ParametricEqFilterType) = ParametricEqBand(frequency, gain, q, value, channel, uuid, enabled)
internal fun ParametricEqBand.withChannel(value: ParametricEqChannel) = ParametricEqBand(frequency, gain, q, filterType, value, uuid, enabled)
internal fun ParametricEqBand.withEnabled(value: Boolean) = ParametricEqBand(frequency, gain, q, filterType, channel, uuid, value)
