package app.siphondsp.compose.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

val XoversGraphic: ImageVector by lazy {
    ImageVector.Builder(name = "Xovers", defaultWidth = 230.dp, defaultHeight = 150.dp, viewportWidth = 230f, viewportHeight = 150f).apply {
        addPath(
            pathData = PathParser().parsePathString("M7,75 H69").toNodes(),
            stroke = SolidColor(Color(0xFFF5F4E9)), strokeAlpha = 1f, strokeLineWidth = 3.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M69,75 C104,75 104,25 140,25 H223").toNodes(),
            stroke = SolidColor(Color(0xFF20A9FA)), strokeAlpha = 1f, strokeLineWidth = 3.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M69,75 H223").toNodes(),
            stroke = SolidColor(Color(0xFFF9B719)), strokeAlpha = 1f, strokeLineWidth = 3.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M69,75 C104,75 106,126 140,126 H223").toNodes(),
            stroke = SolidColor(Color(0xFFFF751B)), strokeAlpha = 1f, strokeLineWidth = 3.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
