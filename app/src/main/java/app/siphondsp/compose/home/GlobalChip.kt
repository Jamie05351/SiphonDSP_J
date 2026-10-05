package app.siphondsp.compose.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

/**
 * One global stage in the status panel: a dot, the stage's name and its setting ("TILT +1.5 dB").
 * Lit in [accent] while the stage is on and the DSP is powered; idle grey otherwise, blending
 * between the two by [globalActive] times the chip's own animated on fraction. Tapping it opens the
 * screen that owns the stage.
 */
@Composable
fun GlobalChip(
    label: String,
    value: String,
    accent: Color,
    on: Boolean,
    globalActive: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalHomeScale.current
    val t = animateActive(on, "chip $label") * globalActive
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .background(lerp(HomePalette.Idle.copy(alpha = 0.25f), accent.copy(alpha = 0.14f), t))
            .border(s.dp(1.5f), lerp(HomePalette.Idle.copy(alpha = 0.6f), accent.copy(alpha = 0.55f), t), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = s.dp(12f), end = s.dp(16f), top = s.dp(9f), bottom = s.dp(9f)),
        horizontalArrangement = Arrangement.spacedBy(s.dp(8f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(s.dp(8f)).background(lerp(HomePalette.Label, accent, t), CircleShape))
        Text(
            text = label,
            color = lerp(HomePalette.Label, accent, t),
            fontSize = s.sp(14f),
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(letterSpacing = 0.06.em),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = value,
            color = lerp(HomePalette.Label, Color.White.copy(alpha = 0.85f), t),
            fontSize = s.sp(14f),
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
        )
    }
}
