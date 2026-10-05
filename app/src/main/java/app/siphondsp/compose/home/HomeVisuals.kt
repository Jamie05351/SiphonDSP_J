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
 * The front page is designed in dp at the head unit's own size, 1280 x 480 (Figma "v3 · Head unit
 * 1280×480"), so [k] is 1 there. Anything smaller, such as a phone in landscape, scales the whole
 * design down by [k] to fit its width or height, and [HomeScreen] shares out any spare height
 * between the top screen and the signal chain.
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
    val Ceiling = Color(0xFFFF3B5C)
    val LimiterChip = Color(0xFFE5E7EB)
    val Cell = Color(0xFF111216)
    val CellLabel = Color(0xFFC9CCD1)
    val Hollow = Color(0xFF5A5F6A)
    val ButtonTop = Color(0xFF2E3036)
    val ButtonBottom = Color(0xFF1A1B1F)
    val ButtonEdge = Color(0xFF3A3D45)
    val MeterHot = Color(0xFFF040CC)
    val MeterPeak = Color(0xFFF3E8FF)
    val HeadroomGood = Color(0xFF22C55E)
    val HeadroomLow = Color(0xFFEAB308)
    val HeadroomCritical = Color(0xFFEF4444)
}

/** The design's size in dp; see [HomeScale]. */
internal const val DesignWidth = 1280f
internal const val DesignHeight = 480f

/** "+1.5", "−1.0": one decimal, with a real minus sign (and a plus when [signed]). */
internal fun formatDb(db: Float, signed: Boolean = false): String {
    val text = String.format(Locale.ROOT, "%.1f", db).replace('-', '−')
    return if (signed && db > 0f) "+$text" else text
}

private const val ActiveMs = 450
