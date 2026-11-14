package app.siphondsp.compose.controls

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's on/off switch: a flat filled pill with its state written on it. ON is a green fill
 * with dark text, OFF a grey fill with muted text, so the state reads at a glance from across the
 * cabin. No thumb, no track: the whole pill is the control, and a tap flips it (the fill crossfades
 * over 160 ms).
 *
 * Callers such as polarity, where both positions are valid settings rather than on/off, pass their
 * own [onColor]/[offColor] and labels: each position is then a fill of its own colour, with text
 * in a dark shade of it. Disabled draws the same pill at [DisabledAlpha].
 */
@Composable
fun BmwSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onColor: Color = OnGreen,
    offColor: Color = OffGrey,
    onLabel: String = "ON",
    offLabel: String = "OFF",
    width: Dp = ComponentWidth,
) {
    val fill by animateColorAsState(
        targetValue = if (checked) onColor else offColor,
        animationSpec = tween(durationMillis = 160),
        label = "bmwSwitchFill",
    )
    val text = when {
        !checked && offColor == OffGrey -> OffText
        else -> lerp(if (checked) onColor else offColor, Color.Black, TextDarkening)
    }
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
            .bmwFocusRing(interactionSource, cornerRadius = PillHeight / 2)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        // Art rects can be shorter than the 48dp touch height; shrink the pill to fit.
        val h = minOf(PillHeight.toPx(), size.height - 2.dp.toPx())
        val top = (size.height - h) / 2f
        drawRoundRect(
            color = fill,
            topLeft = Offset(0f, top),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(h / 2f),
        )
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = text.toArgb()
                textSize = LabelTextSize.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val cy = size.height / 2f
            drawText(if (checked) onLabel else offLabel, size.width / 2f, cy - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }
}

private val ComponentWidth = 72.dp
private val ComponentHeight = CarUi.MinTouch
private val PillHeight = 30.dp
private val LabelTextSize = 14.dp
private const val DisabledAlpha = 0.4f
private const val TextDarkening = 0.82f

private val OnGreen = Color(0xFF2FBF4A)
private val OffGrey = Color(0xFF3A3D44)
private val OffText = Color(0xFF8A8F98)
