package app.siphondsp.compose.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin

/**
 * Universal glossy toggle using the app's existing state colours. Standard switches remain green
 * ON / red OFF, while callers such as polarity can keep passing their own [onColor]/[offColor]
 * and labels. Only the hardware treatment changes: black glass body, illuminated selected side,
 * capsule thumb, bright perimeter and a short inset marker.
 */
@Composable
fun BmwSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onColor: Color = Color(BmwDashboardSkin.GLASS_SWITCH_ON_COLOR),
    offColor: Color = Color(BmwDashboardSkin.GLASS_SWITCH_OFF_COLOR),
    onLabel: String = "ON",
    offLabel: String = "OFF",
    width: Dp = ComponentWidth,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "bmwSwitchProgress",
    )
    val status = if (checked) onColor else offColor
    val interactionSource = remember { MutableInteractionSource() }

    Canvas(
        modifier = modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .size(width, ComponentHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onValueChange = onCheckedChange,
            )
            .bmwFocusRing(interactionSource, cornerRadius = ComponentHeight / 2)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        val outer = Rect(0f, 0f, size.width, size.height)
        val outerCorner = outer.height / 2f
        val body = Rect(
            left = BodyInset.toPx(),
            top = BodyInset.toPx(),
            right = size.width - BodyInset.toPx(),
            bottom = size.height - BodyInset.toPx(),
        )
        val bodyCorner = body.height / 2f

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(GlassHighlight, GlassMid, Color.Black),
                startY = outer.top,
                endY = outer.bottom,
            ),
            topLeft = outer.topLeft,
            size = outer.size,
            cornerRadius = CornerRadius(outerCorner),
        )
        drawRoundRect(
            color = SmokedEdge,
            topLeft = outer.topLeft,
            size = outer.size,
            cornerRadius = CornerRadius(outerCorner),
            style = Stroke(width = BodyBorder.toPx()),
        )

        val selectedLeft = body.left + body.width * 0.45f * progress
        val selectedRight = body.right - body.width * 0.45f * (1f - progress)
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = if (checked) {
                    listOf(Color.Transparent, status.copy(alpha = 0.58f))
                } else {
                    listOf(status.copy(alpha = 0.58f), Color.Transparent)
                },
                startX = body.left,
                endX = body.right,
            ),
            topLeft = Offset(selectedLeft, body.top),
            size = androidx.compose.ui.geometry.Size(selectedRight - selectedLeft, body.height),
            cornerRadius = CornerRadius(bodyCorner),
        )

        val thumbHeight = ThumbHeight.toPx()
        val thumbWidth = ThumbWidth.toPx()
        val thumbRadius = thumbHeight / 2f
        val travel = body.width - thumbWidth
        val thumbLeft = body.left + travel * progress
        val thumb = Rect(
            left = thumbLeft,
            top = body.center.y - thumbHeight / 2f,
            right = thumbLeft + thumbWidth,
            bottom = body.center.y + thumbHeight / 2f,
        )

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(GlassHighlight, GlassMid, Color.Black),
                startY = thumb.top,
                endY = thumb.bottom,
            ),
            topLeft = thumb.topLeft,
            size = thumb.size,
            cornerRadius = CornerRadius(thumbRadius),
        )
        drawRoundRect(
            color = status.copy(alpha = 0.22f),
            topLeft = thumb.topLeft,
            size = thumb.size,
            cornerRadius = CornerRadius(thumbRadius),
            style = Stroke(width = ThumbGlow.toPx()),
        )
        drawRoundRect(
            color = status,
            topLeft = thumb.topLeft,
            size = thumb.size,
            cornerRadius = CornerRadius(thumbRadius),
            style = Stroke(width = ThumbEdge.toPx()),
        )
        drawLine(
            color = androidx.compose.ui.graphics.lerp(status, Color.White, 0.82f),
            start = Offset(thumb.center.x - thumb.width * 0.20f, thumb.center.y),
            end = Offset(thumb.center.x + thumb.width * 0.20f, thumb.center.y),
            strokeWidth = MarkerWidth.toPx(),
            cap = StrokeCap.Round,
        )

        val label = if (checked) onLabel else offLabel
        val labelCentreX = if (checked) {
            body.left + (thumb.left - body.left) / 2f
        } else {
            thumb.right + (body.right - thumb.right) / 2f
        }
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = status.toArgb()
                textSize = LabelTextSize.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawText(label, labelCentreX, body.center.y - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }
}

private val ComponentWidth = 82.dp
private val ComponentHeight = 35.dp
private val BodyInset = 3.dp
private val BodyBorder = 1.dp
private val ThumbWidth = 34.dp
private val ThumbHeight = 27.dp
private val ThumbGlow = 5.dp
private val ThumbEdge = 1.4.dp
private val MarkerWidth = 2.2.dp
private val LabelTextSize = 10.dp
private const val DisabledAlpha = 0.4f

private val GlassHighlight = Color(0xFF4B4E58)
private val GlassMid = Color(0xFF111318)
private val SmokedEdge = Color(0xFF363941)
