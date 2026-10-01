package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The dark panel behind every response graph (PEQ, crossover, compressor): 50 % black over the
 * workspace texture, so the navy background doesn't compete with the curves. One definition so
 * all three graphs sit on the identical box.
 */
internal fun Modifier.graphPanel(): Modifier = background(GraphPanelColor, GraphPanelShape)

private val GraphPanelColor = Color.Black.copy(alpha = 0.5f)
private val GraphPanelShape = RoundedCornerShape(10.dp)

/**
 * Channel colours for response curves, shared by every graph: left neon purple (the app's meter
 * purple), right neon green (the lit-switch green). Solid lines, never dashed.
 */
internal val LeftChannelColor = android.graphics.Color.rgb(0xB1, 0x4D, 0xFF)
internal val RightChannelColor = android.graphics.Color.rgb(0x39, 0xFF, 0x14)
