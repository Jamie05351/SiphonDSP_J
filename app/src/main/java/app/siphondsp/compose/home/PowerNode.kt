package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import app.siphondsp.R
import app.siphondsp.compose.theme.BmwTheme

/**
 * The DSP power button at the start of the signal chain, with its state and hint under it. Lit
 * purple and glowing while [powered], a dark grey button while bypassed, blending by
 * [globalActive]. Its centre sits [NodeCentreY] design units below the column's top, so the chain's
 * line meets it level with the tiles' centres.
 */
@Composable
fun PowerNode(
    powered: Boolean,
    globalActive: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalHomeScale.current
    val g = globalActive
    val signal = BmwTheme.colors.sliderHeadroom
    val description = stringResource(R.string.home_power_description)
    Column(modifier.width(s.dp(ColumnWidth)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .padding(top = s.dp(NodeCentreY - NodeSize / 2))
                .size(s.dp(NodeSize))
                .drawBehind {
                    // The glow: a soft purple halo past the rim, gone while bypassed.
                    val r = size.minDimension / 2
                    val halo = r + s.dp(36f).toPx()
                    drawCircle(
                        Brush.radialGradient(
                            0.7f to signal.copy(alpha = 0.65f * g),
                            1f to Color.Transparent,
                            center = center,
                            radius = halo,
                        ),
                        radius = halo,
                    )
                }
                .clip(CircleShape)
                .toggleable(value = powered, role = Role.Switch, onValueChange = { onToggle() })
                .semantics { contentDescription = description },
        ) {
            Canvas(Modifier.size(s.dp(NodeSize))) {
                val r = size.minDimension / 2
                val rim = s.dp(3f + g).toPx()
                drawCircle(HomePalette.NodeFill, r)
                drawCircle(lerp(HomePalette.NodeEdgeOff, signal, g), r - rim / 2, style = Stroke(rim))
                // The power glyph: an open ring with a bar through its gap.
                val glyph = lerp(HomePalette.Caption, Color(0xFFE9D5FF), g)
                val stroke = s.dp(6f).toPx()
                val gr = s.dp(24f).toPx()
                val c = center + Offset(0f, s.dp(3f).toPx())
                drawArc(
                    glyph, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                    topLeft = c - Offset(gr, gr), size = Size(gr * 2, gr * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(glyph, c - Offset(0f, gr + s.dp(5f).toPx()), c - Offset(0f, s.dp(4f).toPx()), stroke, StrokeCap.Round)
            }
        }
        Text(
            text = stringResource(if (powered) R.string.home_power_on else R.string.home_power_off),
            color = lerp(HomePalette.Caption, Color(0xFFC084FC), g),
            fontSize = s.sp(18f),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = TextStyle(letterSpacing = 0.1.em),
            maxLines = 1,
            modifier = Modifier.padding(top = s.dp(22f)),
        )
        Text(
            text = stringResource(if (powered) R.string.home_power_on_hint else R.string.home_power_off_hint),
            color = HomePalette.Muted,
            fontSize = s.sp(13f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = s.dp(6f)),
        )
    }
}

/** The power column's width, and its node's size and centre, in design units. */
internal const val ColumnWidth = 200f
internal const val NodeSize = 150f
internal const val NodeCentreY = 165f
