package app.siphondsp.compose.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * The front page is laid out in the Figma frame's units ("Home · DSP ON", 1920 x 830) and scaled
 * as a whole by [k], picked by [HomeScreen] so the frame's height fits: the head unit's 1280x480
 * and a phone in landscape both get the same proportions, with spare width going to the meter
 * panel and the gaps between tiles.
 */
@Immutable
class HomeScale(val k: Float) {
    fun dp(design: Float): Dp = (design * k).dp
    fun sp(design: Float): TextUnit = (design * k).sp
}

val LocalHomeScale = staticCompositionLocalOf { HomeScale(1f) }

/**
 * How "on" a piece of the page looks, 0 (idle grey) to 1 (lit). Every on/off visual on the page is
 * driven from these fractions, never from the booleans directly: one per tile pill and chip, and
 * one global one for the DSP power. A stage's effective look is its own fraction times the global
 * one, so a stage that is on still greys out while the whole DSP is bypassed.
 */
@Composable
fun animateActive(on: Boolean, label: String): Float {
    val active by animateFloatAsState(if (on) 1f else 0f, tween(ActiveMs), label = label)
    return active
}

/** The page's neutral colours, from the Figma frames. Stage accents come from the app theme. */
internal object HomePalette {
    val Page = Color(0xFF0E0F12)
    val Panel = Color(0xFF050506)
    val PanelEdge = Color(0xFF2A2C31)
    val TileBase = Color(0xFF0A0A0D)
    val Label = Color(0xFF7A7F8A)
    val Dim = Color(0xFF5A5F6A)
    val Muted = Color(0xFF6B707A)
    val Caption = Color(0xFF9AA0AA)
    val Subtitle = Color(0xFFB4B8C2)
    val Button = Color(0xFFD1D5DB)
    val Idle = Color(0xFF4A4E57)
    val IdleFill = Color(0xFF1C1E23)
    val IdleEdge = Color(0xFF353840)
    val NodeFill = Color(0xFF141519)
    val NodeEdgeOff = Color(0xFF3A3D45)
    val OutFill = Color(0xFF0B0B0D)
    val MeterTrack = Color(0xFF17181C)
    val MeterGreen = Color(0xFF22C55E)
    val MeterAmber = Color(0xFFEAB308)
    val MeterRed = Color(0xFFEF4444)
    val Ceiling = Color(0xFFFF3B5C)
    val LimiterChip = Color(0xFFE5E7EB)
}

/** "+1.5", "−1.0": one decimal, with a real minus sign (and a plus when [signed]). */
internal fun formatDb(db: Float, signed: Boolean = false): String {
    val text = String.format(Locale.ROOT, "%.1f", db).replace('-', '−')
    return if (signed && db > 0f) "+$text" else text
}

private const val ActiveMs = 450
