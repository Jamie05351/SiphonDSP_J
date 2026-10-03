package app.siphondsp.compose.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

val AllpassGraphic: ImageVector by lazy {
    ImageVector.Builder(name = "Allpass", defaultWidth = 230.dp, defaultHeight = 150.dp, viewportWidth = 230f, viewportHeight = 150f).apply {
        addPath(
            pathData = PathParser().parsePathString("M7,75 C25,38 38,18 51,21 C85,27 104,129 137,129 C167,129 187,21 222,21").toNodes(),
            stroke = SolidColor(Color(0xFFF4F0F8)), strokeAlpha = 1f, strokeLineWidth = 3.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M7,126 C40,126 62,22 91,21 C124,21 148,129 174,129 C193,129 210,100 222,75").toNodes(),
            stroke = SolidColor(Color(0xFFB83FF2)), strokeAlpha = 1f, strokeLineWidth = 3.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
