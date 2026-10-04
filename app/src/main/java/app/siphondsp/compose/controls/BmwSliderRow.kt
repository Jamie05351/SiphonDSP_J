package app.siphondsp.compose.controls

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

private const val DisabledRowAlpha = 0.4f
private val DropdownTitleTextSize = 14.sp
private val DropdownChevronSize = 18.sp
private val DropdownTitlePadding = 18.dp
private val DropdownMenuBackground = Color(0xFF14181F)

/**
 * Turns a [BmwSliderRow]'s title into a dropdown: the one bordered box reads
 * "[label]  [selected option] v" and opens [options] when tapped. The box also spans the row's
 * toggle-zone gap (rows using this have no inline switch), so the slider still starts where it
 * does on every other row.
 */
class BmwTitleDropdown(
    val options: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
)

/**
 * Compose equivalent of `CrossoverDashboardBuilder.addSliderRow`: a boxed title, a fixed
 * toggle-zone gap (where a row's inline switch sits), then the value box and −/+ ([ValueStepper])
 * pinned at the end. (It was a slider filling the middle; every DSP slider became a stepper,
 * which is easier to use at a glance in a car and takes far less room.)
 *
 * State is hoisted -- [value] is the persisted value. [onPreview] fires on every step (live, no
 * disk) and [onCommit] once a tap or hold is finished, matching the View path's
 * `onChanged`-on-drag / persist model (see [app.siphondsp.compose.state.BmwDspState]). Typed
 * values go to [onValueEntered] when it is set, else to [onCommit].
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
    // When set, the title becomes a dropdown (see BmwTitleDropdown) and toggleChecked is ignored.
    titleDropdown: BmwTitleDropdown? = null,
    // Where a value typed into the value box goes (parsed, snapped, coerced); null sends it to
    // onCommit. Tapping the value box always opens the numeric-entry dialog.
    onValueEntered: ((Float) -> Unit)? = null,
    // When set, an inline BmwSwitch sits in the toggle-zone slot between the title and the
    // slider (the View's addSliderRow `toggleIndex` case -- e.g. the compressor Mix row).
    toggleChecked: Boolean? = null,
    onToggleChange: ((Boolean) -> Unit)? = null,
    // The stepper's height, which sets the row pitch. Pages that need to fit more rows on the
    // 480dp head unit pass less; Unspecified uses StepperRowHeight.
    sliderMinTouchHeight: Dp = Dp.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(top = 2.dp, bottom = 1.dp)
            .alpha(if (enabled) 1f else DisabledRowAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (titleDropdown != null) {
            BoxedDropdownTitle(
                label = label,
                dropdown = titleDropdown,
                accentColor = accentColor,
                modifier = Modifier.width(LocalRowTitleColumnWidth.current + RowToggleZoneWidth).height(RowBoxHeight),
            )
        } else {
            BoxedTitle(
                text = label,
                accentColor = accentColor,
                modifier = Modifier.width(LocalRowTitleColumnWidth.current).height(RowBoxHeight),
            )
            if (toggleChecked != null && onToggleChange != null) {
                Box(Modifier.width(RowToggleZoneWidth), contentAlignment = Alignment.Center) {
                    BmwSwitch(checked = toggleChecked, onCheckedChange = onToggleChange, contentDescription = label)
                }
            } else {
                Spacer(Modifier.width(RowToggleZoneWidth))
            }
        }
        Spacer(Modifier.weight(1f).widthIn(min = RowValueGap))
        ValueStepper(
            label = label,
            value = value,
            valueRange = valueRange,
            step = step,
            unit = unit,
            accentColor = accentColor,
            onPreview = onPreview,
            onCommit = onCommit,
            boxWidth = RowValueWidth,
            height = if (sliderMinTouchHeight.isSpecified) sliderMinTouchHeight else StepperRowHeight,
            textSize = StepperTextSize,
            unitSize = CarUi.MinDenseText,
            enabled = enabled,
            onTyped = onValueEntered,
        )
    }
}

@Composable
private fun BoxedDropdownTitle(
    label: String,
    dropdown: BmwTitleDropdown,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val selectedText = dropdown.options.getOrElse(dropdown.selectedIndex) { "" }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .bmwGlassBox(accentColor)
                .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { expanded = true }
                .bmwFocusRing(interactionSource)
                .padding(horizontal = DropdownTitlePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = DropdownTitleTextSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = selectedText,
                color = accentColor,
                fontSize = DropdownTitleTextSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(text = "\u25BE", color = accentColor, fontSize = DropdownChevronSize)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(DropdownMenuBackground),
        ) {
            dropdown.options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            color = if (index == dropdown.selectedIndex) accentColor else Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    onClick = {
                        expanded = false
                        dropdown.onSelect(index)
                    },
                )
            }
        }
    }
}

private val StepperRowHeight = 44.dp
private val StepperTextSize = 16.sp

internal fun snapToStep(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (step <= 0f) return raw.coerceIn(range.start, range.endInclusive)
    val snapped = range.start + ((raw - range.start) / step).roundToInt() * step
    return snapped.coerceIn(range.start, range.endInclusive)
}
