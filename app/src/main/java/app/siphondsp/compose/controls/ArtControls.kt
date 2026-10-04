package app.siphondsp.compose.controls

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Head-unit building blocks for the sub-menu pages laid out in REW/_UI/submenu_layout_editor.html.
 * Each control sits over an [artDp] rect of the workspace art inside a [WorkspaceArtBox]; the
 * controls themselves (ValueStepper, BmwSwitch, BoxedValue, BmwDropdown) are the app's own,
 * unchanged -- these only arrange them and size the label / value-box text.
 */

/** A rect in dp on the 1280x480 head unit (the editor's units) as a fraction of the workspace art,
 *  which is the head unit's exact aspect. Keep y at or below 69 (`dsp_workspace_toolbar_height`):
 *  the pages are hosted under the toolbar, so anything higher is clipped. */
fun artDp(x: Int, y: Int, w: Int, h: Int) = WorkspaceArt.Frac(x / 1280f, y / 480f, w / 1280f, h / 480f)

val ArtLabelSize = 16.sp
val ArtValueSize = 20.sp
val ArtValueHeight = 48.dp
private val ArtUnitSize = CarUi.MinText
private val ArtLabelColor = Color(0xFF969EA8)
private const val DisabledAlpha = 0.4f

@Composable
fun ArtLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ArtLabelColor,
    size: TextUnit = ArtLabelSize,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.03.em,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/** Label along the top of the rect, [content] pinned to its bottom -- both on the start edge, or
 *  both on the end edge when [alignEnd] (mirrored right-hand columns). */
@Composable
fun ArtStacked(
    label: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        ArtLabel(
            text = label,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
        )
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomStart),
            contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
            content = content,
        )
    }
}

/** Label in a fixed-width column on the left, [content] after it, vertically centred. */
@Composable
fun ArtRow(
    label: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 150.dp,
    labelColor: Color = ArtLabelColor,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ArtLabel(label, Modifier.width(labelWidth), color = labelColor)
        content()
    }
}

/** The app's boxed value readout at head-unit size; tapping it opens the number-entry dialog. */
@Composable
fun ArtValueBox(
    text: String,
    unit: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    width: Dp = 84.dp,
    height: Dp = ArtValueHeight,
    onTap: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    BoxedValue(
        text = text,
        unit = unit,
        accentColor = accentColor,
        textSize = ArtValueSize,
        unitSize = ArtUnitSize,
        modifier = modifier
            .width(width)
            .height(height)
            .then(
                if (onTap != null) {
                    Modifier
                        .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onTap)
                        .bmwFocusRing(interactionSource)
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * A labelled [ValueStepper] -- value box and −/+ -- at head-unit size. (This was a [BmwSlider];
 * every DSP slider became a stepper, which is easier to use at a glance in a car and takes far
 * less room.) [labelAbove] puts the label over the stepper's start (or end, with [alignEnd]);
 * otherwise it sits in a [labelWidth] column on the left. [valueWidth] is the value box's width.
 * [sliderMinTouchHeight] is kept for existing callers and ignored: the stepper is always
 * [ArtValueHeight] tall.
 */
@Composable
fun ArtSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accentColor: Color,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    labelAbove: Boolean = false,
    alignEnd: Boolean = false,
    labelWidth: Dp = 150.dp,
    valueWidth: Dp = 96.dp,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") sliderMinTouchHeight: Dp = 48.dp,
) {
    val stepper: @Composable () -> Unit = {
        ValueStepper(
            label = label,
            value = value,
            valueRange = valueRange,
            step = step,
            unit = unit,
            accentColor = accentColor,
            onPreview = onPreview,
            onCommit = onCommit,
            boxWidth = valueWidth,
            height = ArtValueHeight,
            textSize = ArtValueSize,
            unitSize = ArtUnitSize,
            enabled = enabled,
        )
    }
    val dim = if (enabled) Modifier else Modifier.alpha(DisabledAlpha)
    if (labelAbove) {
        ArtStacked(label, modifier.then(dim), alignEnd = alignEnd) { stepper() }
    } else {
        ArtRow(label, modifier.then(dim), labelWidth = labelWidth) { stepper() }
    }
}

/**
 * A labelled [ValueStepper] in place of what was a rotary knob: the label centred over the value
 * box and −/+. [diameter] is kept for existing callers and ignored.
 */
@Composable
fun ArtKnob(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accentColor: Color,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") diameter: Dp = 104.dp,
    valueWidth: Dp = 96.dp,
    enabled: Boolean = true,
) {
    val dim = if (enabled) Modifier else Modifier.alpha(DisabledAlpha)
    Column(
        modifier = modifier.then(dim),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ArtLabel(
            text = label,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        ValueStepper(
            label = label,
            value = value,
            valueRange = valueRange,
            step = step,
            unit = unit,
            accentColor = accentColor,
            onPreview = onPreview,
            onCommit = onCommit,
            boxWidth = valueWidth,
            height = ArtValueHeight,
            textSize = ArtValueSize,
            unitSize = ArtUnitSize,
            enabled = enabled,
        )
    }
}

/** A labelled [BmwSwitch] on one row. */
@Composable
fun ArtSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 150.dp,
    labelColor: Color = ArtLabelColor,
) {
    ArtRow(label, modifier, labelWidth = labelWidth, labelColor = labelColor) {
        BmwSwitch(checked = checked, onCheckedChange = onCheckedChange, contentDescription = label)
    }
}

/**
 * A group's title in its colour, an optional switch at the far end, and a rule under both -- the
 * heading of each column of controls (the Output page's Output / Limiter, the bus limiters).
 */
@Composable
fun ArtGroupHeader(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ArtLabel(title.uppercase(), Modifier.weight(1f), color = accent)
            if (checked != null && onCheckedChange != null) {
                BmwSwitch(checked = checked, onCheckedChange = onCheckedChange, contentDescription = title)
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(accent.copy(alpha = 0.45f)))
    }
}

/** A page title in the top-left corner of the content frame. */
@Composable
fun ArtTitle(text: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        ArtLabel(text, color = color)
    }
}
