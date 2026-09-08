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
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.fragment.PeqScope
import app.siphondsp.view.BmwDashboardSkin

/**
 * The Pre EQ / Low Band / Mid Band scope switch -- a [BmwSegmentedControl] over [PeqScope],
 * each segment in its bank's accent (Full = white, Low = blue, Mid = yellow). Shared by the
 * graph and list halves of the ported Parametric EQ screen (roadmap Phase 10).
 */
@Composable
fun PeqScopeControl(
    selected: PeqScope,
    onSelect: (PeqScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    BmwSegmentedControl(
        options = PeqScope.entries.map { it.label },
        selectedIndex = PeqScope.entries.indexOf(selected),
        onSelect = { onSelect(PeqScope.entries[it]) },
        optionAccents = listOf(
            Color.White,
            Color(BmwDashboardSkin.LIGHT_BLUE),
            Color(BmwDashboardSkin.MID_BAND_YELLOW),
        ),
        modifier = modifier,
    )
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
