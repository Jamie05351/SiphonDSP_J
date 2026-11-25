package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.fragment.PeqScope

/** Compact bank chips; graph colours remain independent of the control accent. */
@Composable
fun PeqScopeControl(
    selected: PeqScope,
    onSelect: (PeqScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PeqScope.entries.forEachIndexed { index, scope ->
            PeqActionButton(listOf("Pre EQ", "Low", "Mid", "High")[index], { onSelect(scope) },
                Modifier.weight(1f), selected = scope == selected)
        }
    }
}

@Preview(widthDp = 420, backgroundColor = 0xFF0B0B0B, showBackground = true)
@Composable
private fun PeqScopeControlPreview() {
    BmwDspTheme {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                PeqScopeControl(selected = PeqScope.FULL, onSelect = {}, modifier = Modifier.fillMaxWidth())
            }
            Text("selected: Pre EQ", color = Color.White)
        }
    }
}
