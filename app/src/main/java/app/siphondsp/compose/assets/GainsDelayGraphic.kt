package app.siphondsp.compose.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

val GainsDelayGraphic: ImageVector by lazy {
    ImageVector.Builder(name = "GainsDelay", defaultWidth = 230.dp, defaultHeight = 150.dp, viewportWidth = 230f, viewportHeight = 150f).apply {
        addPath(
            pathData = PathParser().parsePathString("M7,135 H223").toNodes(),
            stroke = SolidColor(Color(0xFF459875)), strokeAlpha = 0.7f, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M24,135 C29,135 30,133 32,95 C33,53 35,12 38,12 C41,12 42,88 44,119 C46,134 48,135 54,135").toNodes(),
            stroke = SolidColor(Color(0xFFF3F7F5)), strokeAlpha = 1f, strokeLineWidth = 3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M159,135 C166,135 166,123 170,108 C173,90 174,94 177,112 C179,127 180,135 185,135").toNodes(),
            stroke = SolidColor(Color(0xFF28D77B)), strokeAlpha = 1f, strokeLineWidth = 3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M55,31 H60").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M66,31 H71").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M77,31 H82").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M88,31 H93").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M99,31 H104").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M110,31 H115").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M121,31 H126").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M132,31 H137").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M143,31 H148").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M154,31 H159").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 0.6f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M153,27 L160,31 L153,35").toNodes(),
            stroke = SolidColor(Color(0xFFE0E8E4)), strokeAlpha = 1f, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
