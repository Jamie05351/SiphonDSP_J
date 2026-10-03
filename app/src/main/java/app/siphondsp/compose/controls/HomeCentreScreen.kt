package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import app.siphondsp.view.HomeStageStatus
import app.siphondsp.view.LevelReadout
import java.util.Locale

/**
 * The front page's centre top screen: the headroom left before the limiter (or before clipping
 * while the limiter is off) on one line, over the MBC band squares and all-pass boxes
 * ([HomeStageBoxes]). Levels themselves are on the output scope at the end of the signal chain,
 * so they aren't repeated here.
 *
 * The two rows are one block, centred in the screen. It is laid out at the head unit's size
 * (18sp / 44sp text, 464dp wide) and scales down as a whole only when the screen is smaller than
 * that plus its margins, as on a phone, so nothing wraps or clips.
 *
 * [limiterDb] is the limiter threshold while the limiter is on, null while it is off.
 */
@Composable
fun HomeCentreScreen(
    readout: LevelReadout,
    limiterDb: Float?,
    stages: HomeStageStatus,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val k = minOf(
            1f,
            (maxWidth - MarginX * 2) / BlockWidth,
            (maxHeight - MarginY * 2) / (HeadroomHeight + RowGap + BoxesHeight),
        ).coerceAtLeast(0f)
        Column(Modifier.width(BlockWidth * k)) {
            Headroom(readout, limiterDb, k)
            Box(Modifier.padding(top = RowGap * k).size(BlockWidth * k, BoxesHeight * k)) {
                HomeStageBoxes(stages)
            }
        }
    }
}

/** "HEADROOM  7.4 dB  to clip", on one baseline. */
@Composable
private fun Headroom(readout: LevelReadout, limiterDb: Float?, k: Float) {
    val headroom = readout.headroomDb(limiterDb ?: 0f)
    val small = (SmallSp * k).sp
    Row(Modifier.size(BlockWidth * k, HeadroomHeight * k), verticalAlignment = Alignment.CenterVertically) {
        OneLine("HEADROOM", small, Color.White, FontWeight.Bold, Modifier.alignByBaseline())
        OneLine(
            if (headroom == null) "--" else "${formatDb(headroom)} dB",
            (BigSp * k).sp,
            headroomColor(headroom),
            FontWeight.Medium,
            Modifier.alignByBaseline().padding(start = (12 * k).dp),
        )
        // What the headroom is measured to.
        OneLine(
            if (limiterDb != null) "to limiter" else "to clip",
            small,
            SubColor,
            modifier = Modifier.alignByBaseline().padding(start = (10 * k).dp),
        )
    }
}

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
    // Fixed-width digits, so the number doesn't jiggle sideways as it changes.
    style = TextStyle(fontFeatureSettings = "tnum"),
    maxLines = 1,
    softWrap = false,
    modifier = modifier,
)

private fun formatDb(db: Float): String = String.format(Locale.ROOT, "%.1f", db).replace('-', '−')

/** Green with 3 dB or more to spare, amber under 3, red under 1; white with no signal. */
private fun headroomColor(db: Float?): Color = when {
    db == null -> Color.White
    db >= 3f -> Color(BmwDashboardSkin.TOGGLE_ON_GREEN)
    db >= 1f -> DspColors.Xover
    else -> DspColors.Comp
}

// The block at the head unit's size: the headroom line (one 44sp line), a gap, then the boxes at
// HomeStageBoxes' own design size.
private val BlockWidth = 464.dp
private val HeadroomHeight = 53.dp
private val RowGap = 10.dp
private val BoxesHeight = 66.dp
private val MarginX = 16.dp
private val MarginY = 8.dp
private const val SmallSp = 18f
private const val BigSp = 44f
private val SubColor = Color(0xFFE6E7E8)
