package app.siphondsp.compose.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.siphondsp.compose.theme.BmwDspTheme

/** TEMPORARY (Phase 3b switch verification): a handful of BmwSwitches -- default ON, default
 *  OFF, and a disabled one -- each independently toggleable, to confirm the glass track / ON-OFF
 *  label / glowing sphere thumb chrome renders and responds to touch on-device before it's built
 *  on further. Same lifecycle as ComposeSmokeTest / BmwSliderDemo -- delete once confirmed. */
@Composable
fun BmwSwitchDemo() {
    BmwDspTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var a by remember { mutableStateOf(true) }
            SwitchRow("Starts ON", a) { a = it }

            var b by remember { mutableStateOf(false) }
            SwitchRow("Starts OFF", b) { b = it }

            var c by remember { mutableStateOf(true) }
            SwitchRow("Disabled", c, enabled = false) { c = it }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BmwSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            contentDescription = label,
        )
        Text("$label  —  ${if (checked) "ON" else "OFF"}", color = Color.White)
    }
}
