package app.siphondsp.compose.controls

import androidx.compose.foundation.LocalIndication
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Head-unit building blocks for the sub-menu pages laid out in REW/_UI/submenu_layout_editor.html.
 * Each control sits over an [artDp] rect of the workspace art inside a [WorkspaceArtBox]; the
 * controls themselves (BmwSlider, BmwSwitch, BoxedValue, BmwDropdown) are the app's own, unchanged
 * -- these only arrange them and size the label / value-box text.
 */

/** A rect in dp on the 1280x480 head unit (the editor's units) as a fraction of the workspace art,
 *  which is the head unit's exact aspect. Keep y at or below 69 (`dsp_workspace_toolbar_height`):
 *  the pages are hosted under the toolbar, so anything higher is clipped. */
fun artDp(x: Int, y: Int, w: Int, h: Int) = WorkspaceArt.Frac(x / 1280f, y / 480f, w / 1280f, h / 480f)

val ArtLabelSize = 16.sp
val ArtValueSize = 20.sp
val ArtValueHeight = 48.dp
private val ArtUnitSize = 14.sp
private val ArtLabelColor = Color(0xFF969EA8)
private const val DisabledAlpha = 0.4f
private val ArtValueFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

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
 * A [BmwSlider] with its value box at the end, same drag/preview/commit model as [BmwSliderRow].
 * [labelAbove] puts the label over the slider's start (or end, with [alignEnd]); otherwise it
 * sits in a [labelWidth] column on the left.
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
    // Material3 pads the slider to a 48 dp touch target; tighter rows pass less.
    sliderMinTouchHeight: Dp = 48.dp,
) {
    val context = LocalContext.current
    var dragValue by remember(value) { mutableFloatStateOf(value) }
    val steps = remember(valueRange, step) {
        if (step > 0f) (((valueRange.endInclusive - valueRange.start) / step).roundToInt() - 1).coerceAtLeast(0) else 0
    }
    val shown = dragValue.coerceIn(valueRange.start, valueRange.endInclusive)

    val sliderAndValue: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides sliderMinTouchHeight) {
                BmwSlider(
                    value = shown,
                    onValueChange = {
                        val snapped = snapToStep(it, valueRange, step)
                        dragValue = snapped
                        onPreview(snapped)
                    },
                    onValueChangeFinished = { onCommit(snapToStep(dragValue, valueRange, step)) },
                    valueRange = valueRange,
                    steps = steps,
                    accentColor = accentColor,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(RowValueGap))
            ArtValueBox(ArtValueFormat.format(shown), unit, accentColor, width = valueWidth) {
                context.showBmwNumberInput(label, valueRange.start, valueRange.endInclusive, dragValue, step, unit) {
                    dragValue = it
                    onCommit(it)
                }
            }
        }
    }

    val dim = if (enabled) Modifier else Modifier.alpha(DisabledAlpha)
    if (labelAbove) {
        ArtStacked(label, modifier.then(dim), alignEnd = alignEnd) {
            sliderAndValue(Modifier.fillMaxWidth().height(ArtValueHeight))
        }
    } else {
        ArtRow(label, modifier.then(dim), labelWidth = labelWidth) {
            sliderAndValue(Modifier.weight(1f).height(ArtValueHeight))
        }
    }
}

/** A labelled [BmwDspKnob] with the existing tap-to-type value box beneath it. */
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
    diameter: Dp = 104.dp,
    valueWidth: Dp = 96.dp,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    var dragValue by remember(value) { mutableFloatStateOf(value) }
    val shown = dragValue.coerceIn(valueRange.start, valueRange.endInclusive)
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
        BmwDspKnob(
            value = shown,
            valueRange = valueRange,
            step = step,
            accentColor = accentColor,
            onPreview = {
                dragValue = it
                onPreview(it)
            },
            onCommit = {
                dragValue = it
                onCommit(it)
            },
            enabled = enabled,
            accessibilityLabel = label,
            diameter = diameter,
            modifier = Modifier.size(diameter),
        )
        ArtValueBox(
            text = ArtValueFormat.format(shown),
            unit = unit,
            accentColor = accentColor,
            width = valueWidth,
        ) {
            context.showBmwNumberInput(label, valueRange.start, valueRange.endInclusive, shown, step, unit) {
                dragValue = it
                onCommit(it)
            }
        }
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

/** A page title in the top-left corner of the content frame. */
@Composable
fun ArtTitle(text: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        ArtLabel(text, color = color)
    }
}
