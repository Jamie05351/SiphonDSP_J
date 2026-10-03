package app.siphondsp.compose.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.HomeStageStatus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The centre screen's stage boxes, under the level readout: the MBC's four bands as numbered
 * squares, lit green while that band is compressing, then one box per output for its all-pass
 * sections, lit green with each switched-on section's frequency in it.
 *
 * Laid out at the head unit's size and scaled down together only when the box is smaller (a
 * phone), so nothing wraps or clips. Purely visual; the values change only when settings do.
 */
@Composable
fun HomeStageBoxes(status: HomeStageStatus, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val k = minOf(1f, maxWidth / DesignWidth, maxHeight / DesignHeight)
        Row(horizontalArrangement = Arrangement.spacedBy(GroupGap * k)) {
            Group("MBC", k) {
                status.mbcBands.forEachIndexed { band, on ->
                    StageBox(on, MbcBoxWidth * k, caption = null, k) {
                        BoxText("${band + 1}", (14f * k).sp, on, FontWeight.Bold)
                    }
                }
            }
            Group("ALL-PASS", k) {
                status.allPass.forEach { output ->
                    StageBox(output.on, AllPassBoxWidth * k, caption = output.label, k) {
                        if (output.on) {
                            val size = if (output.frequenciesHz.size > 1) 9.5f else 11f
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                output.frequenciesHz.forEach { BoxText(formatHz(it), (size * k).sp, on = true) }
                            }
                        } else {
                            BoxText("off", (10f * k).sp, on = false)
                        }
                    }
                }
            }
        }
    }
}

/** A heading over a row of boxes. */
@Composable
private fun Group(title: String, k: Float, boxes: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            color = Color.White,
            fontSize = (HeadingSp * k).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(bottom = 4.dp * k),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(BoxGap * k)) { boxes() }
    }
}

/** One box: green and glowing while [on], dark glass while off, with an optional caption under it. */
@Composable
private fun StageBox(on: Boolean, width: Dp, caption: String?, k: Float, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.dp * k)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, BoxHeight * k)
                .drawBehind {
                    if (on) {
                        // A soft glow just outside the box, like a lit LED behind glass.
                        val g = 3.dp.toPx() * k
                        drawRoundRect(
                            Lit.copy(alpha = 0.3f),
                            topLeft = Offset(-g, -g),
                            size = Size(size.width + 2 * g, size.height + 2 * g),
                            cornerRadius = CornerRadius(6.dp.toPx() * k + g),
                        )
                    }
                }
                .background(if (on) Lit else Color.White.copy(alpha = 0.06f), shape)
                .border(1.dp, if (on) LitEdge else Color.White.copy(alpha = 0.2f), shape),
            contentAlignment = Alignment.Center,
        ) { content() }
        if (caption != null) {
            Text(
                text = caption,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = (CaptionSp * k).sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(top = 2.dp * k),
            )
        }
    }
}

@Composable
private fun BoxText(text: String, size: TextUnit, on: Boolean, weight: FontWeight = FontWeight.Medium) = Text(
    text = text,
    color = if (on) LitText else Color.White.copy(alpha = 0.45f),
    fontSize = size,
    fontWeight = weight,
    textAlign = TextAlign.Center,
    // Fixed-width digits, so a box's text doesn't shift as the frequency changes.
    style = TextStyle(fontFeatureSettings = "tnum"),
    maxLines = 1,
    softWrap = false,
)

/** "80 Hz" below 1 kHz, "1.2 kHz" (or "2 kHz") above. */
internal fun formatHz(hz: Float): String =
    if (hz < 1000f) {
        "${hz.roundToInt()} Hz"
    } else {
        String.format(Locale.ROOT, "%.1f", hz / 1000f).removeSuffix(".0") + " kHz"
    }

// The block at the head unit's size: two headed rows of boxes with captions under the all-pass ones.
// 4 x 30 + 3 x 6, a 20 gap, then 6 x 46 + 5 x 6: 464dp, the centre screen's inner width.
private val DesignWidth = 464.dp
private val DesignHeight = 66.dp
private val GroupGap = 20.dp
private val BoxGap = 6.dp
private val BoxHeight = 32.dp
private val MbcBoxWidth = 30.dp
private val AllPassBoxWidth = 46.dp
private const val HeadingSp = 12f
private const val CaptionSp = 9f

private val Lit = Color(BmwDashboardSkin.TOGGLE_ON_GREEN)
private val LitEdge = Color(0xFFB9FFA8)
private val LitText = Color(0xFF0B1A08)
