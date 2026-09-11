package app.siphondsp.compose.controls

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.siphondsp.view.BmwDashboardSkin
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val Fmt = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
private val MiniLabelWidth = 46.dp // ROW_LABEL_WIDTH_DP
private val MiniLabelColor = Color(0xFF969EA8) // rgb(150, 158, 168)
private val PolNormalGreen = Color(BmwDashboardSkin.M_GREEN)
private val PolInvertRed = Color(BmwDashboardSkin.M_RED)

/**
 * Compose port of `CrossoverDashboardBuilder.addChannelCard` -- one Gains & Delay channel card:
 * a transparent [strokeColor]-bordered box with a title and DELAY (tap-to-type only), POL
 * (NORMAL/INVERT segmented) rows, and -- Left Low only -- a LINK L/R delay switch. GAIN used to
 * be a third row here; it now lives on the [ArcGainSlider] wrapped around this channel's speaker
 * in the car diagram instead (see GainsDelayScreen).
 *
 * All state is hoisted. Delay is tap-only (the View's "show where a delay applies, not fine
 * adjustment" rationale); the caller mirrors it onto the sibling card's index when the LINK
 * toggle is on -- with the shared [app.siphondsp.compose.state.BmwDspState] snapshot the sibling
 * card just recomposes, no rebuild.
 */
@Composable
fun BmwChannelCard(
    title: String,
    accentColor: Color,
    strokeColor: Color,
    delayValue: Float,
    delayRange: ClosedFloatingPointRange<Float>,
    onDelayCommit: (Float) -> Unit,
    polarityInverted: Boolean,
    onPolarityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    linkChecked: Boolean? = null,
    onLinkChange: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .border(1.dp, strokeColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text = title, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniLabel("DELAY")
            BoxedValue(
                text = Fmt.format(delayValue),
                unit = "ms",
                accentColor = accentColor,
                modifier = Modifier
                    .weight(1f)
                    .height(RowBoxHeight)
                    .clickable {
                        context.showBmwNumberInput(
                            "DELAY", delayRange.start, delayRange.endInclusive, delayValue, 0f, "ms", onDelayCommit,
                        )
                    },
            )
        }
        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniLabel("POL")
            BmwSegmentedControl(
                options = listOf("NORMAL", "INVERT"),
                selectedIndex = if (polarityInverted) 1 else 0,
                onSelect = { onPolarityChange(it == 1) },
                optionAccents = listOf(PolNormalGreen, PolInvertRed),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
        }
        if (linkChecked != null && onLinkChange != null) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniLabel("LINK L/R")
                BmwSwitch(
                    checked = linkChecked,
                    onCheckedChange = onLinkChange,
                    contentDescription = "Link L/R delay",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniLabel(text: String) {
    Text(
        text = text,
        color = MiniLabelColor,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.03.em,
        modifier = Modifier.width(MiniLabelWidth),
    )
}

