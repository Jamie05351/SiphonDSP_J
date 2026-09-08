package app.siphondsp.compose.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R

private val DropdownStroke = Color(0xFF434952) // CrossoverDashboardBuilder.segmentStroke rgb(67,73,82)
private val DropdownIdle = Color(0xFF14171C) // segmentIdle rgb(20,23,28)
private val DropdownText = Color(0xFFDCE2E8) // rgb(220,226,232)
private val DropdownShape = RoundedCornerShape(4.dp)

/**
 * Compose port of the outlined dropdown button in `CrossoverDashboardBuilder.addDropdownRow` --
 * a `segmentStroke`-bordered pill showing the current option, opening a [DropdownMenu] on tap
 * (the View uses a `PopupMenu`). Same colours / 32dp min height / 4dp corner / trailing
 * down-arrow.
 */
@Composable
fun BmwDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .border(1.dp, DropdownStroke, DropdownShape)
                .background(DropdownIdle, DropdownShape)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = options.getOrElse(selectedIndex) { "" },
                color = DropdownText,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_down_24dp),
                contentDescription = null,
                tint = DropdownText,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}

/**
 * The toggle-in-title-column + dropdown row from `addDropdownRow`'s `toggleIndex != null` case
 * (the Output all-pass "Type" rows): a [BmwSwitch] where the label box would be, then a fixed
 * toggle-zone gap, then a [BmwDropdown] filling the width, then an empty value-box-width spacer
 * so it lines up with the Frequency / Q slider rows below.
 */
@Composable
fun BmwDropdownRow(
    toggleChecked: Boolean,
    onToggleChange: (Boolean) -> Unit,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(top = 2.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(LocalRowTitleColumnWidth.current),
            contentAlignment = Alignment.CenterStart,
        ) {
            BmwSwitch(checked = toggleChecked, onCheckedChange = onToggleChange)
        }
        Spacer(Modifier.width(RowToggleZoneWidth))
        BmwDropdown(options, selectedIndex, onSelect, Modifier.weight(1f))
        Spacer(Modifier.width(RowValueGap))
        Spacer(Modifier.width(RowValueWidth))
    }
}
