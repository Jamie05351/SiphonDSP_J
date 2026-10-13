package app.siphondsp.compose.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The front-page tiles' artwork, drawn live with a small loop of its own: the same pictures as the
 * static vectors in `compose.assets` (which the sidebar rail still uses), in their 230 x 150
 * viewport, each moved by a phase that runs 0..1 over [loopMs] and repeats. Every movement is a
 * whole number of cycles per loop, so the loop has no seam.
 *
 * - [PEQ]: the four bands' peaks rise and fall out of step, their nodes riding the peaks.
 * - [DELAY]: the arrow's dashes march from the impulse to its echo, which swells as each one lands.
 * - [XOVERS]: the split point drifts and the high and low branches open and close.
 * - [COMPRESSOR]: the loud input and the held-down output both flow left to right.
 * - [OUTPUT]: the two LED rows bounce like a live level meter.
 */
enum class TileArt(val loopMs: Int) {
    PEQ(4800) {
        override fun DrawScope.draw(t: Float, paths: Array<Path>) {
            drawPeqGrid()
            for ((i, band) in PeqBands.withIndex()) {
                val swing = 0.72f + 0.28f * sin(TwoPi * (t + band.offset))
                val peakY = Baseline + (band.peakY - Baseline) * swing
                val path = paths[i].apply {
                    reset()
                    moveTo(3f, Baseline)
                    lineTo(band.x - band.half, Baseline)
                    val shoulder = peakY + (Baseline - peakY) * 0.42f
                    cubicTo(band.x - band.half * 0.38f, Baseline, band.x - band.half * 0.22f, shoulder, band.x, peakY)
                    cubicTo(band.x + band.half * 0.22f, shoulder, band.x + band.half * 0.38f, Baseline, band.x + band.half, Baseline)
                    lineTo(227f, Baseline)
                }
                drawPath(path, band.colour, style = CurveStroke)
            }
            for ((i, band) in PeqBands.withIndex()) {
                val swing = 0.72f + 0.28f * sin(TwoPi * (t + band.offset))
                val node = Offset(band.x, Baseline + (band.peakY - Baseline) * swing)
                drawCircle(NodeRing, 6.8f, node)
                drawCircle(PeqBands[i].colour, 4.3f, node)
            }
        }
    },
    DELAY(2400) {
        override fun DrawScope.draw(t: Float, paths: Array<Path>) {
            drawLine(DelayFloor.copy(alpha = 0.7f), Offset(7f, 135f), Offset(223f, 135f), 1.8f, StrokeCap.Round)
            // The impulse, as drawn.
            drawPath(DelayImpulse, DelayWhite, style = CurveStroke)
            // Its echo swells as the travelling dash reaches it, then settles.
            val landed = ((t + 0.08f) % 1f)
            val swell = 0.62f + 0.38f * exp(-landed * 5f)
            withTransform({ scale(1f, swell, Offset(172f, 135f)) }) {
                drawPath(DelayEcho, DelayGreen, style = CurveStroke)
            }
            // The arrow's dashes march towards the echo.
            drawLine(
                DelayArrow.copy(alpha = 0.6f), Offset(55f, 31f), Offset(159f, 31f), 1.7f, StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 6f), -t * 22f),
            )
            val head = paths[0].apply {
                reset()
                moveTo(153f, 27f)
                lineTo(160f, 31f)
                lineTo(153f, 35f)
            }
            drawPath(head, DelayArrow, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    },
    XOVERS(5000) {
        override fun DrawScope.draw(t: Float, paths: Array<Path>) {
            val split = 69f + 9f * sin(TwoPi * t)
            val open = 0.82f + 0.18f * cos(TwoPi * 2 * t)
            val high = Baseline - 50f * open
            val low = Baseline + 51f * open
            drawLine(XoverInput, Offset(7f, Baseline), Offset(split, Baseline), XoverWidth, StrokeCap.Round)
            for ((i, y) in listOf(high, Baseline, low).withIndex()) {
                val path = paths[i].apply {
                    reset()
                    moveTo(split, Baseline)
                    cubicTo(split + 35f, Baseline, split + 36f, y, split + 71f, y)
                    lineTo(223f, y)
                }
                drawPath(path, XoverColours[i], style = Stroke(XoverWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    },
    COMPRESSOR(1800) {
        override fun DrawScope.draw(t: Float, paths: Array<Path>) {
            // The threshold, above and below.
            for (y in floatArrayOf(39f, 111f)) {
                drawLine(
                    CompThreshold.copy(alpha = 0.6f), Offset(115f, y), Offset(219f, y), 1.7f, StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 6f), 0f),
                )
            }
            // The input: a burst whose waves travel through its envelope. One period a loop.
            val input = paths[0].apply {
                reset()
                var x = 6f
                while (x <= Join) {
                    val u = (x - 6f) / 35f
                    val envelope = 58f * u * u * exp(2f * (1f - u)) * (1f - smoothstep(Join - 14f, Join, x))
                    val y = Baseline - envelope * sin(TwoPi * ((x - 6f) / 27.75f - t))
                    if (x == 6f) moveTo(x, y) else lineTo(x, y)
                    x += 1f
                }
            }
            drawPath(input, CompInput, style = CurveStroke)
            // The output: held under the threshold, the same flow at its own pitch.
            val output = paths[1].apply {
                reset()
                var x = Join
                while (x <= 225f) {
                    val d = x - Join
                    val envelope = 11.65f * (1f - exp(-d / 4f)) * exp(-d / 130f)
                    val y = Baseline - envelope * sin(TwoPi * (d / 21.6f - 2 * t))
                    if (x == Join) moveTo(x, y) else lineTo(x, y)
                    x += 1f
                }
            }
            drawPath(output, CompOutput, style = CurveStroke)
        }
    },
    OUTPUT(6000) {
        override fun DrawScope.draw(t: Float, paths: Array<Path>) {
            for ((row, top) in floatArrayOf(42f, 86f).withIndex()) {
                val level = 0.66f +
                    0.14f * sin(TwoPi * (2 * t + row * 0.13f)) +
                    0.08f * sin(TwoPi * (5 * t + row * 0.31f)) +
                    0.05f * sin(TwoPi * (11 * t + row * 0.57f))
                val lit = (level * MeterSegments).roundToInt().coerceIn(1, MeterSegments)
                for (i in 0 until MeterSegments) {
                    val colour = when {
                        i >= lit -> MeterCyan.copy(alpha = 0.18f)
                        i >= MeterSegments - 3 -> MeterMagenta
                        else -> MeterCyan
                    }
                    drawRoundRect(colour, Offset(14f + i * 14.5f, top), Size(9f, 22f), CornerRadius(2.5f))
                }
            }
            drawLine(MeterCeiling, Offset(205f, 28f), Offset(205f, 124f), 4f, StrokeCap.Round)
        }
    };

    /** Draws the picture at phase [t] (0..1) in the 230 x 150 viewport; [paths] are scratch paths to reuse. */
    abstract fun DrawScope.draw(t: Float, paths: Array<Path>)
}

/**
 * [art] looping in a box, scaled to fit and centred like the static vectors were. The phase is
 * read only while drawing, so the loop redraws the box and recomposes nothing.
 */
@Composable
internal fun AnimatedTileArt(art: TileArt, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "tile art")
    val phase = loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(art.loopMs, easing = LinearEasing)),
        label = art.name,
    )
    Spacer(
        modifier.drawWithCache {
            val scale = min(size.width / ArtWidth, size.height / ArtHeight)
            val left = (size.width - ArtWidth * scale) / 2f
            val top = (size.height - ArtHeight * scale) / 2f
            val paths = Array(ScratchPaths) { Path() }
            onDrawBehind {
                translate(left, top) {
                    withTransform({ scale(scale, scale, Offset.Zero) }) {
                        with(art) { draw(phase.value, paths) }
                    }
                }
            }
        },
    )
}

private fun DrawScope.drawPeqGrid() {
    for (k in 0 until 10) {
        val x = 5f + k * 24f
        drawLine(PeqGrid.copy(alpha = 0.35f), Offset(x, 10f), Offset(x, 145f), 0.7f, StrokeCap.Round)
    }
    drawLine(PeqAxis.copy(alpha = 0.65f), Offset(3f, Baseline), Offset(227f, Baseline), 1.3f, StrokeCap.Round)
}

private fun smoothstep(from: Float, to: Float, x: Float): Float {
    val u = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/** One PEQ band: its centre, half-width and peak in the viewport, its colour and its phase offset. */
private class PeqBand(val x: Float, val half: Float, val peakY: Float, val colour: Color, val offset: Float)

private val PeqBands = listOf(
    PeqBand(39f, 36f, 47f, Color(0xFFCB48E0), 0f),
    PeqBand(85f, 40f, 25f, Color(0xFF28ADFF), 0.31f),
    PeqBand(130f, 42f, 126f, Color(0xFFFFBE20), 0.58f),
    PeqBand(189f, 40f, 47f, Color(0xFF3DEB87), 0.82f),
)

private val DelayImpulse = androidx.compose.ui.graphics.vector.PathParser()
    .parsePathString("M24,135 C29,135 30,133 32,95 C33,53 35,12 38,12 C41,12 42,88 44,119 C46,134 48,135 54,135")
    .toPath()
private val DelayEcho = androidx.compose.ui.graphics.vector.PathParser()
    .parsePathString("M159,135 C166,135 166,123 170,108 C173,90 174,94 177,112 C179,127 180,135 185,135")
    .toPath()

private const val ArtWidth = 230f
private const val ArtHeight = 150f
private const val Baseline = 75f
private const val Join = 117f
private const val TwoPi = (2 * PI).toFloat()
private const val ScratchPaths = 4
private const val MeterSegments = 13
private const val XoverWidth = 3.4f
private val CurveStroke = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private val PeqGrid = Color(0xFF263340)
private val PeqAxis = Color(0xFF678475)
private val NodeRing = Color(0xFFD5DFE8)
private val DelayFloor = Color(0xFF459875)
private val DelayWhite = Color(0xFFF3F7F5)
private val DelayGreen = Color(0xFF28D77B)
private val DelayArrow = Color(0xFFE0E8E4)
private val XoverInput = Color(0xFFF5F4E9)
private val XoverColours = listOf(Color(0xFF20A9FA), Color(0xFFF9B719), Color(0xFFFF751B))
private val CompThreshold = Color(0xFFCB2344)
private val CompInput = Color(0xFFF2EDF0)
private val CompOutput = Color(0xFFF12B4B)
private val MeterCyan = Color(0xFF00E5FF)
private val MeterMagenta = Color(0xFFF040CC)
private val MeterCeiling = Color(0xFFFF3B5C)
