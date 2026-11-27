package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.bmwFocusRing
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

/**
 * Bank tabs, each marked with its bank colour (the graph legend's and crossover tint's) and, when
 * [counts] is given and there's room, how many filters it holds. The selected tab is underlined in
 * its bank colour.
 */
@Composable
fun PeqScopeControl(
    selected: PeqScope,
    onSelect: (PeqScope) -> Unit,
    modifier: Modifier = Modifier,
    counts: Map<PeqScope, Int> = emptyMap(),
) {
    BoxWithConstraints(modifier) {
        val roomy = maxWidth >= 360.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PeqScope.entries.forEach { scope ->
                val isSelected = scope == selected
                val bank = Color(scopeColour(scope))
                val source = remember { MutableInteractionSource() }
                Row(
                    Modifier.weight(if (scope == PeqScope.FULL) 1.3f else 1f).height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) PeqPanelFill else PeqControlFill)
                        .drawBehind {
                            if (isSelected) {
                                val h = 3.dp.toPx()
                                drawRect(bank, Offset(0f, size.height - h), Size(size.width, h))
                            }
                        }
                        .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(scope) }
                        .bmwFocusRing(source)
                        .semantics { this.selected = isSelected }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(bank))
                    Text(scope.label.removeSuffix(" Band"), color = if (isSelected) PeqControlText else PeqMutedText,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    val count = counts[scope]
                    if (roomy && count != null) {
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(PeqValueFill).padding(horizontal = 6.dp, vertical = 1.dp)) {
                            Text("$count", color = PeqMutedText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
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
