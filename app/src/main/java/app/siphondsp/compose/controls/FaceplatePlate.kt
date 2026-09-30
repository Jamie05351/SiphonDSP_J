package app.siphondsp.compose.controls

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * The hardware faceplate's plate and bezel, shared so every surface made of it matches: the front
 * page ([HomeFaceplate]) and the workspace sidebar ([DspSidebarRail]). Draw [drawPlate], then
 * [drawBezelRing] with [FaceplateCorner] and [FaceplateBezel].
 */
internal val FaceplateCorner = 12.dp
internal val FaceplateBezel = 4.dp

/** Horizontal machining streaks, generated once from a fixed seed so it never shimmers or changes. */
internal fun brushedMetalBrush(): ShaderBrush {
    val bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint()
    val random = Random(20260929)
    repeat(140) {
        val light = random.nextBoolean()
        paint.color = android.graphics.Color.argb(
            (10 + random.nextInt(40)),
            if (light) 255 else 0, if (light) 255 else 0, if (light) 255 else 0,
        )
        val y = random.nextInt(128).toFloat()
        val x = random.nextInt(256).toFloat()
        canvas.drawRect(x, y, x + 40f + random.nextInt(216), y + 1f, paint)
    }
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** The plate over the whole draw area: dark brushed metal, lighter at the top, rounded to [corner]. */
internal fun DrawScope.drawPlate(streaks: ShaderBrush, corner: Float) {
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF2A2A2C), Color(0xFF0E0E0F))),
        cornerRadius = CornerRadius(corner),
    )
    drawRoundRect(brush = streaks, alpha = 0.55f, cornerRadius = CornerRadius(corner))
}
