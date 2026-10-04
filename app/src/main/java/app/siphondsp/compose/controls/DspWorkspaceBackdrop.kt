package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The workspace screen's backdrop, drawn live instead of baked into per-destination art: your dark
 * texture filling the whole screen, and a slim metal bezel running around the entire screen edge
 * (same metal as the tile and rail bezels).
 *
 * Needs `res/drawable-nodpi/dsp_workspace_bg.webp` (the 2340x878 texture, which is the head unit's
 * own aspect, so it fills 1280x480 without cropping; other aspects are cover-cropped). It is
 * James's dark navy texture (average about 20/255, a blue cast; the lifted grey before it read
 * too bright on the head unit), saved lossless: a lossy re-encode once flattened an earlier
 * texture to near solid black.
 *
 * The sidebar is a separate layer on top ([DspSidebarRail]); the content sits to its right.
 * The bezel and its inner lip take about [bezel] * 1.75 of the screen edge, so give the content
 * container that much margin on the right and bottom (the sidebar already sits inside it).
 */
@Composable
fun DspWorkspaceBackdrop(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    bezel: Dp = 4.dp,
) {
    val background = ImageBitmap.imageResource(R.drawable.dsp_workspace_bg)
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                val r = cornerRadius.toPx()
                val b = bezel.toPx()
                // Real screen corners are square, so anything outside the rounded bezel is black.
                drawRect(Color.Black)
                drawCoverImage(background, r)
                drawInsetShadow(r, depth = b * 2.5f)
                drawBezelRing(0f, 0f, size.width, size.height, r, b)
            },
    )
}

/** Cover-crops [image] to the whole draw area, clipped to a rounded rect of radius [corner]. */
internal fun DrawScope.drawCoverImage(image: ImageBitmap, corner: Float, alignX: Float = 0.5f) {
    val w = size.width
    val h = size.height
    val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(corner))) }
    clipPath(shape) {
        val scale = max(w / image.width, h / image.height)
        val srcW = (w / scale).roundToInt().coerceIn(1, image.width)
        val srcH = (h / scale).roundToInt().coerceIn(1, image.height)
        drawImage(
            image = image,
            srcOffset = IntOffset(
                ((image.width - srcW) * alignX.coerceIn(0f, 1f)).roundToInt(),
                ((image.height - srcH) / 2f).roundToInt(),
            ),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Dark strokes fading inwards from the edge, so the panel reads as recessed behind the bezel. */
internal fun DrawScope.drawInsetShadow(corner: Float, depth: Float, steps: Int = 4) {
    val step = depth / steps
    for (i in 0 until steps) {
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.34f - i * 0.08f),
            topLeft = Offset(i * step / 2f, i * step / 2f),
            size = Size(size.width - i * step, size.height - i * step),
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = step * 2f),
        )
    }
}

/**
 * Slim bezel: a metal ring lit from the top-left and dark at the bottom-right,
 * a black gap, and a hairline highlight on the inner lip. Occupies the rect
 * ([left], [top], [width], [height]) and reaches about 1.75 x [bezel] inwards from its edge.
 */
internal fun DrawScope.drawBezelRing(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    corner: Float,
    bezel: Float,
) {
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color(0xFF8E9194), Color(0xFF45474A), Color(0xFF222426)),
            start = Offset(left, top), end = Offset(left + width, top + height),
        ),
        topLeft = Offset(left + bezel / 2f, top + bezel / 2f),
        size = Size(width - bezel, height - bezel),
        cornerRadius = CornerRadius(corner),
        style = Stroke(bezel),
    )
    val gap = bezel * 0.5f
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.9f),
        topLeft = Offset(left + bezel + gap / 2f, top + bezel + gap / 2f),
        size = Size(width - 2f * bezel - gap, height - 2f * bezel - gap),
        cornerRadius = CornerRadius((corner - bezel).coerceAtLeast(0f)),
        style = Stroke(gap),
    )
    val lip = bezel + gap + 0.5f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.10f),
        topLeft = Offset(left + lip, top + lip),
        size = Size(width - 2f * lip, height - 2f * lip),
        cornerRadius = CornerRadius((corner - lip).coerceAtLeast(0f)),
        style = Stroke(1f),
    )
}
