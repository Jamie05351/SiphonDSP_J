package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The workspace screen's backdrop: the front page's facia over the whole screen, with the content
 * window cut into it to the right of the sidebar column ([sidebarWidth]), [SidebarFacia] in from
 * the other edges, holding the dark texture. The facia round the edge is the screen's bezel, and
 * the same facia carries on round the sidebar screen that [DspSidebarRail] draws on top.
 *
 * Needs `res/drawable-nodpi/dsp_workspace_bg.webp` (the 2340x878 texture, which is the head unit's
 * own aspect; other aspects are cover-cropped). It is James's charcoal texture, brightened 1.2x
 * (average about 29/255, neutral grey; it replaced the old navy one), saved lossless: a lossy
 * re-encode once flattened an earlier texture to near solid black.
 *
 * The content container's right and bottom margin (dsp_workspace_bezel_inset, 12dp) is
 * [SidebarFacia], so the content sits inside the window.
 */
@Composable
fun DspWorkspaceBackdrop(sidebarWidth: Dp, modifier: Modifier = Modifier) {
    val background = ImageBitmap.imageResource(R.drawable.dsp_workspace_bg)
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(FaciaColour)
                drawContentWindow(contentWindowRect(size, sidebarWidth.toPx(), SidebarFacia.toPx()), background)
            },
    )
}

/**
 * The content window in [window]: the texture cover-cropped into it, shaded at its edges so it
 * reads as set into the facia. [alpha] fades it as a whole.
 */
internal fun DrawScope.drawContentWindow(window: Rect, image: ImageBitmap, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val corner = ContentWindowCorner.toPx()
    translate(window.left, window.top) {
        drawCoverImage(image, corner, window.size, alpha)
        drawInsetShadow(corner, depth = 9.5.dp.toPx(), window.size, alpha)
    }
}

/** Cover-crops [image] to [area] at the origin, clipped to a rounded rect of radius [corner]. */
private fun DrawScope.drawCoverImage(image: ImageBitmap, corner: Float, area: Size, alpha: Float) {
    val w = area.width
    val h = area.height
    val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(corner))) }
    clipPath(shape) {
        val scale = max(w / image.width, h / image.height)
        val srcW = (w / scale).roundToInt().coerceIn(1, image.width)
        val srcH = (h / scale).roundToInt().coerceIn(1, image.height)
        drawImage(
            image = image,
            srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Dark strokes fading inwards from the edge of [area], so the window reads as recessed. */
private fun DrawScope.drawInsetShadow(corner: Float, depth: Float, area: Size, alpha: Float, steps: Int = 4) {
    val step = depth / steps
    for (i in 0 until steps) {
        drawRoundRect(
            color = Color.Black.copy(alpha = (0.34f - i * 0.08f) * alpha),
            topLeft = Offset(i * step / 2f, i * step / 2f),
            size = Size(area.width - i * step, area.height - i * step),
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = step * 2f),
        )
    }
}
