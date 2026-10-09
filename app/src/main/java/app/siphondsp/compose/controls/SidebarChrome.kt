package app.siphondsp.compose.controls

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * The workspace chrome, drawn to match the front page (Figma "SiphonDSP · Faceplate Sidebar"):
 * the whole screen is the front page's facia ([FaciaColour]), with two windows cut into it, the
 * sidebar screen on the left and the content window on the right, [SidebarFacia] apart and in from
 * the screen edges. The sidebar screen is the front page's chain screen turned on its side: black
 * glass, a grey hairline, a purple ring with a glow inwards, and the five tiles joined top to
 * bottom by the purple signal line. Power and OUT are left out; their lines fade out as the
 * front page's morph lands (the stubs are drawn at [StubAlpha], now 0).
 *
 * The values are James's, from the sidebar tuner page (2026-10-09).
 *
 * Shared by [DspSidebarRail] and [DspWorkspaceBackdrop], and by the front page's tile-to-sidebar
 * morph, whose last frame has to be exactly the workspace's first.
 */
internal val FaciaColour = Color(0xFF1B1D23) // also the front page's background (HomePalette.Page)

/** The sidebar's purple: the ring, the glow and the signal line. */
internal val SidebarSignal = Color(0xFFA42EFF)

/** Facia between the screen edges and the windows, and between the two windows. */
internal val SidebarFacia = 8.dp
internal val SidebarScreenCorner = 10.dp
internal val ContentWindowCorner = 10.dp

/**
 * Every edge in the sidebar (the screen's hairline, the purple ring, the tile borders and the
 * signal line) is drawn at this fraction of the front page's width, so they thin out together.
 */
internal const val EdgeScale = 0.35f

private val ScreenFill = Color(0xFF050506)
private val ScreenEdge = Color(0xFF2A2C31)
private val TileBase = Color(0xFF0A0A0D)
private const val GlowBands = 20
private const val StubAlpha = 0f
private const val RingAlpha = 1f
private const val GlowAlpha = 0.14f

// Tile spacing as fractions of the tile: the gap between tiles and the space at each end.
private const val GapRatio = 0.12f
private const val EndRatio = 0.14f

/**
 * Where the sidebar's pieces sit, in px, for a column [columnWidth] wide and [height] tall with
 * [facia] round the screen: the screen, then [count] square tiles centred down it, evenly spaced.
 */
internal class SidebarLayout(columnWidth: Float, height: Float, facia: Float, private val count: Int) {
    val screen = Rect(facia, facia, columnWidth - facia, height - facia)
    val tile: Float = min(screen.height / (count + (count - 1) * GapRatio + 2 * EndRatio), screen.width * 0.95f)
    private val gap = tile * GapRatio
    private val first = screen.top + (screen.height - count * tile - (count - 1) * gap) / 2

    fun tileRect(i: Int): Rect {
        val left = screen.center.x - tile / 2
        val top = first + i * (tile + gap)
        return Rect(left, top, left + tile, top + tile)
    }
}

/** The content window: from the sidebar column's edge to [facia] in from the other three edges. */
internal fun contentWindowRect(size: Size, columnWidth: Float, facia: Float) =
    Rect(columnWidth, facia, size.width - facia, size.height - facia)

/**
 * The chain screen in [rect]: black glass with the grey hairline, a purple ring just inside it and
 * the purple glow fading inwards from the ring (the front page's chain screen while the DSP is on).
 */
internal fun DrawScope.drawChainScreen(rect: Rect, corner: Float, signal: Color) {
    val edge = 2.dp.toPx() * EdgeScale
    val band = 1.4.dp.toPx()
    translate(rect.left, rect.top) {
        val w = rect.width
        val h = rect.height
        drawRoundRect(ScreenFill, size = Size(w, h), cornerRadius = CornerRadius(corner))
        drawRoundRect(
            ScreenEdge,
            topLeft = Offset(edge / 2, edge / 2),
            size = Size(w - edge, h - edge),
            cornerRadius = CornerRadius(corner - edge / 2),
            style = Stroke(edge),
        )
        for (step in 0 until GlowBands) {
            val inset = 2 * edge + band * (step + 0.5f)
            val fade = 1 - (step + 1f) / GlowBands
            drawRoundRect(
                signal.copy(alpha = GlowAlpha * fade * fade),
                topLeft = Offset(inset, inset),
                size = Size(w - 2 * inset, h - 2 * inset),
                cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)),
                style = Stroke(band),
            )
        }
        val inset = edge * 1.5f
        drawRoundRect(
            signal.copy(alpha = RingAlpha),
            topLeft = Offset(inset, inset),
            size = Size(w - 2 * inset, h - 2 * inset),
            cornerRadius = CornerRadius(corner - inset),
            style = Stroke(edge),
        )
    }
}

/**
 * One stretch of the purple signal line, with its soft glow; [stub] for the short ends where power
 * and OUT used to be, which are fainter.
 */
internal fun DrawScope.drawSignalLine(from: Offset, to: Offset, signal: Color, stub: Float = 0f) {
    val alpha = 1f - (1f - StubAlpha) * stub
    if (alpha <= 0f) return
    val core = 2.dp.toPx() * EdgeScale
    drawLine(signal.copy(alpha = 0.4f * alpha), from, to, core * 4f, StrokeCap.Round)
    drawLine(signal.copy(alpha = alpha), from, to, core, StrokeCap.Round)
}

/** Where the top and bottom stubs end: the screen's top and bottom edge at its middle. */
internal fun DrawScope.stubEnds(screen: Rect): Pair<Offset, Offset> {
    val inset = 0f
    return Offset(screen.center.x, screen.top + inset) to Offset(screen.center.x, screen.bottom - inset)
}

/** The whole sidebar screen for [layout]: the glass and the signal line from top to bottom. */
internal fun DrawScope.drawSidebarScreen(layout: SidebarLayout, count: Int, signal: Color) {
    drawChainScreen(layout.screen, SidebarScreenCorner.toPx(), signal)
    val (top, bottom) = stubEnds(layout.screen)
    drawSignalLine(top, layout.tileRect(0).topCenter, signal, stub = 1f)
    for (i in 0 until count - 1) drawSignalLine(layout.tileRect(i).bottomCenter, layout.tileRect(i + 1).topCenter, signal)
    drawSignalLine(layout.tileRect(count - 1).bottomCenter, bottom, signal, stub = 1f)
}

/**
 * A tile's short label, laid out for a tile [tileWidth] px wide: 15 % of the tile, shrunk (not
 * below 9 sp) if it would be wider than 90 % of it.
 */
internal fun measureTileLabel(measurer: TextMeasurer, label: String, tileWidth: Float, density: Density): TextLayoutResult =
    with(density) {
        val base = (tileWidth * 0.15f).toSp().value
        val width = measurer.measure(label, TextStyle(fontSize = base.sp, fontWeight = FontWeight.Medium)).size.width
        val room = tileWidth * 0.90f
        val fontSize = if (width > room) max(base * room / width, LabelMinSp) else base
        measurer.measure(label, TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Medium), maxLines = 1, softWrap = false)
    }

private const val LabelMinSp = 9f

/**
 * One sidebar tile in [rect], drawn like the front page's tiles: dark glass washed with [accent]
 * from the top, a border running from [accent] to [accent2], the module's [art] across the top
 * and its [label] under it, [labelScale] times the size it was laid out at. [lit] (the screen you
 * are on) brightens the wash and the label and adds a halo.
 */
internal fun DrawScope.drawRailTile(
    rect: Rect,
    accent: Color,
    accent2: Color,
    lit: Float,
    art: VectorPainter,
    label: TextLayoutResult,
    labelScale: Float = 1f,
) {
    val w = rect.width
    val h = rect.height
    val m = min(w, h)
    val corner = m * 0.15f
    translate(rect.left, rect.top) {
        if (lit > 0.01f) {
            for (i in 5 downTo 1) {
                val g = m * 0.018f * i
                drawRoundRect(
                    accent.copy(alpha = 0.05f * lit * (6 - i)),
                    topLeft = Offset(-g, -g),
                    size = Size(w + g * 2, h + g * 2),
                    cornerRadius = CornerRadius(corner + g),
                    style = Stroke(m * 0.03f),
                )
            }
        }
        val box = Size(w, h)
        drawRoundRect(TileBase, size = box, cornerRadius = CornerRadius(corner))
        drawRoundRect(
            Brush.verticalGradient(
                0f to accent.copy(alpha = 0.35f + 0.20f * lit),
                0.45f to accent.copy(alpha = 0.20f + 0.09f * lit),
                1f to accent.copy(alpha = 0.15f),
                endY = h,
            ),
            size = box,
            cornerRadius = CornerRadius(corner),
        )
        val border = m * 0.055f * EdgeScale
        drawRoundRect(
            Brush.linearGradient(listOf(accent, accent2), start = Offset.Zero, end = Offset(w, h)),
            topLeft = Offset(border / 2, border / 2),
            size = Size(w - border, h - border),
            cornerRadius = CornerRadius(corner - border / 2),
            style = Stroke(border),
        )
        translate(w * 0.05f, h * 0.15f) {
            with(art) { draw(Size(w * 0.90f, min(w * 0.65f, h * 0.5f))) }
        }
        val lw = label.size.width * labelScale
        translate((w - lw) / 2, h * 0.77f) {
            scale(labelScale, pivot = Offset.Zero) {
                drawText(label, color = lerp(DspColors.Label, Color.White, lit))
            }
        }
    }
}
