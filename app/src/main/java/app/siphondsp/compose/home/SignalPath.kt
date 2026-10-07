package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.siphondsp.R
import app.siphondsp.compose.theme.BmwTheme

/**
 * The power-on sweep's timeline, over [SweepOnMs] (3 s). [progress] runs 0..1 over the whole
 * sequence; each piece of line and each tile gets its own 0..1 from it:
 * - 0.0–0.3 s: the power button lights and the spark leaves it.
 * - 0.3–1.1 s: the five lines into the tiles (power → first tile, and the four gaps) each carry
 *   their own spark at once, in sync.
 * - 1.1–2.3 s: each spark splits round both sides of its tile's border, so all five light together.
 * - 2.3–2.8 s: the last tile's spark follows the last line to OUT.
 * - 2.8–3.0 s: OUT lights.
 * Powering off runs the same timeline backwards, faster.
 */
internal object ChainSweep {
    private val tiles = HomeStage.entries.size
    private const val LinesStart = 0.3f / 3f
    private const val TilesStart = 1.1f / 3f
    private const val LastLineStart = 2.3f / 3f
    private const val OutStart = 2.8f / 3f

    private fun span(progress: Float, from: Float, to: Float) = ((progress - from) / (to - from)).coerceIn(0f, 1f)

    /** The stretch of line before tile [i] (or, at [i] = 5, from the last tile to OUT). */
    fun line(i: Int, progress: Float) =
        if (i < tiles) span(progress, LinesStart, TilesStart) else span(progress, LastLineStart, OutStart)

    /** Every tile's border: they all light together. */
    fun tiles(progress: Float) = span(progress, TilesStart, LastLineStart)

    /** How lit OUT is: it lights once the last line's spark reaches it. */
    fun arrived(progress: Float) = span(progress, OutStart, 1f)
}

/**
 * Where the chain's pieces sit across the chain screen, in design units at the chain's own scale
 * (see [ChainScale]) for a screen [width] wide: the power button [ChainPad] in from the left edge,
 * OUT [ChainPad] in from the right, and the five tiles between them with the six stretches of line
 * all the same length ([gap]).
 */
internal class ChainLayout(val width: Float) {
    private val tiles = HomeStage.entries.size
    val gap = (width - 2 * ChainPad - NodeSize - OutSize - tiles * CardWidth) / (tiles + 1)
    val powerX = ChainPad + NodeSize / 2
    val outX = width - ChainPad - OutSize / 2
    fun tileLeft(i: Int) = ChainPad + NodeSize + gap + i * (CardWidth + gap)
    fun tileRight(i: Int) = tileLeft(i) + CardWidth

    /** The stretch of line before tile [i] (or, at [i] = 5, from the last tile to OUT). */
    fun lineStart(i: Int) = if (i == 0) ChainPad + NodeSize else tileRight(i - 1)
    fun lineEnd(i: Int) = if (i == tiles) width - ChainPad - OutSize else tileLeft(i)
}

/** How far the power button and OUT sit in from the chain screen's sides, in chain units. */
internal const val ChainPad = 39f

/**
 * The spark at the front of the sweep, in the power button's purple: a soft glow round a bright
 * core. [scale] is the design scale in px per design unit.
 */
internal fun DrawScope.drawSpark(at: Offset, glow: Color, scale: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to glow,
            0.3f to glow.copy(alpha = 0.6f),
            1f to Color.Transparent,
            center = at,
            radius = 22f * scale,
        ),
        radius = 22f * scale,
        center = at,
    )
    drawCircle(SparkCore, 5f * scale, at)
}

/**
 * The glow round the power button and OUT while lit: [colour] fading out from the node's rim over
 * [spread] px, at [t] (0..1) strength. Shared so the two nodes glow exactly alike.
 */
internal fun DrawScope.drawNodeGlow(colour: Color, t: Float, spread: Float) {
    if (t <= 0f) return
    val r = size.minDimension / 2
    val halo = r + spread
    drawCircle(
        Brush.radialGradient(
            r / halo to colour.copy(alpha = 0.5f * t),
            1f to Color.Transparent,
            center = center,
            radius = halo,
        ),
        radius = halo,
    )
}

/** How far the nodes' glow reaches past their rim, in design units. */
internal const val NodeGlow = 18f

/** The spark's white-hot centre, from the power glyph's lit colour. */
private val SparkCore = Color(0xFFF3E8FF)

/**
 * The signal chain's wiring, drawn behind the power node, the tiles and the output node: the
 * processing route, a line from the power node to OUT across the middle of the panel (it shows in
 * the gaps between the tiles), and the bypass route, which leaves the power node upwards, runs
 * along the panel's top edge over the tiles and drops into OUT.
 *
 * The processing line is always there as a grey dashed line; [sweep] (see [ChainSweep]) lights it
 * purple stretch by stretch, from the power node towards OUT, as the DSP powers on, with a spark
 * ([drawSpark]) at the front while [spark] (powering on, not off). The bypass route fades with
 * [globalActive]: white at 0, gone at 1.
 *
 * The geometry is [layout], the same [ChainLayout] the chain screen places its pieces by.
 */
@Composable
internal fun SignalPath(globalActive: Float, sweep: () -> Float, spark: Boolean, layout: ChainLayout, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    val g = globalActive
    val signal = BmwTheme.colors.sliderHeadroom
    Canvas(modifier.fillMaxSize()) {
        val p = sweep()
        fun u(v: Float) = v * s.k * density
        val powerX = u(layout.powerX)
        val outX = u(layout.outX)
        val lineY = size.height / 2
        val from = Offset(powerX + u(NodeSize / 2), lineY)
        val to = Offset(outX - u(OutSize / 2), lineY)

        // Processing route: the dashed line, lit stretch by stretch.
        drawLine(
            HomePalette.Idle, from, to, u(2f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(u(8f), u(8f))),
        )
        for (i in 0..HomeStage.entries.size) {
            val start = u(layout.lineStart(i))
            val end = u(layout.lineEnd(i))
            val lit = ChainSweep.line(i, p)
            if (lit <= 0f) continue
            val a = Offset(start, lineY)
            val b = Offset(start + (end - start) * lit, lineY)
            drawLine(signal.copy(alpha = 0.25f), a, b, u(10f), StrokeCap.Round)
            drawLine(signal, a, b, u(3f))
            if (spark && lit < 1f) drawSpark(b, signal, s.k * density)
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
 * The chain's end: OUT to the amp, centred in its column's height on the chain's line. Ringed
 * white while bypassed (the bypass route lands here); [lit] (0..1, [ChainSweep.arrived]) turns the
 * ring the power button's purple, with the same glow, as the power-on spark reaches it. Read while
 * drawing, so the sweep redraws without recomposing.
 */
@Composable
fun OutputNode(lit: () -> Float, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    Box(modifier.width(s.dp(OutColumnWidth)).fillMaxHeight()) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(s.dp(OutSize))
                .drawBehind {
                    val t = lit()
                    val r = size.minDimension / 2
                    // The glow, as round the power button.
                    drawNodeGlow(signal, t, s.dp(NodeGlow).toPx())
                    drawCircle(HomePalette.OutFill, r)
                    val rim = s.dp(3f + t).toPx()
                    drawCircle(lerp(Color.White.copy(alpha = 0.85f), signal, t), r - rim / 2, style = Stroke(rim))
                },
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
