package app.siphondsp.compose.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.AlignTarget
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.CarSpeakerDiagram
import app.siphondsp.compose.controls.DriverId
import app.siphondsp.compose.controls.SpeakerGeometryMath
import app.siphondsp.compose.controls.SpeakerGeometryState
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
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.roundToInt

/**
 * The Gains & Delay "Align" page: time-alignment setup, split off the Delay page so both have room.
 *
 * - **Map**: the car with its six drivers; tap one to highlight its band (the lit drivers show the
 *   distance the alignment uses).
 * - **Target**: Driver aligns to the driver seat. Multi aligns to both front seats using, per
 *   driver, the average of its driver-seat and passenger-seat paths (the passenger seat is the
 *   driver seat mirrored, so nothing extra is measured). Band timing is right for both seats; each
 *   seat is left half its left/right path gap off centre, which delay alone can't fix.
 * - **Table**: all six drivers at once. PATH is the measured driver-seat distance (editable), ALIGN
 *   the geometric time alignment for the target (relative to the farthest of all six drivers, at
 *   343 m/s).
 * - **Apply to delays** writes the six alignment values into the driver delays in one step; the
 *   Delay page then shows them.
 */
@Composable
fun SpeakerAlignScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val geometry = rememberSpeakerGeometry()
    var bandIndex by rememberSaveable { mutableIntStateOf(GainsBand.MID.ordinal) }
    val band = GainsBand.entries[bandIndex.coerceIn(0, GainsBand.entries.size - 1)]
    val setBand: (GainsBand) -> Unit = { bandIndex = it.ordinal }
    var confirming by remember { mutableStateOf(false) }
    val headUnit = LocalContext.current.isHeadUnitDisplay()

    BmwDspTheme {
        if (headUnit) {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                BandMap(geometry, band, setBand, Modifier.artRect(MapRect))
                Row(Modifier.artRect(SeatRect), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatToggle(geometry, Modifier.weight(1f))
                    ApplyButton(Color(band.accent), true, Modifier.width(220.dp).height(44.dp)) { confirming = true }
                }
                AlignTable(geometry, band, setBand, HeadUnitSizing, fill = true, Modifier.artRect(TableRect))
            }
        } else {
            Column(
                modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BandMap(geometry, band, setBand, Modifier.widthIn(max = 460.dp).fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatToggle(geometry, Modifier.weight(1f))
                    ApplyButton(Color(band.accent), false, Modifier.width(150.dp).height(44.dp)) { confirming = true }
                }
                AlignTable(geometry, band, setBand, PhoneAlignSizing, fill = false, Modifier.fillMaxWidth())
            }
        }
        if (confirming) ApplyDialog(geometry, onDismiss = { confirming = false }) { applyAlignment(dsp, geometry) }
    }
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

/**
 * All six drivers: name, PATH (editable, with −/+) and ALIGN (read-only). The highlighted band's
 * rows are tinted; tapping a driver's name highlights its band on the map too.
 */
@Composable
private fun AlignTable(
    geometry: SpeakerGeometryState,
    band: GainsBand,
    setBand: (GainsBand) -> Unit,
    sizing: DelaySizing,
    fill: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .background(PanelBackground, PanelShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = if (fill) Arrangement.SpaceEvenly else Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeaderText("DRIVER", sizing, Modifier.weight(1f))
            HeaderText("PATH", sizing, Modifier.width(sizing.boxWidth + TableStepperGap + sizing.stepperWidth))
            Spacer(Modifier.width(TableColumnGap))
            HeaderText("ALIGN", sizing, Modifier.width(sizing.boxWidth))
        }
        for (b in GainsBand.entries) {
            for (left in listOf(true, false)) AlignRow(geometry, b, left, b == band, setBand, sizing)
        }
    }
}

@Composable
private fun HeaderText(text: String, sizing: DelaySizing, modifier: Modifier) {
    Text(
        text = text,
        color = LabelColor,
        fontSize = sizing.label,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.03.em,
        textAlign = if (text == "DRIVER") TextAlign.Start else TextAlign.Center,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun AlignRow(
    geometry: SpeakerGeometryState,
    band: GainsBand,
    left: Boolean,
    highlighted: Boolean,
    setBand: (GainsBand) -> Unit,
    sizing: DelaySizing,
) {
    val context = LocalContext.current
    val accent = Color(band.accent)
    val id = DriverId(band.speaker, left)
    val cm = geometry.distanceCm(id)
    val name = "${if (left) "Left" else "Right"} ${band.title}"
    // Shows what Apply will write; tinted when the path needs more delay than the DSP allows.
    val capped = geometry.alignDelayMs(id) > DelayRange.endInclusive
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.background(accent.copy(alpha = 0.14f), RowShape) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = accent,
            fontSize = sizing.label,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f).clickable { setBand(band) }.padding(start = 6.dp),
        )
        ValueBox(CmFormat.format(cm), "cm", accent, sizing) {
            context.showBmwNumberInput("PATH", PathRangeCm.start, PathRangeCm.endInclusive, cm, 1f, "cm") {
                geometry.setDistanceCm(id, it)
            }
        }
        Stepper("$name path", sizing) { dir -> geometry.setDistanceCm(id, stepped(cm, dir * PathStepCm, PathRangeCm)) }
        Spacer(Modifier.width(TableColumnGap))
        ValueBox(
            AlignFormat.format(alignmentFor(geometry, id)), "ms",
            if (capped) CappedColor else accent.copy(alpha = 0.75f), sizing, null,
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
            fontSize = if (headUnit) 18.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ApplyDialog(geometry: SpeakerGeometryState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val seatName = if (geometry.target == AlignTarget.DRIVER) "the driver seat" else "both front seats"
    val needed = SpeakerGeometryMath.allDrivers.map { geometry.alignDelayMs(it) }
    val cappedCount = needed.count { it > DelayRange.endInclusive }
    val cappedNote = if (cappedCount == 0) {
        ""
    } else {
        "\n\n$cappedCount driver${if (cappedCount == 1) " needs" else "s need"} up to " +
            "${AlignFormat.format(needed.max())} ms, more than the ${AlignFormat.format(DelayRange.endInclusive)} ms " +
            "maximum. They will be set to the maximum and won't be fully aligned; check the PATH values."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply alignment to delays?") },
        text = {
            Text(
                "Sets the delay of all six drivers to the geometric alignment for $seatName " +
                    "(farthest driver 0 ms) and turns stereo link off. Your current delays are replaced." + cappedNote,
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

// Head unit art rects (dp on the 1280x480 art; content authored from x = 140). The map on the
// left with the seat target and Apply under it; the six-driver table on the right.
private val MapRect = artDp(160, 80, 540, 287)
private val SeatRect = artDp(160, 380, 540, 48)
private val TableRect = artDp(730, 76, 523, 392)

// A phone's table must fit ~336dp wide: narrower boxes and −/+ than the Delay page's.
private val PhoneAlignSizing = DelaySizing(88.dp, 38.dp, 16.sp, 14.sp, 36.dp, 14.sp, 18.sp)
private val TableStepperGap = 6.dp
private val TableColumnGap = 12.dp
private val RowShape = RoundedCornerShape(6.dp)

private val AlignFormat = java.text.DecimalFormat(
    "0.00",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
private val CmFormat = java.text.DecimalFormat(
    "0.#",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
// ALIGN needs more than DelayRange allows, so Apply will cap it.
private val CappedColor = Color(0xFFFF6B5A)
private val PathRangeCm = 20f..400f
private const val PathStepCm = 1f
