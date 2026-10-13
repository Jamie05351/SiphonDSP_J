package app.siphondsp.compose.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.BmwSwitch
import app.siphondsp.compose.controls.BoxedValue
import app.siphondsp.compose.controls.CarSpeakerDiagram
import app.siphondsp.compose.controls.MinusPlusPill
import app.siphondsp.compose.controls.SpeakerKind
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.controls.bmwFocusRing
import app.siphondsp.compose.controls.showBmwNumberInput
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ThreeWayCrossover
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.roundToInt

/**
 * The three crossover bands of the 3-way system (Phase 5 of the 3-way crossover,
 * docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md), each with the DSP indices for its Left/Right output,
 * its colours and the kind of driver it feeds ([speaker]).
 *
 * High only makes sound with the Crossovers page's 3-way switch on; with it off the High band's
 * controls are greyed out and inert, with a pointer to where 3-way is turned on.
 */
enum class GainsBand(
    val title: String,
    val leftOutput: Int,
    val rightOutput: Int,
    val gainL: Int,
    val gainR: Int,
    val delayL: Int,
    val delayR: Int,
    val accent: Int,
    val stroke: Int,
    val slider: Int,
    val speaker: SpeakerKind,
) {
    HIGH(
        "High", NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.OUTPUT_HIGH_RIGHT,
        NativeBmwDspValues.INDEX_HIGH_GAIN_L, NativeBmwDspValues.INDEX_HIGH_GAIN_R,
        NativeBmwDspValues.INDEX_HIGH_DELAY_L, NativeBmwDspValues.INDEX_HIGH_DELAY_R,
        BmwDashboardSkin.HIGH_BAND_PINK, BmwDashboardSkin.HIGH_BAND_PINK, BmwDashboardSkin.SLIDER_HIGH_BAND_COLOR,
        SpeakerKind.TWEETER,
    ),
    MID(
        "Mid", NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.OUTPUT_MID_RIGHT,
        NativeBmwDspValues.INDEX_MID_GAIN_L, NativeBmwDspValues.INDEX_MID_GAIN_R,
        NativeBmwDspValues.INDEX_MID_DELAY_L, NativeBmwDspValues.INDEX_MID_DELAY_R,
        BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR,
        SpeakerKind.MID,
    ),
    LOW(
        "Low", NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.OUTPUT_LOW_RIGHT,
        NativeBmwDspValues.INDEX_LOW_GAIN_L, NativeBmwDspValues.INDEX_LOW_GAIN_R,
        NativeBmwDspValues.INDEX_LOW_DELAY_L, NativeBmwDspValues.INDEX_LOW_DELAY_R,
        BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.M_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR,
        SpeakerKind.WOOFER,
    );

    /** High's per-output config lives in the schema tail, not the legacy 4-output block. */
    fun polarityIndex(output: Int): Int =
        if (this == HIGH) NativeBmwDspValues.highOutputIndex(output, NativeBmwDspValues.FIELD_INVERT)
        else NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)

    companion object {
        /** The band whose drivers are of this kind (tweeters = High, mids = Mid, woofers = Low). */
        fun forSpeaker(kind: SpeakerKind): GainsBand = entries.first { it.speaker == kind }
    }
}

/**
 * The Gains & Delay "Delay" page: the per-driver controls you adjust while tuning, one band at a
 * time. The time-alignment setup (the speaker map, PATH distances, ALIGN and "Apply to delays") has
 * its own page, [SpeakerAlignScreen], so this one has room for large controls.
 *
 * - **Band**: the High / Mid / Low tabs; the value boxes take the band's colour.
 * - **Cards** (Left and Right for the chosen band): DELAY, GAIN and POLARITY, mirrored round the
 *   middle of the page (head unit: labels outside, controls inside) with no panel of their own.
 * - **Backdrop** (head unit): the car with the band's drivers picked out, a static picture of what
 *   the cards control (the tappable map is on ALIGN).
 * - **Stereo link** stays global.
 *
 * Stage alignment is no longer shown (the virtual centre does that job); a stored stage delay
 * still applies in the engine.
 */
@Composable
fun GainsDelayScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    var bandIndex by rememberSaveable { mutableIntStateOf(GainsBand.MID.ordinal) }
    val band = GainsBand.entries[bandIndex.coerceIn(0, GainsBand.entries.size - 1)]
    val linked = dsp.isOn(NativeBmwDspValues.INDEX_DELAY_LINKED)
    val inactive = band == GainsBand.HIGH && !ThreeWayCrossover.isEnabled(dsp.values)
    val setBand: (GainsBand) -> Unit = { bandIndex = it.ordinal }
    val headUnit = LocalContext.current.isHeadUnitDisplay()

    BmwDspTheme {
        if (headUnit) {
            HeadUnitDelayPage(dsp, band, setBand, linked, inactive, modifier)
        } else {
            PhoneDelayPage(dsp, band, setBand, linked, inactive, modifier)
        }
    }
}

// ---------------------------------------------------------------- head unit (1280x480 art rects)

@Composable
private fun HeadUnitDelayPage(
    dsp: BmwDspState,
    band: GainsBand,
    setBand: (GainsBand) -> Unit,
    linked: Boolean,
    inactive: Boolean,
    modifier: Modifier,
) {
    WorkspaceArtBox(modifier.fillMaxSize()) {
        // Drawn first so everything else sits over it. White, not the band colour: the drivers
        // show as themselves, lit against the dimmed car.
        CarSpeakerDiagram(
            selected = band.speaker,
            accent = Color.White,
            onSelect = {},
            modifier = Modifier.artRect(CarRect),
            interactive = false,
            carAlpha = CarAlpha,
        )
        BandTabs(band, setBand, Modifier.artRect(TabsRect))
        if (inactive) ThreeWayOffNote(band, Modifier.artRect(NoteRect))
        DriverCard(dsp, band, left = true, mirrored = false, linked, inactive, LargeSizing, fill = true, Modifier.artRect(LeftCardRect))
        DriverCard(dsp, band, left = false, mirrored = true, linked, inactive, LargeSizing, fill = true, Modifier.artRect(RightCardRect))
        StereoLinkRow(dsp, linked, true, Modifier.artRect(LinkRect))
    }
}

// ---------------------------------------------------------------- phone (scrolling column)

@Composable
private fun PhoneDelayPage(
    dsp: BmwDspState,
    band: GainsBand,
    setBand: (GainsBand) -> Unit,
    linked: Boolean,
    inactive: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 560.dp
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BandTabs(band, setBand, Modifier.fillMaxWidth().height(44.dp))
            if (inactive) ThreeWayOffNote(band, Modifier.fillMaxWidth())
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    DriverCard(dsp, band, true, mirrored = false, linked, inactive, PhoneSizing, fill = false, Modifier.weight(1f))
                    DriverCard(dsp, band, false, mirrored = true, linked, inactive, PhoneSizing, fill = false, Modifier.weight(1f))
                }
            } else {
                DriverCard(dsp, band, true, mirrored = false, linked, inactive, PhoneSizing, fill = false, Modifier.fillMaxWidth())
                DriverCard(dsp, band, false, mirrored = false, linked, inactive, PhoneSizing, fill = false, Modifier.fillMaxWidth())
            }
            StereoLinkRow(dsp, linked, false, Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- pieces

/** The High / Mid / Low tabs, each in its band colour. Shared with the ALLPASS page. */
@Composable
internal fun BandTabs(band: GainsBand, onSelect: (GainsBand) -> Unit, modifier: Modifier) {
    BmwSegmentedControl(
        options = GainsBand.entries.map { it.title },
        selectedIndex = band.ordinal,
        onSelect = { onSelect(GainsBand.entries[it]) },
        optionAccents = GainsBand.entries.map { Color(it.accent) },
        modifier = modifier,
        segmentHeight = 40.dp,
        segmentGap = 6.dp,
    )
}

@Composable
private fun ThreeWayOffNote(band: GainsBand, modifier: Modifier) {
    Text(
        text = "3-way is off: turn it on in Crossovers",
        color = Color(band.accent),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun StereoLinkRow(dsp: BmwDspState, linked: Boolean, headUnit: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(
            text = "STEREO LINK",
            color = LabelColor,
            fontSize = if (headUnit) 18.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.03.em,
            modifier = Modifier.padding(end = 12.dp),
        )
        BmwSwitch(
            checked = linked,
            onCheckedChange = { dsp.commit(NativeBmwDspValues.INDEX_DELAY_LINKED, if (it) 1f else 0f) },
            contentDescription = "Link left and right speaker delay",
        )
    }
}

/**
 * Left or Right card for [band]: a LEFT / RIGHT heading over DELAY, GAIN and POLARITY, with no
 * panel behind it. [mirrored] puts the labels on the right and the controls on the left (the
 * head unit's Right card, so the two cards face each other). With [fill] (head unit) it fills its
 * art rect and spreads the rows; otherwise (phone) it wraps.
 */
@Composable
private fun DriverCard(
    dsp: BmwDspState,
    band: GainsBand,
    left: Boolean,
    mirrored: Boolean,
    linked: Boolean,
    inactive: Boolean,
    sizing: DelaySizing,
    fill: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val accent = Color(band.accent)
    val output = if (left) band.leftOutput else band.rightOutput
    val delayIndex = if (left) band.delayL else band.delayR
    val siblingIndex = if (left) band.delayR else band.delayL
    val gainIndex = if (left) band.gainL else band.gainR
    val polarityIndex = band.polarityIndex(output)
    val delayMirror = if (linked) intArrayOf(siblingIndex) else IntArray(0)
    // Names the side and band in each −/+ button's spoken label ("Increase Left Mid delay"): the
    // card heading isn't merged into the buttons, so without it both cards would sound identical.
    val cardName = "${if (left) "Left" else "Right"} ${band.title}"
    val edge = if (mirrored) Alignment.End else Alignment.Start

    Box(modifier) {
        Column(
            modifier = (if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .then(if (inactive) Modifier.alpha(InactiveAlpha) else Modifier)
                // D-pad / rotary can't enter a dimmed card either; the overlay below only stops taps.
                .focusProperties { onEnter = { if (inactive) cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = if (fill) Arrangement.SpaceEvenly else Arrangement.spacedBy(8.dp),
            horizontalAlignment = edge,
        ) {
            Column(horizontalAlignment = edge) {
                Text(
                    text = if (left) "LEFT" else "RIGHT",
                    color = HeadingColor,
                    fontSize = sizing.title,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.08.em,
                )
                Box(Modifier.padding(top = 6.dp).width(HeadingRuleWidth).height(3.dp).background(accent, HeadingRuleShape))
            }
            CardRow("DELAY", sizing, mirrored) {
                val box = @Composable {
                    ValueBox(DelayFormat.format(dsp.get(delayIndex)), "ms", accent, sizing) {
                        context.showBmwNumberInput(
                            "DELAY", DelayRange.start, DelayRange.endInclusive, dsp.get(delayIndex), 0f, "ms",
                        ) { dsp.commit(delayIndex, it, delayMirror) }
                    }
                }
                val step: (Int) -> Unit = { dir ->
                    dsp.commit(delayIndex, stepped(dsp.get(delayIndex), dir * DelayStepMs, DelayRange), delayMirror)
                }
                BoxAndStepper(mirrored, "$cardName delay", sizing, box, step)
            }
            CardRow("GAIN", sizing, mirrored) {
                val box = @Composable {
                    ValueBox(DelayFormat.format(dsp.get(gainIndex)), "dB", accent, sizing) {
                        context.showBmwNumberInput(
                            "GAIN", GainRange.start, GainRange.endInclusive, dsp.get(gainIndex), GainStep, "dB",
                        ) { dsp.commit(gainIndex, snapGain(it)) }
                    }
                }
                BoxAndStepper(mirrored, "$cardName gain", sizing, box) { dir ->
                    dsp.commit(gainIndex, snapGain(dsp.get(gainIndex) + dir * GainStep))
                }
            }
            CardRow("POLARITY", sizing, mirrored) {
                BmwSwitch(
                    checked = dsp.isOn(polarityIndex),
                    onCheckedChange = { dsp.commit(polarityIndex, if (it) 1f else 0f) },
                    contentDescription = "${if (left) "Left" else "Right"} ${band.title} polarity",
                    onColor = PolInvertPink,
                    offColor = PolNormalGreen,
                    onLabel = "INVERT",
                    offLabel = "NORMAL",
                    // Wider than a value box so INVERT / NORMAL clears the thumb.
                    width = 132.dp,
                )
            }
        }
        if (inactive) {
            // Swallow taps on the dimmed card.
            Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            )
        }
    }
}

/** A value box and its −/+ pill, the pill on the inside (towards the middle of the page). */
@Composable
private fun RowScope.BoxAndStepper(
    mirrored: Boolean,
    label: String,
    sizing: DelaySizing,
    box: @Composable () -> Unit,
    onStep: (Int) -> Unit,
) {
    if (mirrored) {
        Stepper(label, sizing, gapAfter = true, onStep = onStep)
        box()
    } else {
        box()
        Stepper(label, sizing, onStep = onStep)
    }
}

/**
 * Control sizes for the Delay and Align pages: the phone's, the head unit's (the Align table), and
 * the head unit's large set for the Delay cards, which have the whole page width to themselves.
 */
internal class DelaySizing(
    val boxWidth: Dp,
    val controlHeight: Dp,
    val valueText: TextUnit,
    val unitText: TextUnit,
    val stepperHalfWidth: Dp,
    val label: TextUnit,
    val title: TextUnit,
) {
    val stepperWidth: Dp get() = stepperHalfWidth * 2 + 1.dp
}

internal val PhoneSizing = DelaySizing(120.dp, 38.dp, 16.sp, 14.sp, 40.dp, 14.sp, 18.sp)
internal val HeadUnitSizing = DelaySizing(84.dp, 44.dp, 20.sp, 16.sp, 40.dp, 16.sp, 18.sp)
internal val LargeSizing = DelaySizing(120.dp, 48.dp, 24.sp, 18.sp, 50.dp, 20.sp, 22.sp)

/**
 * Label on the left, then the row's controls (value box, and −/+ where the value is editable).
 * [mirrored]: the controls first and the label right-aligned at the far end.
 */
@Composable
internal fun CardRow(
    label: String,
    sizing: DelaySizing,
    mirrored: Boolean = false,
    controls: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (mirrored) controls()
        Text(
            text = label,
            color = LabelColor,
            fontSize = sizing.label,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.03.em,
            maxLines = 1,
            textAlign = if (mirrored) TextAlign.End else TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        if (!mirrored) controls()
    }
}

/**
 * The PEQ list's −/+ pill beside a value box; [onStep] gets -1 or +1. The gap to the box goes
 * before the pill, or after it with [gapAfter] (pill on the box's left).
 */
@Composable
internal fun Stepper(label: String, sizing: DelaySizing, gapAfter: Boolean = false, onStep: (Int) -> Unit) {
    if (!gapAfter) Spacer(Modifier.width(StepperGap))
    MinusPlusPill(
        onMinus = { onStep(-1) },
        onPlus = { onStep(1) },
        height = sizing.controlHeight,
        halfWidth = sizing.stepperHalfWidth,
        label = label,
    )
    if (gapAfter) Spacer(Modifier.width(StepperGap))
}

/** [value] moved by [delta], kept in [range] and rounded to 0.01 so repeated taps don't drift. */
internal fun stepped(value: Float, delta: Float, range: ClosedFloatingPointRange<Float>): Float =
    ((value + delta) * 100f).roundToInt().div(100f).coerceIn(range.start, range.endInclusive)

/** A recessed value readout; tappable (numeric entry) when [onTap] is given, else read-only. */
@Composable
internal fun ValueBox(text: String, unit: String, accent: Color, sizing: DelaySizing, onTap: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    BoxedValue(
        text = text,
        unit = unit,
        accentColor = accent,
        modifier = Modifier
            .width(sizing.boxWidth)
            .height(sizing.controlHeight)
            .then(
                if (onTap != null) {
                    Modifier
                        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onTap)
                        .bmwFocusRing(interaction)
                } else {
                    Modifier
                },
            ),
        textSize = sizing.valueText,
        unitSize = sizing.unitText,
    )
}

private fun snapGain(raw: Float): Float {
    val snapped = GainRange.start + ((raw - GainRange.start) / GainStep).roundToInt() * GainStep
    return snapped.coerceIn(GainRange.start, GainRange.endInclusive)
}

// Head unit art rects (dp on the 1280x480 art; content authored from x = 140; Figma "Delay page ·
// Mid · PROPOSED"). Band tabs across the top, the 3-way note under them while High is off, the car
// in the middle with the Left and Right cards either side of it, then the stereo link.
private val TabsRect = artDp(487, 76, 420, 40)
private val NoteRect = artDp(447, 118, 500, 22)
private val CarRect = artDp(447, 140, 500, 251)
private val LeftCardRect = artDp(152, 150, 320, 240)
private val RightCardRect = artDp(922, 150, 320, 240)
private val LinkRect = artDp(560, 400, 280, 48)

/** The backdrop car photo's opacity, so the cards read over it. */
private const val CarAlpha = 0.5f
private val HeadingColor = Color(0xFFE5E7EB)
private val HeadingRuleWidth = 48.dp
private val HeadingRuleShape = RoundedCornerShape(2.dp)

private const val InactiveAlpha = 0.35f
internal val PanelShape = RoundedCornerShape(10.dp)
internal val PanelBackground = Color(0x99100818)
internal val LabelColor = Color(0xFF969EA8)
private val PolNormalGreen = Color(BmwDashboardSkin.M_GREEN)
// Pink for inverted, shared with the stage-alignment accent so "offset from normal" reads the same.
private val PolInvertPink = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)

internal val DelayFormat = java.text.DecimalFormat(
    "0.##",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
internal val DelayRange = 0f..2.8f
private val GainRange = -6f..6f
private const val GainStep = 0.5f

// −/+ steps: the same resolution the tap-to-type boxes use.
private const val DelayStepMs = 0.01f
private val StepperGap = 6.dp
