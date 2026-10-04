package app.siphondsp.compose.controls

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Grey glyphs and divider, as on the PEQ filter list. */
internal val MinusPlusGlyphColor = Color(0xFF9AA1AB)
internal val MinusPlusFill = Color(0xFF23272F)

/**
 * The PEQ filter list's −/+ stepper: one pill split by a thin divider, rather than two separate
 * buttons, so it stays compact next to the value box it steps. Shared by the PEQ list and the
 * Delay page cards; [label] names the value for accessibility ("Left Mid delay" -> "Decrease Left Mid delay"); include
 * enough to tell repeated steppers apart, e.g. the channel and band.
 */
@Composable
internal fun MinusPlusPill(
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    halfWidth: Dp = 40.dp,
    label: String? = null,
    glyphColor: Color = MinusPlusGlyphColor,
) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(MinusPlusFill),
    ) {
        GlyphHalf("−", label?.let { "Decrease $it" }, glyphColor, Modifier.width(halfWidth).fillMaxHeight(), onMinus)
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight(0.6f)
                .align(Alignment.CenterVertically)
                .background(glyphColor),
        )
        GlyphHalf("+", label?.let { "Increase $it" }, glyphColor, Modifier.width(halfWidth).fillMaxHeight(), onPlus)
    }
}

@Composable
private fun GlyphHalf(text: String, description: String?, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .bmwFocusRing(interactionSource)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
