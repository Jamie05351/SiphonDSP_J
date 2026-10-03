package app.siphondsp.compose.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

val PeqGraphic: ImageVector by lazy {
    ImageVector.Builder(name = "Peq", defaultWidth = 230.dp, defaultHeight = 150.dp, viewportWidth = 230f, viewportHeight = 150f).apply {
        addPath(
            pathData = PathParser().parsePathString("M5,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M29,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M53,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M77,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M101,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M125,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M149,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M173,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M197,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M221,10 V145").toNodes(),
            stroke = SolidColor(Color(0xFF263340)), strokeAlpha = 0.35f, strokeLineWidth = 0.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M3,75 H227").toNodes(),
            stroke = SolidColor(Color(0xFF678475)), strokeAlpha = 0.65f, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M3,75 C23,75 26,58 39,47 C49,58 49,75 92,75 H227").toNodes(),
            stroke = SolidColor(Color(0xFFCB48E0)), strokeAlpha = 1f, strokeLineWidth = 3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M3,75 C51,75 72,72 85,25 C96,72 119,75 155,75 H227").toNodes(),
            stroke = SolidColor(Color(0xFF28ADFF)), strokeAlpha = 1f, strokeLineWidth = 3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M3,75 H89 C113,75 119,83 130,126 C142,81 146,75 178,75 H227").toNodes(),
            stroke = SolidColor(Color(0xFFFFBE20)), strokeAlpha = 1f, strokeLineWidth = 3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M3,75 H142 C174,75 175,66 189,47 C201,72 211,75 227,75").toNodes(),
            stroke = SolidColor(Color(0xFF3DEB87)), strokeAlpha = 1f, strokeLineWidth = 3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = PathParser().parsePathString("M32.2,47 A6.8,6.8 0 1,0 45.8,47 A6.8,6.8 0 1,0 32.2,47 Z").toNodes(),
            fill = SolidColor(Color(0xFFD5DFE8)), fillAlpha = 1f,
        )
        addPath(
            pathData = PathParser().parsePathString("M34.7,47 A4.3,4.3 0 1,0 43.3,47 A4.3,4.3 0 1,0 34.7,47 Z").toNodes(),
            fill = SolidColor(Color(0xFFCB48E0)), fillAlpha = 1f,
        )
        addPath(
            pathData = PathParser().parsePathString("M78.2,25 A6.8,6.8 0 1,0 91.8,25 A6.8,6.8 0 1,0 78.2,25 Z").toNodes(),
            fill = SolidColor(Color(0xFFD5DFE8)), fillAlpha = 1f,
        )
        addPath(
            pathData = PathParser().parsePathString("M80.7,25 A4.3,4.3 0 1,0 89.3,25 A4.3,4.3 0 1,0 80.7,25 Z").toNodes(),
            fill = SolidColor(Color(0xFF28ADFF)), fillAlpha = 1f,
        )
        addPath(
            pathData = PathParser().parsePathString("M123.2,126 A6.8,6.8 0 1,0 136.8,126 A6.8,6.8 0 1,0 123.2,126 Z").toNodes(),
            fill = SolidColor(Color(0xFFD5DFE8)), fillAlpha = 1f,
        )
        addPath(
            pathData = PathParser().parsePathString("M125.7,126 A4.3,4.3 0 1,0 134.3,126 A4.3,4.3 0 1,0 125.7,126 Z").toNodes(),
            fill = SolidColor(Color(0xFFFFBE20)), fillAlpha = 1f,
        )
        addPath(
            pathData = PathParser().parsePathString("M182.2,47 A6.8,6.8 0 1,0 195.8,47 A6.8,6.8 0 1,0 182.2,47 Z").toNodes(),
            fill = SolidColor(Color(0xFFD5DFE8)), fillAlpha = 1f,
        )
        addPath(
            pathData = PathParser().parsePathString("M184.7,47 A4.3,4.3 0 1,0 193.3,47 A4.3,4.3 0 1,0 184.7,47 Z").toNodes(),
            fill = SolidColor(Color(0xFF3DEB87)), fillAlpha = 1f,
        )
    }.build()
}
