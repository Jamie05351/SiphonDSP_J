package app.siphondsp.compose.screens

import app.siphondsp.view.isHeadUnitDisplay
import app.siphondsp.fragment.PeqScope
import app.siphondsp.compose.nav.DspWorkspaceScaffold
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.WorkspaceArt
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
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

/**
 * The PEQ in the chrome every DSP workspace shares ([DspWorkspaceScaffold]): the sidebar rail, the
 * back arrow and status strip, and the PEQ-blue content window. Its toolbar holds the bank finder
 * (Pre EQ / Low / Mid / High, styled like the other workspaces' page finders, with each bank's
 * dot and filter count), undo / redo and one ⋯ menu.
 */
@Composable
internal fun PeqWorkspaceLayout(holder: PeqStateHolder, showSpectrum: Boolean = true) {
    val navigator = LocalAppNavigator.current
    val headUnit = LocalContext.current.isHeadUnitDisplay()
    CompositionLocalProvider(LocalGlassAccent provides PeqControlBlue) {
        Box(Modifier.fillMaxSize().testTag("peq-workspace")) {
            DspWorkspaceScaffold(
                current = DspDestination.PARAMETRIC_EQ,
                onNavigate = { navigator.openWorkspace(it) },
                onBack = navigator::back,
                // A phone's toolbar line has no room for the strip beside the banks.
                showStatusStrip = headUnit,
                toolbarActions = { PeqToolbar(holder, headUnit) },
            ) {
                ParametricEqScreen(
                    holder,
                    Modifier.fillMaxSize().testTag("peq-content-frame")
                        .padding(start = PeqContentInset, end = PeqContentInset, top = 6.dp),
                    showSpectrum,
                )
            }
        }
    }
}

/** The toolbar line: banks at the page finder's place, undo / redo / ⋯ just before them. */
@Composable
private fun PeqToolbar(holder: PeqStateHolder, headUnit: Boolean) {
    val banks: @Composable (Modifier) -> Unit = { m ->
        PeqBankFinder(
            selected = holder.selectedScope,
            onSelect = { holder.selectedScope = it },
            counts = PeqScope.entries.associateWith { holder.peqState.bandCount(it) },
            modifier = m,
            // A phone's 80 dp segments have room for the dot and name only.
            showCounts = headUnit,
        )
    }
    if (headUnit) {
        WorkspaceArtBox(
            modifier = Modifier.fillMaxSize(),
            originX = dimensionResource(app.siphondsp.R.dimen.dsp_status_strip_margin_start),
            originY = 0.dp,
        ) {
            PeqToolbarActions(holder, Modifier.artRect(PeqActionsFrac))
            banks(Modifier.artRect(WorkspaceArt.finder4))
        }
    } else {
        Row(
            Modifier.fillMaxSize().padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeqToolbarActions(holder, Modifier.width(120.dp).height(36.dp))
            banks(Modifier.width(320.dp).height(36.dp))
        }
    }
}

/**
 * Pre EQ / Low / Mid / High in the shape of the workspaces' page finder ([DspPageFinder]): one
 * PEQ-blue outlined bar, a segment per bank, the selected one filled. Each keeps its bank dot and
 * filter count, which the plain finder has no room for.
 */
@Composable
internal fun PeqBankFinder(
    selected: PeqScope,
    onSelect: (PeqScope) -> Unit,
    counts: Map<PeqScope, Int>,
    modifier: Modifier = Modifier,
    showCounts: Boolean = true,
) {
    val shape = RoundedCornerShape(3.dp)
    Box(modifier) {
        Row(
            Modifier.fillMaxSize().clip(shape).background(Color(0xFF0E0F11)).border(2.5.dp, PeqControlBlue, shape),
        ) {
            PeqScope.entries.forEachIndexed { i, scope ->
                if (i > 0) Box(Modifier.width(2.5.dp).fillMaxHeight().background(PeqControlBlue))
                val isSelected = scope == selected
                val source = remember { MutableInteractionSource() }
                Row(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (isSelected) PeqControlBlue.copy(alpha = .55f) else Color.Transparent)
                        .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(scope) }
                        .bmwFocusRing(source)
                        .semantics { this.selected = isSelected },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(scopeColour(scope))))
                    Text(
                        scope.label.removeSuffix(" Band").uppercase(java.util.Locale.ROOT),
                        color = if (isSelected) Color.White else Color(0xFFBDDFF1),
                        fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.12.em, maxLines = 1,
                    )
                    val count = counts[scope]
                    if (showCounts && count != null) {
                        Box(
                            Modifier.clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) Color(0xFF03304D) else PeqValueFill)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text("$count", color = if (isSelected) Color.White else PeqMutedText, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

/** A square toolbar button (undo, redo, ⋯) that fills the height it's given. */
@Composable
internal fun PeqIconButton(glyph: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxHeight().alpha(if (enabled) 1f else .4f).clip(RoundedCornerShape(8.dp)).background(PeqControlFill)
            .clickable(interactionSource = source, indication = null, role = Role.Button, enabled = enabled, onClick = onClick)
            .bmwFocusRing(source)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = PeqControlText, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// Undo / redo / ⋯ on the toolbar line, ending just before the bank finder (WorkspaceArt.finder4).
private val PeqActionsFrac = WorkspaceArt.Frac(0.484f, 0.048f, 0.080f, 0.0693f)
// The content's inset from the window's rim, as the other workspaces' panels sit.
internal val PeqContentInset = 4.dp

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
 * The workspaces' stepper look: the value in a box edged and lit in [accent], then a −|+ pill with
 * independent hold-to-repeat halves (as [ArtSlider]'s steppers). Tapping the value opens exact
 * entry.
 *
 * [rangeFraction] (0..1) draws a thin bar along the value's bottom edge showing where it sits in its
 * range -- from the start, or from the centre when [rangeCentred] (a ±dB value). [trailing] is a
 * small secondary readout at the value's left (Q's bandwidth). With [scrubbable], a sideways drag
 * on the value steps it like the buttons (one step per [ScrubStepDp]), previewing as it goes and
 * committing once on release.
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
    accent: Color = rangeColor,
    /** The workspaces' look (value box, then a −|+ pill). Off: the table's compact − value + row. */
    pill: Boolean = false,
) {
    if (!pill) {
        CompactNumericControl(label, value, onValueClick, onStep, onCommit, modifier, showSteppers, stepWidth,
            valueFontSize, height, rangeFraction, rangeCentred, rangeColor, trailing, scrubbable)
        return
    }
    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val source = remember { MutableInteractionSource() }
        val step by rememberUpdatedState(onStep)
        val commit by rememberUpdatedState(onCommit)
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .drawBehind {
                    // The accent glow around the box, as the shared steppers light their value.
                    drawRoundRect(accent.copy(alpha = .22f), Offset(-3.dp.toPx(), -3.dp.toPx()),
                        Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), CornerRadius(10.dp.toPx()))
                }
                .clip(shape)
                .background(PeqValueFill)
                .border(1.5.dp, accent.copy(alpha = .9f), shape)
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
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
            }
        }
        if (showSteppers) Row(
            Modifier.width(stepWidth * 2 + 1.5.dp).fillMaxHeight().clip(shape).background(PeqControlFill),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoldStepHalf(
                "−", "Decrease $label", PeqControlText, true, Modifier.width(stepWidth).fillMaxHeight(),
                { onStep(-1, it) }, StepMath::defaultHoldMultiplier, {}, onCommit,
            )
            Box(Modifier.width(1.5.dp).fillMaxHeight(.5f).background(Color(0xFF3A3F4B)))
            HoldStepHalf(
                "+", "Increase $label", PeqControlText, true, Modifier.width(stepWidth).fillMaxHeight(),
                { onStep(1, it) }, StepMath::defaultHoldMultiplier, {}, onCommit,
            )
        }
    }
}

/** The filter table's compact stepper: − at the left, the value, + at the right, in one bar. */
@Composable
private fun CompactNumericControl(
    label: String,
    value: String,
    onValueClick: () -> Unit,
    onStep: (Int, Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier,
    showSteppers: Boolean,
    stepWidth: Dp,
    valueFontSize: TextUnit,
    height: Dp,
    rangeFraction: Float?,
    rangeCentred: Boolean,
    rangeColor: Color,
    trailing: String?,
    scrubbable: Boolean,
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
