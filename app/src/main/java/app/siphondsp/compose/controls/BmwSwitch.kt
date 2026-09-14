package app.siphondsp.compose.controls

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin
import kotlin.math.max

/**
 * The app's ON/OFF switch: a Canvas recreation of the "metal toggle" look -- a machined dark pill
 * with a faint brushed sheen, a spun-chrome knob riding proud of it, and a coloured perimeter
 * glow on the knob's side. Green + knob-right + "ON" for on, red + knob-left + "OFF" for off. The
 * knob is neutral chrome in both states; only the glow and the label carry the status colour.
 *
 * Drawn (not a bitmap) so it stays crisp and the labels stay legible at the small inline size the
 * slider rows use. Colours default to [BmwDashboardSkin]'s `GLASS_SWITCH_*` constants (shared with
 * the now-dead View path) and labels default to "ON"/"OFF"; [onColor]/[offColor]/[onLabel]/
 * [offLabel] let a caller with its own on/off convention (e.g. polarity: green=normal,
 * pink=inverted) reuse this same chrome instead of a second switch implementation.
 *
 * Built on [toggleable] with [Role.Switch]. Stateless: the caller hoists [checked] /
 * [onCheckedChange]. Footprint ([ComponentWidth] x [ComponentHeight]) is unchanged from the
 * previous switch so every call site's layout is untouched.
 */
@Composable
fun BmwSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    // Defaults are the app's standard ON/OFF green/red glass switch. A caller with its own
    // status-colour convention (e.g. polarity: green=normal, pink=inverted) overrides these
    // rather than needing a second switch implementation.
    onColor: Color = Color(BmwDashboardSkin.GLASS_SWITCH_ON_COLOR),
    offColor: Color = Color(BmwDashboardSkin.GLASS_SWITCH_OFF_COLOR),
    onLabel: String = "ON",
    offLabel: String = "OFF",
    // Wider labels ("NORMAL"/"INVERT" for the polarity switch) need more room than the default
    // ON/OFF footprint every other call site relies on, so width is overridable per-instance.
    width: Dp = ComponentWidth,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "bmwSwitchProgress",
    )
    val desc = contentDescription

    Canvas(
        modifier = modifier
            .then(if (enabled) Modifier else Modifier.alpha(DisabledAlpha))
            .size(width, ComponentHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .then(
                if (desc != null) Modifier.semantics { this.contentDescription = desc } else Modifier
            ),
    ) {
        val status = lerp(offColor, onColor, progress)
        val pillH = PillHeight.toPx()
        val pillTop = (size.height - pillH) / 2f
        val pill = Rect(0f, pillTop, size.width, pillTop + pillH)
        val corner = pill.height / 2f

        val knobD = KnobDiameter.toPx()
        val travel = size.width - knobD
        val knobLeft = travel * progress
        val kcx = knobLeft + knobD / 2f
        val kcy = size.height / 2f
        val kr = knobD / 2f

        drawPerimeterGlow(pill, corner, status, progress, kcx, kcy, kr)
        drawPillBody(pill, corner, status)
        drawLabels(pill, corner, knobD, progress, onColor, offColor, onLabel, offLabel)
        drawKnob(kcx, kcy, kr, status)
    }
}

// --- geometry --------------------------------------------------------------------------------
// Longer than the old 62x34 switch: the knob + label had no room to breathe. The toggle-zone
// slot every call site puts this in is >=120dp wide, so the extra length costs no layout.
private val ComponentWidth = 82.dp
private val ComponentHeight = 35.dp
private val PillHeight = 26.dp
private val PillBorderWidth = 1.4.dp
private val KnobDiameter = 30.dp
private val KnobShadowBlur = 4.dp
private val KnobRimWidth = 1.7.dp
private val KnobOutlineWidth = 1.dp
private val GlowBlur = 6.dp
private const val LabelTextSizeSp = 12f
private const val LabelLetterSpacing = 0.07f
private val LabelBlur = 3.dp
private const val DisabledAlpha = 0.4f

// --- colours -------------------------------------------------------------------------------
private val PillTop = Color(0xFF2C2C2E)
private val PillMid = Color(0xFF161617)
private val PillBottom = Color(0xFF050506)
private val PillInnerTopShadow = Color(0x99000000)
private val PillInnerBottomCatch = Color(0x1FFFFFFF)
private val BrushLine = Color(0x0BFFFFFF)

private val KnobLight = Color(0xFFEDF0F3)
private val KnobMid = Color(0xFF9A9FA6)
private val KnobDark = Color(0xFF43474D)
private val KnobDeep = Color(0xFF212429)
private val KnobRimLight = Color(0xFFF6F8FA)
private val KnobRimDark = Color(0xFF2B2E33)
private val KnobOutline = Color(0xFF141619)
private val KnobShadow = Color(0x8C000000)

private fun Rect.insetBy(amount: Float) = Rect(left + amount, top + amount, right - amount, bottom - amount)

private fun DrawScope.drawPerimeterGlow(
    pill: Rect,
    corner: Float,
    status: Color,
    progress: Float,
    kcx: Float,
    kcy: Float,
    kr: Float,
) {
    val glowBlur = GlowBlur.toPx()
    val strokeW = PillBorderWidth.toPx() * 2.4f
    drawIntoCanvas { canvas ->
        // Ambient rim light: the whole pill edge picks up a faint wash of the status colour.
        val ambient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            maskFilter = BlurMaskFilter(glowBlur, BlurMaskFilter.Blur.NORMAL)
            color = status.copy(alpha = 0.16f).toArgb()
        }
        canvas.nativeCanvas.drawRoundRect(
            pill.left, pill.top, pill.right, pill.bottom, corner, corner, ambient,
        )

        // Concentrated glow, weighted to the knob's side by a horizontal alpha ramp (no hard
        // clip): strong at the left edge for OFF, at the right edge for ON.
        val leftA = (0.55f * (1f - progress)).coerceIn(0f, 0.55f)
        val rightA = (0.55f * progress).coerceIn(0f, 0.55f)
        val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            maskFilter = BlurMaskFilter(glowBlur * 1.6f, BlurMaskFilter.Blur.NORMAL)
            shader = android.graphics.LinearGradient(
                pill.left, 0f, pill.right, 0f,
                status.copy(alpha = leftA).toArgb(),
                status.copy(alpha = rightA).toArgb(),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.nativeCanvas.drawRoundRect(
            pill.left, pill.top, pill.right, pill.bottom, corner, corner, hot,
        )
        // Soft bloom sitting behind the knob itself.
        val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(kr * 0.85f, BlurMaskFilter.Blur.NORMAL)
            color = status.copy(alpha = 0.3f).toArgb()
        }
        canvas.nativeCanvas.drawCircle(kcx, kcy, kr * 0.95f, bloom)
    }
}

private fun DrawScope.drawPillBody(pill: Rect, corner: Float, status: Color) {
    val borderPx = PillBorderWidth.toPx()
    val body = pill.insetBy(borderPx / 2f)
    val bodyCorner = body.height / 2f
    val clip = Path().apply { addRoundRect(RoundRect(body, CornerRadius(bodyCorner))) }

    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to PillTop, 0.5f to PillMid, 1f to PillBottom,
            startY = body.top, endY = body.bottom,
        ),
        topLeft = Offset(body.left, body.top),
        size = Size(body.width, body.height),
        cornerRadius = CornerRadius(bodyCorner),
    )

    clipPath(clip) {
        // Brushed-metal striations: a few very faint horizontal lines across the body.
        val lines = 5
        for (i in 1..lines) {
            val y = body.top + body.height * i / (lines + 1)
            drawLine(
                color = BrushLine,
                start = Offset(body.left + bodyCorner * 0.3f, y),
                end = Offset(body.right - bodyCorner * 0.3f, y),
                strokeWidth = density,
            )
        }
        // Recessed feel: dark inner shadow along the top edge, faint catch-light along the bottom.
        drawIntoCanvas { canvas ->
            val topShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
                maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
                color = PillInnerTopShadow.toArgb()
            }
            canvas.nativeCanvas.drawRoundRect(
                body.left, body.top - 2f * density, body.right, body.bottom,
                bodyCorner, bodyCorner, topShadow,
            )
        }
        drawLine(
            color = PillInnerBottomCatch,
            start = Offset(body.left + bodyCorner, body.bottom - density),
            end = Offset(body.right - bodyCorner, body.bottom - density),
            strokeWidth = density,
        )
        // Specular skim along the very top edge.
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.30f),
                1f to Color.Transparent,
                startX = body.left + bodyCorner, endX = body.right - bodyCorner,
            ),
            start = Offset(body.left + bodyCorner, body.top + density),
            end = Offset(body.right - bodyCorner, body.top + density),
            strokeWidth = density * 1.2f,
        )
    }

    // Dark metal outline so the pill shape is always defined, then a thin status-tinted rim over
    // it so the edge still reads coloured on the side away from the glow.
    drawRoundRect(
        color = Color(0xFF0B0C0E),
        topLeft = Offset(body.left, body.top),
        size = Size(body.width, body.height),
        cornerRadius = CornerRadius(bodyCorner),
        style = Stroke(borderPx * 1.3f),
    )
    drawRoundRect(
        color = lerp(Color(0xFF3C4046), status, 0.55f).copy(alpha = 0.9f),
        topLeft = Offset(body.left, body.top),
        size = Size(body.width, body.height),
        cornerRadius = CornerRadius(bodyCorner),
        style = Stroke(borderPx),
    )
}

private fun DrawScope.drawLabels(
    pill: Rect,
    corner: Float,
    knobD: Float,
    progress: Float,
    onColor: Color,
    offColor: Color,
    onLabel: String,
    offLabel: String,
) {
    // OFF fades out quickly as the knob leaves the left; ON fades in over the second half.
    val offAlpha = (1f - progress * 2f).coerceIn(0f, 1f)
    val onAlpha = ((progress - 0.5f) * 2f).coerceIn(0f, 1f)
    val offX = (knobD + pill.right) / 2f
    val onX = (pill.left + (pill.right - knobD)) / 2f

    drawIntoCanvas { canvas ->
        fun label(text: String, cx: Float, color: Color, a: Float) {
            if (a <= 0.01f) return
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = LabelTextSizeSp * density
                letterSpacing = LabelLetterSpacing
                this.color = color.copy(alpha = a).toArgb()
            }
            val glow = Paint(base).apply {
                this.color = color.copy(alpha = a * 0.7f).toArgb()
                maskFilter = BlurMaskFilter(LabelBlur.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            val baselineY = pill.center.y - (base.descent() + base.ascent()) / 2f
            canvas.nativeCanvas.drawText(text, cx, baselineY, glow)
            canvas.nativeCanvas.drawText(text, cx, baselineY, base)
        }
        label(offLabel, offX, offColor, offAlpha)
        label(onLabel, onX, onColor, onAlpha)
    }
}

private fun DrawScope.drawKnob(cx: Float, cy: Float, r: Float, status: Color) {
    drawIntoCanvas { canvas ->
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(KnobShadowBlur.toPx(), BlurMaskFilter.Blur.NORMAL)
            color = KnobShadow.toArgb()
        }
        canvas.nativeCanvas.drawCircle(cx, cy + 1.5f * density, r, shadow)
    }

    // Spun-metal base: a sweep gradient of alternating light/dark greys around the centre.
    drawCircle(
        brush = Brush.sweepGradient(
            0.00f to KnobMid, 0.09f to KnobLight, 0.22f to KnobDark, 0.31f to KnobMid,
            0.44f to KnobLight, 0.56f to KnobDark, 0.67f to KnobMid, 0.80f to KnobLight,
            0.92f to KnobDark, 1.00f to KnobMid,
            center = Offset(cx, cy),
        ),
        radius = r,
        center = Offset(cx, cy),
    )
    // 3D form: bright upper-left, shaded lower-right.
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.34f),
            0.55f to Color.Transparent,
            1f to KnobDeep.copy(alpha = 0.55f),
            center = Offset(cx - r * 0.34f, cy - r * 0.40f),
            radius = r * 1.7f,
        ),
        radius = r,
        center = Offset(cx, cy),
    )
    // Faint pickup of the status glow on the knob's outer edge.
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Transparent,
            0.72f to Color.Transparent,
            1f to status.copy(alpha = 0.18f),
            center = Offset(cx, cy),
            radius = r,
        ),
        radius = r,
        center = Offset(cx, cy),
    )

    // Bright machined rim: vertical light->dark so it reads as a bevel.
    val rimW = KnobRimWidth.toPx()
    drawCircle(
        brush = Brush.verticalGradient(
            0f to KnobRimLight, 1f to KnobRimDark,
            startY = cy - r, endY = cy + r,
        ),
        radius = r - rimW / 2f,
        center = Offset(cx, cy),
        style = Stroke(rimW),
    )
    // Thin dark outline.
    drawCircle(
        color = KnobOutline,
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(KnobOutlineWidth.toPx()),
    )
    // Crisp specular hotspot, upper-left.
    drawCircle(
        color = Color.White.copy(alpha = 0.9f),
        radius = max(r * 0.16f, density),
        center = Offset(cx - r * 0.36f, cy - r * 0.40f),
    )
    // Top catch-light arc.
    val arc = Rect(cx - r * 0.62f, cy - r * 0.86f, cx + r * 0.62f, cy - r * 0.10f)
    drawArc(
        color = Color.White.copy(alpha = 0.55f),
        startAngle = 200f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(arc.left, arc.top),
        size = Size(arc.width, arc.height),
        style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round),
    )
}
