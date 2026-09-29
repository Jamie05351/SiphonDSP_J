package app.siphondsp.compose.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.BmwSwitch
import app.siphondsp.compose.controls.BoxedValue
import app.siphondsp.compose.controls.CarSpeakerDiagram
import app.siphondsp.compose.controls.DriverId
import app.siphondsp.compose.controls.AlignTarget
import app.siphondsp.compose.controls.SpeakerGeometryState
import app.siphondsp.compose.controls.SpeakerKind
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.controls.bmwFocusRing
import app.siphondsp.compose.controls.bmwGlassBox
import app.siphondsp.compose.controls.rememberSpeakerGeometry
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
 * The Gains & Delay "Delay" page: one interactive speaker map for all three bands.
 *
 * - **Band**: tap a driver on the map, or a High / Mid / Low tab; the lit drivers, the colours and
 *   the two cards follow.
 * - **Target**: Driver aligns to the driver seat. Multi aligns to both front seats using, per
 *   driver, the average of its driver-seat and passenger-seat paths (the passenger seat is the
 *   driver seat mirrored, so nothing extra is measured). Band timing is right for both seats; each
 *   seat is left half its left/right path gap off centre, which delay alone can't fix.
 * - **Cards** (Left and Right for the chosen band): PATH is the measured driver-seat distance,
 *   ALIGN the geometric time alignment for the target (relative to the farthest of all six
 *   drivers, at 343 m/s), and DELAY / GAIN / POLARITY / STAGE ALIGN are the real DSP controls.
 * - **Apply to delays** writes the six alignment values into the driver delays in one step.
 * - **Stereo link** stays global.
 */
@Composable
fun GainsDelayScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val geometry = rememberSpeakerGeometry()
    var bandIndex by rememberSaveable { mutableIntStateOf(GainsBand.MID.ordinal) }
    val band = GainsBand.entries[bandIndex.coerceIn(0, GainsBand.entries.size - 1)]
    val linked = dsp.isOn(NativeBmwDspValues.INDEX_DELAY_LINKED)
    val inactive = band == GainsBand.HIGH && !ThreeWayCrossover.isEnabled(dsp.values)
    val setBand: (GainsBand) -> Unit = { bandIndex = it.ordinal }
    val headUnit = LocalContext.current.isHeadUnitDisplay()

    BmwDspTheme {
        if (headUnit) {
            HeadUnitDelayPage(dsp, geometry, band, setBand, linked, inactive, modifier)
        } else {
            PhoneDelayPage(dsp, geometry, band, setBand, linked, inactive, modifier)
        }
    }
}

// ---------------------------------------------------------------- head unit (1280x480 art rects)

@Composable
private fun HeadUnitDelayPage(
    dsp: BmwDspState,
    geometry: SpeakerGeometryState,
    band: GainsBand,
    setBand: (GainsBand) -> Unit,
    linked: Boolean,
    inactive: Boolean,
    modifier: Modifier,
) {
    var confirming by remember { mutableStateOf(false) }
    WorkspaceArtBox(modifier.fillMaxSize()) {
        DriverCard(dsp, geometry, band, left = true, linked, inactive, headUnit = true, Modifier.artRect(LeftCardRect))
        DriverCard(dsp, geometry, band, left = false, linked, inactive, headUnit = true, Modifier.artRect(RightCardRect))
        BandTabs(band, setBand, Modifier.artRect(TabsRect))
        BandMap(geometry, band, setBand, Modifier.artRect(MapRect))
        if (inactive) ThreeWayOffNote(band, Modifier.artRect(NoteRect))
        Row(Modifier.artRect(SeatRect), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeatToggle(geometry, Modifier.weight(1f))
            ApplyButton(Color(band.accent), true, Modifier.width(190.dp).height(44.dp)) { confirming = true }
        }
        StereoLinkRow(dsp, linked, true, Modifier.artRect(LinkRect))
    }
    if (confirming) ApplyDialog(geometry, onDismiss = { confirming = false }) { applyAlignment(dsp, geometry) }
}

// ---------------------------------------------------------------- phone (scrolling column)

@Composable
private fun PhoneDelayPage(
    dsp: BmwDspState,
    geometry: SpeakerGeometryState,
    band: GainsBand,
    setBand: (GainsBand) -> Unit,
    linked: Boolean,
    inactive: Boolean,
    modifier: Modifier,
) {
    var confirming by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 560.dp
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BandTabs(band, setBand, Modifier.fillMaxWidth().height(44.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BandMap(geometry, band, setBand, Modifier.widthIn(max = 460.dp).fillMaxWidth())
            }
            if (inactive) ThreeWayOffNote(band, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeatToggle(geometry, Modifier.weight(1f))
                ApplyButton(Color(band.accent), false, Modifier.width(150.dp).height(44.dp)) { confirming = true }
            }
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DriverCard(dsp, geometry, band, true, linked, inactive, false, Modifier.weight(1f))
                    DriverCard(dsp, geometry, band, false, linked, inactive, false, Modifier.weight(1f))
                }
            } else {
                DriverCard(dsp, geometry, band, true, linked, inactive, false, Modifier.fillMaxWidth())
                DriverCard(dsp, geometry, band, false, linked, inactive, false, Modifier.fillMaxWidth())
            }
            StereoLinkRow(dsp, linked, false, Modifier.fillMaxWidth())
        }
    }
    if (confirming) ApplyDialog(geometry, onDismiss = { confirming = false }) { applyAlignment(dsp, geometry) }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun BandTabs(band: GainsBand, onSelect: (GainsBand) -> Unit, modifier: Modifier) {
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
private fun SeatToggle(geometry: SpeakerGeometryState, modifier: Modifier) {
    BmwSegmentedControl(
        options = listOf("Driver", "Multi"),
        selectedIndex = geometry.target.ordinal,
        onSelect = { geometry.selectTarget(AlignTarget.entries[it]) },
        modifier = modifier,
        segmentHeight = 40.dp,
        segmentGap = 6.dp,
    )
}

/**
 * The live map. The lit drivers show the distance the alignment uses: the driver-seat path, or in
 * Multi the average of the driver- and passenger-seat paths.
 */
@Composable
private fun BandMap(
    geometry: SpeakerGeometryState,
    band: GainsBand,
    setBand: (GainsBand) -> Unit,
    modifier: Modifier,
) {
    CarSpeakerDiagram(
        selected = band.speaker,
        accent = Color(band.accent),
        onSelect = { setBand(GainsBand.forSpeaker(it)) },
        modifier = modifier,
        seats = geometry.target.seats,
        label = { kind, left ->
            if (kind != band.speaker) {
                null
            } else {
                val cm = geometry.targetDistanceCm(DriverId(kind, left)).roundToInt()
                if (geometry.target == AlignTarget.MULTI) "avg $cm cm" else "$cm cm"
            }
        },
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
            fontSize = if (headUnit) 16.sp else 14.sp,
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

@Composable
private fun ApplyButton(accent: Color, headUnit: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .bmwGlassBox(accent)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .bmwFocusRing(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Apply to delays",
            color = Color.White,
            fontSize = if (headUnit) 16.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ApplyDialog(geometry: SpeakerGeometryState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val seatName = if (geometry.target == AlignTarget.DRIVER) "the driver seat" else "both front seats"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply alignment to delays?") },
        text = {
            Text(
                "Sets the delay of all six drivers to the geometric alignment for $seatName " +
                    "(farthest driver 0 ms) and turns stereo link off. Your current delays are replaced.",
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Left or Right card for [band]: PATH (measured, editable), ALIGN (derived), then the real DSP
 * controls. On a head unit it fills its art rect and spreads the rows; on a phone it wraps.
 */
@Composable
private fun DriverCard(
    dsp: BmwDspState,
    geometry: SpeakerGeometryState,
    band: GainsBand,
    left: Boolean,
    linked: Boolean,
    inactive: Boolean,
    headUnit: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val accent = Color(band.accent)
    val stageAccent = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)
    val id = DriverId(band.speaker, left)
    val output = if (left) band.leftOutput else band.rightOutput
    val delayIndex = if (left) band.delayL else band.delayR
    val siblingIndex = if (left) band.delayR else band.delayL
    val gainIndex = if (left) band.gainL else band.gainR
    val stageIndex = if (left) NativeBmwDspValues.INDEX_STAGE_DELAY_L else NativeBmwDspValues.INDEX_STAGE_DELAY_R
    val polarityIndex = band.polarityIndex(output)
    val delayMirror = if (linked) intArrayOf(siblingIndex) else IntArray(0)
    val cm = geometry.distanceCm(id)
    val labelSize = if (headUnit) 16.sp else 14.sp

    Box(modifier) {
        Column(
            modifier = (if (headUnit) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .background(PanelBackground, PanelShape)
                .border(1.dp, Color(band.stroke), PanelShape)
                .then(if (inactive) Modifier.alpha(InactiveAlpha) else Modifier)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = if (headUnit) Arrangement.SpaceEvenly else Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${if (left) "Left" else "Right"} ${band.title}",
                color = accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            CardRow("PATH", labelSize) {
                ValueBox(CmFormat.format(cm), "cm", accent, headUnit) {
                    context.showBmwNumberInput("PATH", PathRangeCm.start, PathRangeCm.endInclusive, cm, 1f, "cm") {
                        geometry.setDistanceCm(id, it)
                    }
                }
            }
            CardRow("ALIGN", labelSize) {
                ValueBox(AlignFormat.format(geometry.alignDelayMs(id)), "ms", accent.copy(alpha = 0.75f), headUnit, null)
            }
            CardRow("DELAY", labelSize) {
                ValueBox(DelayFormat.format(dsp.get(delayIndex)), "ms", accent, headUnit) {
                    context.showBmwNumberInput(
                        "DELAY", DelayRange.start, DelayRange.endInclusive, dsp.get(delayIndex), 0f, "ms",
                    ) { dsp.commit(delayIndex, it, delayMirror) }
                }
            }
            CardRow("GAIN", labelSize) {
                ValueBox(DelayFormat.format(dsp.get(gainIndex)), "dB", accent, headUnit) {
                    context.showBmwNumberInput(
                        "GAIN", GainRange.start, GainRange.endInclusive, dsp.get(gainIndex), GainStep, "dB",
                    ) { dsp.commit(gainIndex, snapGain(it)) }
                }
            }
            CardRow("POLARITY", labelSize) {
                BmwSwitch(
                    checked = dsp.isOn(polarityIndex),
                    onCheckedChange = { dsp.commit(polarityIndex, if (it) 1f else 0f) },
                    contentDescription = "${if (left) "Left" else "Right"} ${band.title} polarity",
                    onColor = PolInvertPink,
                    offColor = PolNormalGreen,
                    onLabel = "INVERT",
                    offLabel = "NORMAL",
                    // Wider than the value boxes so INVERT / NORMAL clears the thumb; the POLARITY
                    // label is short enough to give the width up.
                    width = 132.dp,
                )
            }
            CardRow("STAGE ALIGN", labelSize) {
                ValueBox(DelayFormat.format(dsp.get(stageIndex)), "ms", stageAccent, headUnit) {
                    context.showBmwNumberInput(
                        "STAGE ALIGNMENT", 0f, NativeBmwDspValues.STAGE_DELAY_MAX_MS, dsp.get(stageIndex), 0.05f, "ms",
                    ) { dsp.commit(stageIndex, it) }
                }
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

@Composable
private fun CardRow(label: String, size: TextUnit, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.03.em,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        control()
    }
}

/** A recessed value readout; tappable (numeric entry) when [onTap] is given, else read-only. */
@Composable
private fun ValueBox(text: String, unit: String, accent: Color, headUnit: Boolean, onTap: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    BoxedValue(
        text = text,
        unit = unit,
        accentColor = accent,
        modifier = Modifier
            .width(if (headUnit) 108.dp else 120.dp)
            .height(if (headUnit) 44.dp else 38.dp)
            .then(
                if (onTap != null) {
                    Modifier
                        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onTap)
                        .bmwFocusRing(interaction)
                } else {
                    Modifier
                },
            ),
        textSize = if (headUnit) 20.sp else 16.sp,
        unitSize = if (headUnit) 16.sp else 14.sp,
    )
}

/** Writes the six geometric alignment delays in one atomic save, and unlinks the sides. */
private fun applyAlignment(dsp: BmwDspState, geometry: SpeakerGeometryState) {
    val updates = HashMap<Int, Float>()
    for (band in GainsBand.entries) {
        updates[band.delayL] = alignmentFor(geometry, DriverId(band.speaker, true))
        updates[band.delayR] = alignmentFor(geometry, DriverId(band.speaker, false))
    }
    // Left and right now differ, so a linked pair would fight this on the next edit.
    updates[NativeBmwDspValues.INDEX_DELAY_LINKED] = 0f
    dsp.commitAll(updates)
}

private fun alignmentFor(geometry: SpeakerGeometryState, id: DriverId): Float {
    val ms = geometry.alignDelayMs(id).coerceIn(DelayRange.start, DelayRange.endInclusive)
    return (ms * 100f).roundToInt() / 100f
}

private fun snapGain(raw: Float): Float {
    val snapped = GainRange.start + ((raw - GainRange.start) / GainStep).roundToInt() * GainStep
    return snapped.coerceIn(GainRange.start, GainRange.endInclusive)
}

// Head unit art rects (dp on the 1280x480 art). Cards sit in the old side-panel columns; the
// centre column is band tabs, the map, seat + apply, then the stereo link.
private val LeftCardRect = artDp(190, 72, 244, 396)
private val RightCardRect = artDp(984, 72, 244, 396)
private val TabsRect = artDp(462, 72, 506, 44)
private val MapRect = artDp(482, 122, 466, 248)
private val NoteRect = artDp(462, 126, 506, 28)
private val SeatRect = artDp(462, 376, 506, 44)
private val LinkRect = artDp(540, 424, 350, 44)

private const val InactiveAlpha = 0.35f
private val PanelShape = RoundedCornerShape(10.dp)
private val PanelBackground = Color(0x99100818)
private val LabelColor = Color(0xFF969EA8)
private val PolNormalGreen = Color(BmwDashboardSkin.M_GREEN)
// Pink for inverted, shared with the stage-alignment accent so "offset from normal" reads the same.
private val PolInvertPink = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)

private val DelayFormat = java.text.DecimalFormat(
    "0.##",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
private val AlignFormat = java.text.DecimalFormat(
    "0.00",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
private val CmFormat = java.text.DecimalFormat(
    "0.#",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
private val DelayRange = 0f..2.8f
private val GainRange = -6f..6f
private const val GainStep = 0.5f
private val PathRangeCm = 20f..400f
