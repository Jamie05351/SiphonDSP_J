package app.siphondsp.compose.controls

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import app.siphondsp.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The v4 head-unit workspace art (`dsp_workspace_backdrop_v4*.jpg`, 2800x1050 -- the 1280x480
 * head unit's exact aspect). Rects are fractions of the *image*, x/y/w/h, placed in the layout
 * placer (REW/_UI/workspace_layout_placer_v4.html) against that art.
 */
object WorkspaceArt {
    const val IMAGE_WIDTH = 2800f
    const val IMAGE_HEIGHT = 1050f

    class Frac(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Sidebar tile rows, top to bottom (PEQ, Gains, Xovers, Comp, Allpass): y and height. */
    val sidebarTiles = listOf(
        0.0343f to 0.1524f,
        0.2333f to 0.1553f,
        0.4305f to 0.1533f,
        0.6257f to 0.1467f,
        0.8143f to 0.1467f,
    )
    const val SIDEBAR_TILE_X = 0.0066f
    const val SIDEBAR_TILE_W = 0.0915f

    /** The 3-segment page finder (Gains, Xovers). */
    val finder3 = Frac(0.629f, 0.048f, 0.341f, 0.0693f)

    /** The 5-segment page finder (Compressor). */
    val finder5 = Frac(0.52f, 0.048f, 0.45f, 0.0693f)
}

/** Where a [WorkspaceArtBox] should assume it sits, in window pixels. A pager host provides its
 *  own position so every page lays out as if settled, and art-placed content slides with its page
 *  instead of being held still against the art mid-swipe. */
val LocalArtAnchor = compositionLocalOf<Offset?> { null }

interface WorkspaceArtScope {
    /** Places this child over [frac] of the workspace art, sized to it exactly. */
    fun Modifier.artRect(frac: WorkspaceArt.Frac): Modifier
}

private object WorkspaceArtScopeInstance : WorkspaceArtScope {
    override fun Modifier.artRect(frac: WorkspaceArt.Frac): Modifier = this.then(ArtRectData(frac))
}

private class ArtRectData(val frac: WorkspaceArt.Frac) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@ArtRectData
}

/**
 * Lays each child over its [WorkspaceArtScope.artRect] of the workspace backdrop, using the same
 * centerCrop mapping as `R.id.dsp_workspace_backdrop`'s ImageView -- so controls line up with the
 * art wherever this box itself sits (toolbar line, pager page). Children without an art rect are
 * not placed. Fills its incoming constraints.
 */
@Composable
fun WorkspaceArtBox(modifier: Modifier = Modifier, content: @Composable WorkspaceArtScope.() -> Unit) {
    val view = LocalView.current
    val anchor = LocalArtAnchor.current
    var ownPosition by remember { mutableStateOf<Offset?>(null) }

    Layout(
        content = { WorkspaceArtScopeInstance.content() },
        modifier = modifier.onGloballyPositioned { ownPosition = it.positionInWindow() },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val origin = anchor ?: ownPosition
        val frame = backdropFrame(view)
        if (origin == null || frame == null) return@Layout layout(width, height) {}

        val placed = measurables.mapNotNull { measurable ->
            val frac = (measurable.parentData as? ArtRectData)?.frac ?: return@mapNotNull null
            val left = (frame.left + frac.x * frame.shownWidth - origin.x).roundToInt()
            val top = (frame.top + frac.y * frame.shownHeight - origin.y).roundToInt()
            val right = (frame.left + (frac.x + frac.w) * frame.shownWidth - origin.x).roundToInt()
            val bottom = (frame.top + (frac.y + frac.h) * frame.shownHeight - origin.y).roundToInt()
            Triple(measurable.measure(Constraints.fixed(right - left, bottom - top)), left, top)
        }
        layout(width, height) {
            placed.forEach { (placeable, x, y) -> placeable.place(x, y) }
        }
    }
}

private class ArtFrame(val left: Float, val top: Float, val shownWidth: Float, val shownHeight: Float)

/** The workspace backdrop's drawn art rect in window pixels (centerCrop), or null before layout. */
private fun backdropFrame(view: View): ArtFrame? {
    val backdrop = view.rootView.findViewById<View>(R.id.dsp_workspace_backdrop) ?: return null
    if (backdrop.width == 0 || backdrop.height == 0) return null
    val location = IntArray(2)
    backdrop.getLocationInWindow(location)
    val scale = max(backdrop.width / WorkspaceArt.IMAGE_WIDTH, backdrop.height / WorkspaceArt.IMAGE_HEIGHT)
    val shownWidth = WorkspaceArt.IMAGE_WIDTH * scale
    val shownHeight = WorkspaceArt.IMAGE_HEIGHT * scale
    return ArtFrame(
        left = location[0] + (backdrop.width - shownWidth) / 2f,
        top = location[1] + (backdrop.height - shownHeight) / 2f,
        shownWidth = shownWidth,
        shownHeight = shownHeight,
    )
}
