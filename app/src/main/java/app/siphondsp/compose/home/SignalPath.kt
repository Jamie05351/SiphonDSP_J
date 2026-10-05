package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import app.siphondsp.R
import app.siphondsp.compose.theme.BmwTheme

/**
 * The signal chain's wiring, drawn behind the power node, the tiles and the output node: the
 * processing route, a line from the power node through the tiles to OUT, and the bypass route,
 * which leaves the power node upwards, runs along the top of the panel with its label on it, and
 * drops into OUT.
 *
 * Both routes are always drawn and cross-fade by [globalActive]: at 1 the processing line is lit
 * purple with a glow and the bypass route is gone; at 0 the processing line is a grey dashed
 * line (still there, but not carrying audio) and the bypass route is white.
 *
 * The geometry matches [HomeScreen]'s chain row: the power and OUT columns are [ColumnWidth]
 * wide, [ChainInset] in from the panel's sides, and the line runs [ChainTop] + [NodeCentreY] down.
 */
@Composable
fun SignalPath(globalActive: Float, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    val g = globalActive
    val signal = BmwTheme.colors.sliderHeadroom
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            fun u(v: Float) = v * s.k * density
            val powerX = u(ChainInset + ColumnWidth / 2)
            val outX = size.width - powerX
            val lineY = u(ChainTop + NodeCentreY)
            val from = Offset(powerX + u(NodeSize / 2), lineY)
            val to = Offset(outX - u(OutSize / 2), lineY)

            // Processing route.
            drawLine(signal.copy(alpha = 0.25f * g), from, to, u(10f), StrokeCap.Round)
            drawLine(signal.copy(alpha = g), from, to, u(3f))
            drawLine(
                HomePalette.Idle.copy(alpha = 1 - g), from, to, u(2f),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(u(8f), u(8f))),
            )

            // Bypass route, with rounded corners and an arrowhead into OUT.
            val routeY = u(BypassY)
            val r = u(20f)
            val bypass = Path().apply {
                moveTo(powerX, lineY - u(NodeSize / 2))
                lineTo(powerX, routeY + r)
                quadraticTo(powerX, routeY, powerX + r, routeY)
                lineTo(outX - r, routeY)
                quadraticTo(outX, routeY, outX, routeY + r)
                lineTo(outX, lineY - u(OutSize / 2) - u(4f))
            }
            val white = Color.White.copy(alpha = 0.85f * (1 - g))
            drawPath(bypass, white, style = Stroke(u(2.5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val tip = Offset(outX, lineY - u(OutSize / 2) - u(2f))
            val head = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - u(7f), tip.y - u(10f))
                lineTo(tip.x + u(7f), tip.y - u(10f))
                close()
            }
            drawPath(head, white)
        }
        BypassLabel(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = s.dp(BypassY - 14f))
                .alpha(1 - g),
        )
    }
}

/** The bypass route's label, a dark pill sitting on the route. */
@Composable
private fun BypassLabel(modifier: Modifier) {
    val s = LocalHomeScale.current
    Text(
        text = stringResource(R.string.home_bypass_path),
        color = Color.White.copy(alpha = 0.9f),
        fontSize = s.sp(12f),
        fontWeight = FontWeight.Bold,
        style = TextStyle(letterSpacing = 0.1.em),
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .background(HomePalette.Page, CircleShape)
            .border(s.dp(1.5f), Color.White.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = s.dp(14f), vertical = s.dp(7f)),
    )
}

/**
 * The chain's end: OUT to the amp. Ringed in the signal purple while powered, white while
 * bypassed (the bypass route lands here). Same column geometry as [PowerNode].
 */
@Composable
fun OutputNode(globalActive: Float, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    Column(modifier.width(s.dp(ColumnWidth)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .padding(top = s.dp(NodeCentreY - OutSize / 2))
                .size(s.dp(OutSize))
                .background(HomePalette.OutFill, CircleShape)
                .border(s.dp(3f), lerp(Color.White.copy(alpha = 0.85f), signal, globalActive), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.home_out), color = Color.White, fontSize = s.sp(18f), fontWeight = FontWeight.Bold, maxLines = 1)
                Text(stringResource(R.string.home_out_channels), color = HomePalette.Caption, fontSize = s.sp(12f), fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        Text(
            text = stringResource(R.string.home_to_amp),
            color = HomePalette.Muted,
            fontSize = s.sp(13f),
            maxLines = 1,
            modifier = Modifier.padding(top = s.dp(12f)),
        )
    }
}

/** The chain row's geometry in design units, shared by [HomeScreen] and the path. */
internal const val ChainInset = 13f
internal const val ChainTop = 58f
internal const val OutSize = 110f
private const val BypassY = 26f
