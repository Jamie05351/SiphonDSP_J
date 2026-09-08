package app.siphondsp.compose.controls

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin

/**
 * Faithful Compose recreation of the app's hand-painted slider chrome -- see
 * BmwSkinDrawables.kt's SliderCapsuleDrawable and SliderThumbDrawable in the View system, which
 * this mirrors dimension-for-dimension and color-for-color. Built on Material3's [Slider] using
 * its `track`/`thumb` slots rather than a from-scratch drag implementation, so gesture handling,
 * accessibility, and RTL support all stay Material3's -- only the paint changes, same division of
 * responsibility as the View version (MDC's Slider + a custom background/thumb Drawable).
 *
 * [accentColor] recolors the capsule border, active fill (lightened, same 0.2 blend-to-white the
 * View version uses), and thumb gradient/inset/ticks -- pass one of [app.siphondsp.compose.theme.BmwTheme]'s
 * `slider*` colors (sliderLowBand, sliderMidBand, sliderHeadroom, sliderTilt, sliderDefault) to
 * match the row's band.
 */
@Composable
fun BmwSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    accentColor: Color,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        modifier = modifier.fillMaxWidth(),
        interactionSource = interactionSource,
        colors = SliderDefaults.colors(),
        track = { sliderState -> BmwSliderTrack(sliderState = sliderState, accentColor = accentColor, focused = isFocused) },
        thumb = { BmwSliderThumb(accentColor = accentColor) },
    )
}

// Dimensions, 1:1 with BmwDashboardSkin's SLIDER_* dp constants.
private val CapsuleHeight = 18.dp
private val CapsuleBorderWidth = 1.dp
private val GrooveMargin = 4.75.dp
private val GrooveStrokeWidth = 1.dp
private val GrooveHighlightMargin = 1.5.dp
private val ActiveFillHeight = 6.5.dp
private val ThumbWidth = 36.dp
private val ThumbHeight = 18.dp
private val ThumbCornerRadius = 7.5.dp
private val ThumbBorderWidth = 1.dp
private val ThumbInsetMargin = 5.25.dp
private val ThumbInsetCornerRadius = 3.75.dp
private val ThumbInsetBorderWidth = 1.dp
private const val TickCount = 3
private val TickLength = 2.dp
private val TickSpacing = 5.dp
private val TickStrokeWidth = 0.75.dp

// Colors, 1:1 with BmwDashboardSkin's SLIDER_GROOVE_*/BOX_BACKGROUND/THUMB_* constants (the
// neutral ones that don't vary by accent -- accent-derived colors are computed inline below,
// same blend() calls the View version makes).
private val CapsuleFillColor = Color(0xFF101318)
private val GrooveFillColor = Color(0xFF070707)
private val GrooveStrokeColor = Color(0xFF2B2B2B)
private val GrooveHighlightColor = Color(0xFF262628)
private val ThumbBorderColor = Color(0xFF7A7A7A)
private val ThumbHighlightColor = Color(0x99C5C8CB)
private val ThumbShadowColor = Color(0x8C000000)
private val ThumbInsetBorderColor = Color(0xFF111317)

/** [Rect] has no public inset-by-delta constructor usable across Compose UI versions without
 *  checking availability -- this local helper avoids that uncertainty entirely. */
private fun Rect.insetBy(amount: Float) = Rect(left + amount, top + amount, right - amount, bottom - amount)

@Composable
private fun BmwSliderTrack(sliderState: SliderState, accentColor: Color, focused: Boolean) {
    val activeFillColor = lerp(accentColor, Color.White, 0.2f)
    val focusColor = Color(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)

    Box(modifier = Modifier.fillMaxWidth().height(CapsuleHeight)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(CapsuleHeight)) {
            val borderPx = CapsuleBorderWidth.toPx()
            val capsuleRect = Rect(0f, 0f, size.width, size.height).insetBy(borderPx / 2f)
            val capsuleRadius = capsuleRect.height / 2f

            drawRoundRect(
                color = CapsuleFillColor,
                topLeft = Offset(capsuleRect.left, capsuleRect.top),
                size = Size(capsuleRect.width, capsuleRect.height),
                cornerRadius = CornerRadius(capsuleRadius),
            )

            val grooveRect = capsuleRect.insetBy(GrooveMargin.toPx())
            val grooveRadius = grooveRect.height / 2f
            drawRoundRect(
                color = GrooveFillColor,
                topLeft = Offset(grooveRect.left, grooveRect.top),
                size = Size(grooveRect.width, grooveRect.height),
                cornerRadius = CornerRadius(grooveRadius),
            )
            drawRoundRect(
                color = GrooveStrokeColor,
                topLeft = Offset(grooveRect.left, grooveRect.top),
                size = Size(grooveRect.width, grooveRect.height),
                cornerRadius = CornerRadius(grooveRadius),
                style = Stroke(width = GrooveStrokeWidth.toPx()),
            )
            val grooveHighlightRect = grooveRect.insetBy(GrooveHighlightMargin.toPx())
            if (grooveHighlightRect.width > 0f && grooveHighlightRect.height > 0f) {
                drawRoundRect(
                    color = GrooveHighlightColor,
                    topLeft = Offset(grooveHighlightRect.left, grooveHighlightRect.top),
                    size = Size(grooveHighlightRect.width, grooveHighlightRect.height),
                    cornerRadius = CornerRadius(grooveHighlightRect.height / 2f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            val fraction = ((sliderState.value - sliderState.valueRange.start) /
                (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
            val fillHeightPx = ActiveFillHeight.toPx()
            val fillTop = size.height / 2f - fillHeightPx / 2f
            val fillWidth = grooveRect.width * fraction
            if (fillWidth > 0f) {
                drawRoundRect(
                    color = activeFillColor,
                    topLeft = Offset(grooveRect.left, fillTop),
                    size = Size(fillWidth, fillHeightPx),
                    cornerRadius = CornerRadius(fillHeightPx / 2f),
                )
            }

            if (!focused) {
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(capsuleRect.left, capsuleRect.top),
                    size = Size(capsuleRect.width, capsuleRect.height),
                    cornerRadius = CornerRadius(capsuleRadius),
                    style = Stroke(width = borderPx),
                )
            }
        }

        if (focused) {
            val glowModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.graphicsLayer { renderEffect = BlurEffect(4.dp.toPx(), 4.dp.toPx(), TileMode.Decal) }
            } else {
                Modifier
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(CapsuleHeight).then(glowModifier)) {
                drawFocusRing(focusColor, CapsuleBorderWidth.toPx())
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(CapsuleHeight)) {
                drawFocusRing(focusColor, CapsuleBorderWidth.toPx())
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusRing(color: Color, borderPx: Float) {
    val rect = Rect(0f, 0f, size.width, size.height).insetBy(borderPx / 2f)
    drawRoundRect(
        color = color,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = CornerRadius(rect.height / 2f),
        style = Stroke(width = borderPx),
    )
}

@Composable
private fun BmwSliderThumb(accentColor: Color) {
    val gradientTop = lerp(accentColor, Color.White, 0.35f)
    val gradientCenter = accentColor
    val gradientBottom = lerp(accentColor, Color.Black, 0.45f)
    val insetColor = lerp(accentColor, Color.Black, 0.25f)

    Canvas(modifier = Modifier.size(width = ThumbWidth, height = ThumbHeight)) {
        val borderPx = ThumbBorderWidth.toPx()
        val cornerPx = ThumbCornerRadius.toPx()
        val bodyRect = Rect(0f, 0f, size.width, size.height).insetBy(borderPx / 2f)

        val brush = Brush.verticalGradient(
            colors = listOf(gradientTop, gradientCenter, gradientBottom),
            startY = bodyRect.top,
            endY = bodyRect.bottom,
        )
        val clip = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(bodyRect, CornerRadius(cornerPx)))
        }
        clipPath(clip) {
            drawRoundRect(
                brush = brush,
                topLeft = Offset(bodyRect.left, bodyRect.top),
                size = Size(bodyRect.width, bodyRect.height),
                cornerRadius = CornerRadius(cornerPx),
            )
            val edgePx = 1.dp.toPx()
            drawRect(color = ThumbHighlightColor, topLeft = Offset(bodyRect.left, bodyRect.top), size = Size(bodyRect.width, edgePx))
            drawRect(color = ThumbShadowColor, topLeft = Offset(bodyRect.left, bodyRect.bottom - edgePx), size = Size(bodyRect.width, edgePx))
        }
        drawRoundRect(
            color = ThumbBorderColor,
            topLeft = Offset(bodyRect.left, bodyRect.top),
            size = Size(bodyRect.width, bodyRect.height),
            cornerRadius = CornerRadius(cornerPx),
            style = Stroke(width = borderPx),
        )

        val insetRect = bodyRect.insetBy(ThumbInsetMargin.toPx())
        if (insetRect.width > 0f && insetRect.height > 0f) {
            val insetCornerPx = ThumbInsetCornerRadius.toPx()
            drawRoundRect(
                color = insetColor,
                topLeft = Offset(insetRect.left, insetRect.top),
                size = Size(insetRect.width, insetRect.height),
                cornerRadius = CornerRadius(insetCornerPx),
            )
            drawRoundRect(
                color = ThumbInsetBorderColor,
                topLeft = Offset(insetRect.left, insetRect.top),
                size = Size(insetRect.width, insetRect.height),
                cornerRadius = CornerRadius(insetCornerPx),
                style = Stroke(width = ThumbInsetBorderWidth.toPx()),
            )

            val cx = bodyRect.center.x
            val topBandMid = (bodyRect.top + insetRect.top) / 2f
            val bottomBandMid = (insetRect.bottom + bodyRect.bottom) / 2f
            val tickLenPx = TickLength.toPx()
            val tickSpacingPx = TickSpacing.toPx()
            if (insetRect.top - bodyRect.top >= tickLenPx + 1.dp.toPx()) {
                val firstX = cx - tickSpacingPx * (TickCount - 1) / 2f
                repeat(TickCount) { i ->
                    val x = firstX + i * tickSpacingPx
                    drawLine(
                        color = accentColor,
                        start = Offset(x, topBandMid - tickLenPx / 2f),
                        end = Offset(x, topBandMid + tickLenPx / 2f),
                        strokeWidth = TickStrokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = accentColor,
                        start = Offset(x, bottomBandMid - tickLenPx / 2f),
                        end = Offset(x, bottomBandMid + tickLenPx / 2f),
                        strokeWidth = TickStrokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
