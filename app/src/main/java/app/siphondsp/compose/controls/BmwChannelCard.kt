package app.siphondsp.compose.controls

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.siphondsp.view.BmwDashboardSkin
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

private val Fmt = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
private val MiniLabelWidth = 52.dp
private val MiniLabelColor = Color(0xFF969EA8) // rgb(150, 158, 168)
private val PolNormalGreen = Color(BmwDashboardSkin.M_GREEN)
// Pink, not the app's usual invert-red -- shares SLIDER_STAGE_COLOR with the stage-timing sliders
// on this same screen so the "something is offset from normal" cue reads consistently.
private val PolInvertPink = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)
private val PolaritySwitchWidth = 132.dp

/**
 * Compose port of `CrossoverDashboardBuilder.addChannelCard` -- one Gains & Delay channel card:
 * a transparent [strokeColor]-bordered box with a title and compact DELAY (tap-to-type only),
 * POL (a [BmwSwitch] recoloured green/pink for normal/invert), and a full-width brushed-metal
 * GAIN slider with a single-line value.
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
    gainValue: Float,
    gainRange: ClosedFloatingPointRange<Float>,
    gainStep: Float,
    gainSliderAccent: Color,
    onGainPreview: (Float) -> Unit,
    onGainCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier
            .background(CardBackground, cardShape)
            .border(1.dp, strokeColor, cardShape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = title,
            color = accentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = if (mirrored) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(3.dp))

        val delayBox: @Composable () -> Unit = {
            BoxedValue(
                text = Fmt.format(delayValue), unit = "ms", accentColor = accentColor,
                modifier = Modifier.width(DelayValueWidth).height(RowBoxHeight).clickable {
                    context.showBmwNumberInput(
                        "DELAY", delayRange.start, delayRange.endInclusive, delayValue, 0f, "ms", onDelayCommit,
                    )
                },
            )
        }
        val polaritySwitch: @Composable () -> Unit = {
            BmwSwitch(
                checked = polarityInverted,
                onCheckedChange = onPolarityChange,
                contentDescription = "$title polarity",
                onColor = PolInvertPink,
                offColor = PolNormalGreen,
                onLabel = "INVERT",
                offLabel = "NORMAL",
                width = PolaritySwitchWidth,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mirrored) {
                polaritySwitch()
                Spacer(Modifier.weight(1f))
                delayBox()
                Spacer(Modifier.width(6.dp))
                MiniLabel("DELAY", TextAlign.End)
            } else {
                MiniLabel("DELAY")
                delayBox()
                Spacer(Modifier.weight(1f))
                polaritySwitch()
            }
        }

        GainRow(
            value = gainValue,
            range = gainRange,
            step = gainStep,
            labelColor = accentColor,
            sliderAccent = gainSliderAccent,
            onPreview = onGainPreview,
            onCommit = onGainCommit,
            mirrored = mirrored,
        )

    }
}

@Composable
private fun GainRow(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    labelColor: Color,
    sliderAccent: Color,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    mirrored: Boolean,
) {
    val context = LocalContext.current
    var drag by remember(value) { mutableFloatStateOf(value) }
    val shown = drag.coerceIn(range.start, range.endInclusive)

    Column(modifier = Modifier.fillMaxWidth()) {
        val slider: @Composable () -> Unit = {
            BmwSlider(
            value = shown,
            valueRange = range,
            steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0),
            accentColor = sliderAccent,
            onValueChange = {
                val snapped = snapGain(it, range, step)
                drag = snapped
                onPreview(snapped)
            },
            onValueChangeFinished = { onCommit(drag) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val valueBox: @Composable () -> Unit = {
            BoxedValue(
                text = Fmt.format(shown), unit = "dB", accentColor = labelColor,
                modifier = Modifier.width(GainValueWidth).height(RowBoxHeight).clickable {
                    context.showBmwNumberInput("GAIN", range.start, range.endInclusive, drag, step, "dB") {
                        drag = it
                        onCommit(it)
                    }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = RowBoxHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mirrored) {
                valueBox()
                Spacer(Modifier.weight(1f))
                MiniLabel("GAIN", TextAlign.End)
            } else {
                MiniLabel("GAIN")
                Spacer(Modifier.weight(1f))
                valueBox()
            }
        }
        slider()
    }
}

private fun snapGain(
    raw: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
): Float {
    if (step <= 0f) return raw.coerceIn(range.start, range.endInclusive)
    val snapped = range.start + ((raw - range.start) / step).roundToInt() * step
    return snapped.coerceIn(range.start, range.endInclusive)
}

@Composable
private fun MiniLabel(text: String, textAlign: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        color = MiniLabelColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.03.em,
        textAlign = textAlign,
        modifier = Modifier.width(MiniLabelWidth),
    )
}

private val DelayValueWidth = 82.dp
private val GainValueWidth = 76.dp
private val CardBackground = Color(0x99100818)
