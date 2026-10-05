package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.siphondsp.R
import app.siphondsp.compose.theme.BmwTheme

/**
 * The power-on sweep's timeline: the signal travels from the power node along the line to the first
 * tile, around that tile's border, along the next stretch of line, and so on to OUT. [progress]
 * runs 0..1 over the whole chain; each piece of line and each tile gets its own 0..1 from it.
 * Lines are short, so they take [LineWeight] of a tile's time.
 */
internal object ChainSweep {
    private const val LineWeight = 0.25f
    private val tiles = HomeStage.entries.size
    private val total = (tiles + 1) * LineWeight + tiles

    /** Piece [k] of the chain in order (line, tile, line, ... line), lit 0..1 at [progress]. */
    private fun piece(k: Int, progress: Float): Float {
        val start = (k + 1) / 2 * LineWeight + k / 2 * 1f
        val weight = if (k % 2 == 0) LineWeight else 1f
        return ((progress * total - start) / weight).coerceIn(0f, 1f)
    }

    /** The stretch of line before tile [i] (or, at [i] = 5, from the last tile to OUT). */
    fun line(i: Int, progress: Float) = piece(2 * i, progress)

    /** Tile [i]'s border. */
    fun tile(i: Int, progress: Float) = piece(2 * i + 1, progress)
}

/**
 * The signal chain's wiring, drawn behind the power node, the tiles and the output node: the
 * processing route, a line from the power node to OUT across the middle of the panel (it shows in
 * the gaps between the tiles), and the bypass route, which leaves the power node upwards, runs
 * along the panel's top edge over the tiles and drops into OUT.
 *
 * The processing line is always there as a grey dashed line; [sweep] (see [ChainSweep]) lights it
 * purple stretch by stretch, from the power node towards OUT, as the DSP powers on. The bypass
 * route fades with [globalActive]: white at 0, gone at 1.
 *
 * The geometry matches [HomeScreen]'s chain row: the power column ([ColumnWidth]) at the left and
 * the OUT column ([OutColumnWidth]) at the right, both centred on the line.
 */
@Composable
fun SignalPath(globalActive: Float, sweep: () -> Float, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    val g = globalActive
    val signal = BmwTheme.colors.sliderHeadroom
    Canvas(modifier.fillMaxSize()) {
        val p = sweep()
        fun u(v: Float) = v * s.k * density
        val powerX = u(ColumnWidth / 2)
        val outX = size.width - u(OutColumnWidth / 2)
        val lineY = size.height / 2
        val from = Offset(powerX + u(NodeSize / 2), lineY)
        val to = Offset(outX - u(OutSize / 2), lineY)

        // Processing route: the dashed line, lit over stretch by stretch. The stretches run between
        // the tiles, laid out as HomeScreen's chain row lays them out.
        drawLine(
            HomePalette.Idle, from, to, u(2f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(u(8f), u(8f))),
        )
        val tiles = HomeStage.entries.size
        val row = tiles * u(CardWidth) + (tiles - 1) * u(TileGap)
        val firstTile = u(ColumnWidth) + (size.width - u(ColumnWidth) - u(OutColumnWidth) - row) / 2
        for (i in 0..tiles) {
            val start = if (i == 0) from.x else firstTile + i * (u(CardWidth) + u(TileGap)) - u(TileGap)
            val end = if (i == tiles) to.x else firstTile + i * (u(CardWidth) + u(TileGap))
            val lit = ChainSweep.line(i, p)
            if (lit <= 0f) continue
            val a = Offset(start, lineY)
            val b = Offset(start + (end - start) * lit, lineY)
            drawLine(signal.copy(alpha = 0.25f), a, b, u(10f), StrokeCap.Round)
            drawLine(signal, a, b, u(3f))
        }

        // Bypass route, with rounded corners and an arrowhead into OUT.
        val routeY = u(BypassY)
        val r = u(10f)
        val outTop = lineY - u(OutSize / 2)
        val bypass = Path().apply {
            moveTo(powerX, lineY - u(NodeSize / 2))
            lineTo(powerX, routeY + r)
            quadraticTo(powerX, routeY, powerX + r, routeY)
            lineTo(outX - r, routeY)
            quadraticTo(outX, routeY, outX, routeY + r)
            lineTo(outX, outTop - u(4f))
        }
        val white = Color.White.copy(alpha = 0.85f * (1 - g))
        drawPath(bypass, white, style = Stroke(u(2.5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val tip = Offset(outX, outTop - u(1f))
        val head = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - u(6f), tip.y - u(9f))
            lineTo(tip.x + u(6f), tip.y - u(9f))
            close()
        }
        drawPath(head, white)
    }
}

/**
 * The chain's end: OUT to the amp, centred in its column's height on the chain's line. Ringed in
 * the signal purple while powered, white while bypassed (the bypass route lands here).
 */
@Composable
fun OutputNode(globalActive: Float, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    Box(modifier.width(s.dp(OutColumnWidth)).fillMaxHeight()) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(s.dp(OutSize))
                .background(HomePalette.OutFill, CircleShape)
                .border(s.dp(3f), lerp(Color.White.copy(alpha = 0.85f), signal, globalActive), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.home_out), color = Color.White, fontSize = s.sp(20f), fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(
            text = stringResource(R.string.home_to_amp),
            color = HomePalette.Muted,
            fontSize = s.sp(14f),
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center).offset(y = s.dp(OutSize / 2 + 20f)),
        )
    }
}

/** The OUT column's width and node size, and the bypass route's height, in design units. */
internal const val OutColumnWidth = 132f
internal const val OutSize = 84f
private const val BypassY = 7f
