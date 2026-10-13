package app.siphondsp.compose.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Output card's artwork: two LED level rows (cyan, the top segments magenta as on the front
 * page's meters, the rest faint) against a red ceiling line -- the page's headroom, limiter and
 * master volume in one picture. Figma "Menus · PROPOSED", frame 1.
 */
val OutputGraphic: ImageVector by lazy {
    ImageVector.Builder(name = "Output", defaultWidth = 230.dp, defaultHeight = 150.dp, viewportWidth = 230f, viewportHeight = 150f).apply {
        for ((top, lit) in listOf(42f to 10, 86f to 9)) {
            for (i in 0 until Segments) {
                val x = 14f + i * Pitch
                val colour = when {
                    i >= lit -> Cyan.copy(alpha = 0.18f)
                    i >= Segments - 3 -> Magenta
                    else -> Cyan
                }
                addPath(
                    pathData = PathParser().parsePathString(roundRect(x, top, SegmentWidth, SegmentHeight, 2.5f)).toNodes(),
                    fill = SolidColor(colour),
                )
            }
        }
        addPath(
            pathData = PathParser().parsePathString("M205,28 L205,124").toNodes(),
            stroke = SolidColor(Color(0xFFFF3B5C)), strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round,
        )
    }.build()
}

private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r},$y H${x + w - r} Q${x + w},$y ${x + w},${y + r} V${y + h - r} Q${x + w},${y + h} ${x + w - r},${y + h} " +
        "H${x + r} Q$x,${y + h} $x,${y + h - r} V${y + r} Q$x,$y ${x + r},$y Z"

private const val Segments = 13
private const val Pitch = 14.5f
private const val SegmentWidth = 9f
private const val SegmentHeight = 22f
private val Cyan = Color(0xFF00E5FF)
private val Magenta = Color(0xFFF040CC)
