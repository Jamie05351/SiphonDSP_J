package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

private val ValueFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
private const val DisabledRowAlpha = 0.4f

/**
 * Compose equivalent of `CrossoverDashboardBuilder.addSliderRow`: a boxed title, a fixed
 * toggle-zone gap (so slider starts line up whether or not a row has an inline switch), a
 * [BmwSlider] filling the middle, and a boxed value readout pinned at the end.
 *
 * State is hoisted -- [value] is the persisted value. While the user drags, a local copy drives
 * the thumb + readout; [onPreview] fires on every change (live, no disk) and [onCommit] fires on
 * release, matching the View path's `onChanged`-on-drag / persist model (see
 * [app.siphondsp.compose.state.BmwDspState]).
 */
@Composable
fun BmwSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accentColor: Color,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragValue by remember(value) { mutableFloatStateOf(value) }
    val steps = remember(valueRange, step) {
        if (step > 0f) {
            (((valueRange.endInclusive - valueRange.start) / step).roundToInt() - 1).coerceAtLeast(0)
        } else {
            0
        }
    }
    val shown = dragValue.coerceIn(valueRange.start, valueRange.endInclusive)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(top = 2.dp, bottom = 1.dp)
            .alpha(if (enabled) 1f else DisabledRowAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxedTitle(
            text = label,
            accentColor = accentColor,
            modifier = Modifier.width(RowTitleColumnWidth).height(RowBoxHeight),
        )
        Spacer(Modifier.width(RowToggleZoneWidth))
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
        Spacer(Modifier.width(RowValueGap))
        BoxedValue(
            text = ValueFormat.format(shown),
            unit = unit,
            accentColor = accentColor,
            modifier = Modifier.width(RowValueWidth).height(RowBoxHeight),
        )
    }
}

private fun snapToStep(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (step <= 0f) return raw.coerceIn(range.start, range.endInclusive)
    val snapped = range.start + ((raw - range.start) / step).roundToInt() * step
    return snapped.coerceIn(range.start, range.endInclusive)
}
