package app.siphondsp.compose.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import app.siphondsp.R
import app.siphondsp.compose.controls.FaciaColour
import app.siphondsp.compose.controls.SidebarFacia
import app.siphondsp.compose.controls.SidebarLayout
import app.siphondsp.compose.controls.SidebarScreenCorner
import app.siphondsp.compose.controls.SidebarSignal
import app.siphondsp.compose.controls.cardStyle
import app.siphondsp.compose.controls.contentWindowRect
import app.siphondsp.compose.controls.drawChainScreen
import app.siphondsp.compose.controls.drawContentWindow
import app.siphondsp.compose.controls.drawRailTile
import app.siphondsp.compose.controls.drawSignalLine
import app.siphondsp.compose.controls.measureTileLabel
import app.siphondsp.compose.controls.stubEnds
import app.siphondsp.compose.controls.tileLabelRes
import app.siphondsp.view.DspDestination

/**
 * The front page turning into the workspace when a tile is tapped (Figma "SiphonDSP · Faceplate
 * Sidebar", Transition 1-4), drawn over the whole front page. [progress] runs 0..1:
 * - the facia fades up over the front page in the first [FadeIn], so the top screen, the buttons,
 *   power and OUT fade away while the tiles are still where they were;
 * - the tiles fold from the row into the sidebar column, each [Stagger] after the one before
 *   (PEQ first), with the purple line between them swinging from across to down and the power
 *   and OUT lines turning into the stubs; the chain screen shrinks with them, always wrapping them;
 * - the content window fades in at the end.
 *
 * At 1 it is exactly the workspace's first frame ([SidebarLayout], [contentWindowRect] and
 * [drawRailTile], the same code the workspace draws with), so the DSP screen can open over it with
 * no animation. [tiles] and [chain] are the front page's tiles and chain screen, and [selected]
 * the tapped tile, in this composable's coordinates; [sidebarWidth] is the workspace's column.
 */
@Composable
internal fun SidebarMorph(
    progress: () -> Float,
    selected: Int,
    tiles: List<Rect>,
    chain: Rect,
    sidebarWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val destinations = DspDestination.entries.filter { it.showInPrimaryNav }
    val styles = destinations.map { it.cardStyle() }
    val art = styles.map { rememberVectorPainter(it.third) }
    val labels = destinations.map { stringResource(it.tileLabelRes()) }
    val measurer = rememberTextMeasurer()
    val texture = ImageBitmap.imageResource(R.drawable.dsp_workspace_bg)
    val signal = SidebarSignal
    val count = minOf(destinations.size, tiles.size)

    Spacer(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = (progress() / FadeIn).coerceIn(0f, 1f) }
            .drawWithCache {
                val facia = SidebarFacia.toPx()
                val layout = SidebarLayout(sidebarWidth.toPx(), size.height, facia, count)
                val rail = List(count) { layout.tileRect(it) }
                val text = labels.map { measureTileLabel(measurer, it, layout.tile, this) }
                val window = contentWindowRect(size, sidebarWidth.toPx(), facia)
                val corner = SidebarScreenCorner.toPx()
                val pad = 12f * density
                onDrawBehind {
                    val p = progress()
                    val k = List(count) { i -> FastOutSlowInEasing.transform(((p - Stagger * i) / (1f - Stagger * (count - 1))).coerceIn(0f, 1f)) }
                    val at = List(count) { i -> lerp(tiles[i], rail[i], k[i]) }

                    // The chain screen heads for the sidebar screen but always wraps the tiles.
                    val s = lerp(chain, layout.screen, FastOutSlowInEasing.transform(p))
                    val screen = Rect(
                        maxOf(facia, minOf(s.left, at.minOf { it.left } - pad)),
                        maxOf(facia, minOf(s.top, at.minOf { it.top } - pad)),
                        maxOf(s.right, at.maxOf { it.right } + pad),
                        minOf(size.height - facia, maxOf(s.bottom, at.maxOf { it.bottom } + pad)),
                    )

                    drawRect(FaciaColour)
                    drawContentWindow(window, texture, alpha = ((p - WindowStart) / (1f - WindowStart)).coerceIn(0f, 1f))
                    drawChainScreen(screen, corner, signal)

                    // Each line leaves a tile's right-middle (then bottom-middle) and enters the next
                    // one's left-middle (then top-middle), so the lines swing round as the tiles fold.
                    /** Where the line leaves tile [i]: its right-middle, swinging to its bottom-middle. */
                    fun outPort(i: Int) = lerp(at[i].centerRight, at[i].bottomCenter, k[i])
                    /** Where the line enters tile [i]: its left-middle, swinging to its top-middle. */
                    fun inPort(i: Int) = lerp(at[i].centerLeft, at[i].topCenter, k[i])
                    val (stubTop, stubBottom) = stubEnds(screen)
                    val first = lerp(Offset(screen.left + pad, at[0].center.y), stubTop, k[0])
                    val last = lerp(Offset(screen.right - pad, at[count - 1].center.y), stubBottom, k[count - 1])
                    drawSignalLine(first, inPort(0), signal, stub = k[0])
                    for (i in 0 until count - 1) drawSignalLine(outPort(i), inPort(i + 1), signal)
                    drawSignalLine(outPort(count - 1), last, signal, stub = k[count - 1])

                    for (i in 0 until count) {
                        val (accent, accent2, _) = styles[i]
                        val r = at[i]
                        drawRailTile(
                            r, accent, accent2,
                            lit = if (i == selected) 1f else 0f,
                            art = art[i],
                            label = text[i],
                            labelScale = minOf(r.width, r.height) / layout.tile,
                        )
                    }
                }
            },
    )
}

/** How long the morph takes, from the end of the tile's flash to the DSP screen opening. */
internal const val SidebarMorphMs = 300L

// Fractions of the morph: the facia's fade over the front page, each tile's start after the one
// before, and where the content window starts to fade in.
private const val FadeIn = 0.15f
private const val Stagger = 0.04f
private const val WindowStart = 0.55f
