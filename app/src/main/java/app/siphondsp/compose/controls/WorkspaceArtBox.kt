package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.R

/**
 * The head-unit workspace faceplate (DspWorkspaceBackdrop) has the same 8:3 aspect
 * ratio as the fixed 1280x480 mdpi head unit. Rects are authored in that 1280x480 dp
 * coordinate space by the layout tools in REW/_UI.
 */
object WorkspaceArt {
    const val SCREEN_WIDTH_DP = 1280f
    const val SCREEN_HEIGHT_DP = 480f

    class Frac(val x: Float, val y: Float, val w: Float, val h: Float)

    /** The 3-segment page finder (Gains, Xovers). */
    val finder3 = Frac(0.629f, 0.048f, 0.341f, 0.0693f)

    /** The 5-segment page finder (Compressor). */
    val finder5 = Frac(0.52f, 0.048f, 0.45f, 0.0693f)

    /** Where the content column started when the screens' rects were authored (the old 140dp
     *  sidebar). Content pages stretch x from here to the current column; see [WorkspaceArtScope]. */
    const val AUTHORED_CONTENT_LEFT_DP = 140f
}

/**
 * Scope for controls positioned over the fixed head-unit artwork. The host supplies the full-screen
 * origin of its own Compose surface; [artRect] converts the artwork rect into ordinary page-local
 * `offset` and `size` modifiers. This deliberately uses normal Compose layout instead of reading
 * window coordinates during layout or forcing child constraints from a second global frame.
 */
class WorkspaceArtScope internal constructor(
    private val originX: Dp,
    private val originY: Dp,
    /** Content pages only: the column's right edge. Rects are then stretched horizontally from the
     *  authored span (AUTHORED_CONTENT_LEFT_DP..right) to the current one (originX..right), so the
     *  width the narrower sidebar gave back goes to the screen. Null places rects 1:1. */
    private val contentRight: Dp? = null,
) {
    fun Modifier.artRect(frac: WorkspaceArt.Frac): Modifier {
        var x = frac.x * WorkspaceArt.SCREEN_WIDTH_DP
        var w = frac.w * WorkspaceArt.SCREEN_WIDTH_DP
        if (contentRight != null) {
            val authoredLeft = WorkspaceArt.AUTHORED_CONTENT_LEFT_DP
            val scale = (contentRight.value - originX.value) / (contentRight.value - authoredLeft)
            x = originX.value + (x - authoredLeft) * scale
            w *= scale
        }
        return absoluteOffset(
            x = x.dp - originX,
            y = (frac.y * WorkspaceArt.SCREEN_HEIGHT_DP).dp - originY,
        ).size(
            width = w.dp,
            height = (frac.h * WorkspaceArt.SCREEN_HEIGHT_DP).dp,
        )
    }
}

/**
 * Hosts head-unit controls using the page's own local coordinate system. Content pages begin after
 * the fixed sidebar and toolbar; callers hosted elsewhere (the toolbar page finder) provide their
 * own origin explicitly. Pager pages now move their content naturally, without a global-position
 * anchor or window-coordinate feedback loop.
 */
@Composable
fun WorkspaceArtBox(
    modifier: Modifier = Modifier,
    originX: Dp? = null,
    originY: Dp? = null,
    content: @Composable WorkspaceArtScope.() -> Unit,
) {
    val scope = WorkspaceArtScope(
        originX = originX ?: dimensionResource(R.dimen.dsp_sidebar_width),
        originY = originY ?: dimensionResource(R.dimen.dsp_workspace_toolbar_height),
        // A content page (default origin) gets the stretch; an explicitly placed host doesn't.
        contentRight = if (originX == null) {
            WorkspaceArt.SCREEN_WIDTH_DP.dp - dimensionResource(R.dimen.dsp_workspace_bezel_inset)
        } else {
            null
        },
    )
    Box(modifier) { scope.content() }
}
