package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.LevelReadout
import java.util.Locale

/**
 * The front page's top-screen level readout: big L and R RMS numbers with each channel's held peak
 * under it, and the headroom left before the limiter (or before clipping while the limiter is off).
 * The block is centred vertically in its box. Text is 18sp / 44sp at the head unit's size and
 * scales down together only when the box is smaller than that (a small phone), so nothing wraps or
 * clips.
 *
 * [limiterDb] is the limiter threshold while the limiter is on, null while it is off.
 */
@Composable
fun HomeLevelReadout(readout: LevelReadout, limiterDb: Float?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val k = minOf(1f, maxWidth / DesignWidth, maxHeight / DesignHeight)
        val small = (SmallSp * k).sp
        val big = (BigSp * k).sp
        val headroom = readout.headroomDb(limiterDb ?: 0f)
        // Centred vertically, so the block sits on the top screen's centre line.
        Row(
            Modifier.fillMaxWidth().align(Alignment.CenterStart),
            horizontalArrangement = Arrangement.spacedBy((12 * k).dp),
        ) {
            Column(Modifier.weight(2f)) {
                Heading("LEVELS", small)
                Row {
                    Channel("L", readout.leftRmsDb, readout.leftPeakDb, small, big, k, Modifier.weight(1f))
                    Channel("R", readout.rightRmsDb, readout.rightPeakDb, small, big, k, Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(1.1f)) {
                Heading("HEADROOM", small)
                OneLine(
                    if (headroom == null) "--" else "${formatDb(headroom)} dB",
                    big,
                    headroomColor(headroom),
                    FontWeight.Medium,
                )
                OneLine(if (limiterDb != null) "before limiter" else "before clipping", small, SubColor)
            }
        }
    }
}

@Composable
private fun Channel(
    name: String,
    rmsDb: Float,
    peakDb: Float,
    small: TextUnit,
    big: TextUnit,
    k: Float,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OneLine(name, small, Purple, FontWeight.Bold, Modifier.padding(end = (8 * k).dp))
            OneLine(levelText(rmsDb), big, Color.White, FontWeight.Medium)
        }
        OneLine("peak ${levelText(peakDb)}", small, SubColor, modifier = Modifier.padding(start = (24 * k).dp))
    }
}

@Composable
private fun Heading(text: String, size: TextUnit) =
    OneLine(text, size, Color.White, FontWeight.Bold, Modifier.padding(bottom = 4.dp))

@Composable
private fun OneLine(
    text: String,
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) = Text(
    text = text,
    color = color,
    fontSize = size,
    fontWeight = weight,
    // Fixed-width digits, so the numbers don't jiggle sideways as they change.
    style = TextStyle(fontFeatureSettings = "tnum"),
    maxLines = 1,
    softWrap = false,
    modifier = modifier,
)

/** "−12.4" (a real minus sign), or "--" at the meter's floor. */
private fun levelText(db: Float): String = if (db <= LevelReadout.FLOOR_DB) "--" else formatDb(db)

private fun formatDb(db: Float): String = String.format(Locale.ROOT, "%.1f", db).replace('-', '−')

/** Green with 3 dB or more to spare, amber under 3, red under 1; white with no signal. */
private fun headroomColor(db: Float?): Color = when {
    db == null -> Color.White
    db >= 3f -> Color(BmwDashboardSkin.TOGGLE_ON_GREEN)
    db >= 1f -> DspColors.Xover
    else -> DspColors.Comp
}

private val DesignWidth = 531.dp
private val DesignHeight = 130.dp
private const val SmallSp = 18f
private const val BigSp = 44f
private val Purple = Color(0xFFB14DFF)
private val SubColor = Color(0xFFE6E7E8)
