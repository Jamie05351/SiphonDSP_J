package app.siphondsp.compose.screens

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.controls.GlassButton
import app.siphondsp.compose.controls.GlassButtonKind
import app.siphondsp.compose.controls.GlassChip
import app.siphondsp.compose.controls.GlassDialog
import app.siphondsp.compose.controls.GlassDialogText
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.bandalign.BandAlignSolver
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin
import kotlin.math.ln
import kotlin.math.roundToInt

private val Accent = Color(BmwDashboardSkin.LIGHT_BLUE)
private val Before = Color(0xFF8A9099)
private val Warn = Color(BmwDashboardSkin.MID_BAND_YELLOW)
private val Panel = Color(0xFF101318)

private fun sideName(side: BmwOutputChannel) = if (side == BmwOutputChannel.LEFT) "Left" else "Right"

/**
 * Band Align, a page of the Alignment workspace: import REW measurements of each side's Low band
 * alone and Mid band alone at each seat, and it works out the Low delay and polarity that make the
 * two bands add up best through their crossover at those seats -- shows the before and after, and
 * applies it (with undo). See [BandAlignSolver] for the method.
 *
 * Three columns like the other pages: Import (seat, side, the three files), the before / after
 * graph for that seat and side, then Analyse, the result and Apply / Undo. How to measure is a
 * dialog, so the page itself stays short. Each column scrolls if its content runs long.
 */
@Composable
fun BandAlignScreen(model: BandAlignViewModel = viewModel()) {
    val context = LocalContext.current
    val dsp = rememberBmwDspState()
    var seatIndex by rememberSaveable { mutableIntStateOf(0) }
    var sideIndex by rememberSaveable { mutableIntStateOf(0) }
    var showHowTo by rememberSaveable { mutableStateOf(false) }
    val seat = BandAlignViewModel.Seat.entries[seatIndex]
    val side = BmwOutputChannel.entries[sideIndex]
    BmwDspTheme {
        Row(
            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            PageColumn(Modifier.weight(0.31f)) {
                ArtGroupHeader("Import", Accent, Modifier.fillMaxWidth().height(40.dp))
                BmwSegmentedControl(
                    options = BandAlignViewModel.Seat.entries.map { it.label.substringBefore(' ').uppercase() },
                    selectedIndex = seatIndex, onSelect = { seatIndex = it },
                    modifier = Modifier.fillMaxWidth(), segmentHeight = 36.dp,
                )
                BmwSegmentedControl(
                    options = BmwOutputChannel.entries.map { sideName(it).uppercase() },
                    selectedIndex = sideIndex, onSelect = { sideIndex = it },
                    modifier = Modifier.fillMaxWidth(), segmentHeight = 36.dp,
                )
                for (slot in BandAlignViewModel.Slot.entries) {
                    ImportRow(model, BandAlignViewModel.Key(seat, side, slot))
                }
            }
            PageColumn(Modifier.weight(0.38f)) {
                ArtGroupHeader("${seat.label.substringBefore(' ')} seat · ${sideName(side)} side", Color.White, Modifier.fillMaxWidth().height(40.dp))
                val result = model.results?.firstOrNull { it.side == side }
                val seatResult = result?.seats?.firstOrNull { it.name.equals(seat.label, ignoreCase = true) }
                    ?: result?.seats?.firstOrNull { it.name.contains(seat.label.substringBefore(' '), ignoreCase = true) }
                if (result != null && seatResult != null) {
                    BeforeAfterChart(seatResult, result.bandHz, Modifier.fillMaxWidth().height(190.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Legend(Before, "Low + Mid now")
                        if (result.proposal != null) Legend(Accent, "with Band Align")
                    }
                    SeatNotes(seatResult)
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(190.dp).background(Panel, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Body(
                            if (model.results == null) "Import Low only and Mid only, then Analyse" else "No result for this seat and side",
                            color = Before, small = true,
                        )
                    }
                }
            }
            PageColumn(Modifier.weight(0.31f)) {
                ArtGroupHeader("Analyse", Accent, Modifier.fillMaxWidth().height(40.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val ready = model.readySides()
                    GlassChip(if (model.analysing) "Analysing…" else "Analyse", onClick = {
                        if (ready.isNotEmpty() && !model.analysing) model.analyse(dsp.values, BmwPeqState.load(context))
                    }, arrow = false)
                    GlassChip("How to measure", onClick = { showHowTo = true }, arrow = false)
                }
                if (model.analysing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
                model.failure?.let { Body(it, color = Warn, small = true) }
                if (model.measuredBeforeApply) {
                    Body("Applied. The imported files were measured before that, so they've been cleared. Measure again to analyse or confirm.", color = Accent, small = true)
                }
                val ready = model.readySides()
                if (model.results == null && !model.analysing) {
                    Body(
                        if (ready.isEmpty()) "Import at least one side's Low only and Mid only at a seat."
                        else ready.entries.joinToString(" ") { (s, seats) -> "${sideName(s)}: ${seats.joinToString(" and ") { it.seat.lowercase() }}." },
                        small = true,
                    )
                }
                model.results?.let { results -> ResultSummary(model, results, dsp.values, dsp.isOn(NativeBmwDspValues.INDEX_DELAY_LINKED)) { dsp.commitAll(it) } }
                if (model.undo != null) {
                    GlassChip("Undo Band Align", onClick = {
                        val message = model.undoApply(dsp.values) { dsp.commitAll(it) }
                        Toast.makeText(context, message ?: model.failure ?: "Couldn't restore", Toast.LENGTH_LONG).show()
                    }, arrow = false)
                }
            }
        }
        if (showHowTo) HowToMeasure { showHowTo = false }
    }
}

/** One of the page's three columns, scrolling if its content is taller than the page. */
@Composable
private fun PageColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A measurement slot: its name, then the imported file (or what to measure), and Import / Replace / Clear. */
@Composable
private fun ImportRow(model: BandAlignViewModel, key: BandAlignViewModel.Key) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import(key, uri)
    }
    val imported = model.imports[key]
    val err = model.importErrors[key]
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(key.slot.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            when {
                err != null -> Text(err, color = Warn, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                imported != null -> {
                    Text(imported.fileName, color = Color(0xFFB8C0CA), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (imported.measurement.heavilySmoothed) {
                        Text("Smoothed ${imported.measurement.smoothing}: export at 1/48 oct", color = Warn, fontSize = 12.sp, maxLines = 1)
                    }
                }
                key.slot.hint.isNotEmpty() -> Text(key.slot.hint, color = Before, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (imported != null) GlassChip("Clear", onClick = { model.clear(key) }, arrow = false)
        GlassChip(if (imported != null) "Replace" else "Import", onClick = {
            try {
                launcher.launch(arrayOf("text/*", "application/octet-stream"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No file picker on this device -- import the files on a phone", Toast.LENGTH_LONG).show()
            }
        }, arrow = false)
    }
}

/** How well the Low + Mid measurement backs the result at this seat. */
@Composable
private fun SeatNotes(seat: BandAlignSolver.SeatResult) {
    seat.fitRmsDb?.let { fit ->
        val shift = if (seat.shiftMs != 0.0) ", timing corrected by ${"%.2f".format(seat.shiftMs)} ms" else ""
        Body(
            "Low + Mid match the Low + Mid measurement within ${"%.1f".format(fit)} dB$shift." +
                if (fit > 2.0) " A loose match: re-measure without moving the mic." else "",
            color = if (fit > 2.0) Warn else Before, small = true,
        )
    } ?: Body("No Low + Mid measurement: timing taken from REW's delay notes.", color = Before, small = true)
    if (seat.timingAmbiguous) {
        Body("The Low + Mid measurement fits a timing offset either way round, so REW's own timing was kept.", color = Warn, small = true)
    }
}

/** Each side's proposal, then Apply. */
@Composable
private fun ResultSummary(
    model: BandAlignViewModel,
    results: List<BandAlignSolver.SideResult>,
    values: FloatArray,
    delayLinked: Boolean,
    commit: (Map<Int, Float>) -> Boolean,
) {
    val context = LocalContext.current
    for (r in results) {
        val p = r.proposal
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${sideName(r.side).uppercase()} SIDE", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (p == null) {
                Body("Already adds up as well as it can: ${describe(r.current)}.", small = true)
            } else {
                if (p.delayMs < 0.0) {
                    // Low can't play early: Apply makes everything else later instead.
                    Text("Low ${"%.2f".format(-p.delayMs)} ms earlier, ${polarity(p)}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Low: ${describe(p)}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Body(
                    "Now ${describe(r.current)}. +${"%.1f".format(r.scoreDb)} dB over ${r.bandHz.start.roundToInt()}-${r.bandHz.endInclusive.roundToInt()} Hz.",
                    small = true,
                )
                if (r.atRangeLimit) {
                    Body(
                        "At the end of the 0-2.8 ms range; the best may lie beyond it." +
                            if (p.delayMs <= 0.0) " Delaying Mid instead would let Low go earlier." else "",
                        color = Warn, small = true,
                    )
                }
            }
        }
    }
    val proposals = results.mapNotNull { r -> r.proposal?.let { r.side to it } }.toMap()
    if (proposals.isEmpty()) return
    // One offset for both sides, as Apply uses it: shown once, not per side.
    val offset = BandAlignSolver.sharedOffsetMs(results)
    if (offset > 0.0) {
        Body(
            "Apply makes every delay, both sides, ${"%.2f".format(offset)} ms later, with the earliest Low at 0 ms, " +
                "so Mid / High and Left / Right timing stay as tuned.",
            color = Accent, small = true,
        )
    }
    val l = proposals[BmwOutputChannel.LEFT]?.delayMs ?: results.firstOrNull { it.side == BmwOutputChannel.LEFT }?.current?.delayMs
    val rr = proposals[BmwOutputChannel.RIGHT]?.delayMs ?: results.firstOrNull { it.side == BmwOutputChannel.RIGHT }?.current?.delayMs
    if (delayLinked && l != null && rr != null && l != rr) {
        Body(
            "Link L/R Delay is on: Apply sets each side's Low delay on its own, but editing a delay on the Delay page with the link on copies one side onto the other.",
            color = Warn, small = true,
        )
    }
    if (model.appliedResults !== results) {
        GlassChip("Apply", onClick = {
            val ok = model.apply(values, BmwPeqState.load(context), commit)
            Toast.makeText(context, if (ok) "Band Align applied" else model.failure ?: "Couldn't save the change", Toast.LENGTH_LONG).show()
        }, arrow = false)
    } else {
        Body("Applied. Measure Low + Mid again and compare it in REW to confirm.", color = Accent, small = true)
    }
}

/** The measuring steps, out of the page and in a dialog. */
@Composable
private fun HowToMeasure(onClose: () -> Unit) {
    GlassDialog(
        title = "How to measure",
        icon = R.drawable.ic_twotone_mic_24dp,
        onDismissRequest = onClose,
        buttons = listOf(GlassButton("Close", GlassButtonKind.Cancel, onClick = onClose)),
    ) {
        GlassDialogText(
            "Band Align lines each side's Low band up with its Mid band: it finds the Low delay and polarity, " +
                "against Mid, that add up best at the seats you measure, rejecting any setting that leaves either seat worse.\n\n" +
                "With SiphonDSP on and your tune as it is, but Mono Bass and Virtual Centre off. Same mic position for every " +
                "measurement at a seat, with REW's acoustic timing reference on. For each side, play REW's sweep on that channel only:\n" +
                "• Low only: Signal Generator › Routing › Mute mid band and Mute high band on.\n" +
                "• Mid only: Mute low band and Mute high band on.\n" +
                "• Low + Mid (optional, checks the timing): only Mute high band on.\n\n" +
                "Leave Band isolation OFF: it adds brick-wall filters that change the phase. Unmute everything before " +
                "analysing. Export from REW with File › Export › Measurement as text, smoothing 1/48 octave.",
        )
    }
}

private fun polarity(s: BandAlignSolver.Setting) = if (s.inverted) "inverted polarity" else "normal polarity"

private fun describe(s: BandAlignSolver.Setting) = "${"%.2f".format(s.delayMs)} ms, ${polarity(s)}"

@Composable
private fun BeforeAfterChart(seat: BandAlignSolver.SeatResult, band: ClosedFloatingPointRange<Double>, modifier: Modifier) {
    val f = seat.frequencies
    val all = seat.beforeDb.indices.filter { f[it] in 25.0..1000.0 && seat.beforeDb[it].isFinite() && seat.afterDb[it].isFinite() }
    val peak = all.maxOfOrNull { maxOf(seat.beforeDb[it], seat.afterDb[it]) }
    if (peak == null) {
        Body("Nothing to plot: these files don't overlap enough.", color = Warn, small = true)
        return
    }
    val top = peak + 3
    val bottom = top - 36
    Canvas(modifier.background(Panel, RoundedCornerShape(6.dp))) {
        val lnMin = ln(25.0)
        val lnMax = ln(1000.0)
        fun x(hz: Double) = ((ln(hz) - lnMin) / (lnMax - lnMin) * size.width).toFloat()
        fun y(db: Double) = ((top - db.coerceIn(bottom, top)) / (top - bottom) * size.height).toFloat()
        // The scored crossover region, and the grid.
        drawRect(Color(0x1446B5E8), topLeft = Offset(x(band.start), 0f), size = Size(x(band.endInclusive) - x(band.start), size.height))
        for (hz in listOf(50.0, 100.0, 200.0, 500.0)) drawLine(Color(0x33FFFFFF), Offset(x(hz), 0f), Offset(x(hz), size.height))
        var db = (bottom / 6).roundToInt() * 6.0
        while (db < top) {
            drawLine(Color(0x1FFFFFFF), Offset(0f, y(db)), Offset(size.width, y(db)))
            db += 6
        }
        fun curve(values: DoubleArray, color: Color, width: Float) {
            val path = Path()
            all.forEachIndexed { k, i -> if (k == 0) path.moveTo(x(f[i]), y(values[i])) else path.lineTo(x(f[i]), y(values[i])) }
            drawPath(path, color, style = Stroke(width = width))
        }
        curve(seat.beforeDb, Before, 2.dp.toPx())
        if (!seat.afterDb.contentEquals(seat.beforeDb)) curve(seat.afterDb, Accent, 2.5.dp.toPx())
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
        val lnMin = ln(25.0)
        val lnMax = ln(1000.0)
        for ((hz, label) in listOf(50.0 to "50", 100.0 to "100", 200.0 to "200", 500.0 to "500 Hz")) {
            val frac = ((ln(hz) - lnMin) / (lnMax - lnMin)).toFloat()
            Text(label, color = Before, fontSize = 11.sp, modifier = Modifier.offset(x = maxWidth * frac - 10.dp))
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(3.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Before, fontSize = 12.sp)
    }
}

@Composable
private fun Body(text: String, color: Color = Color(0xFFD5DAE0), small: Boolean = false) {
    Text(text, color = color, fontSize = if (small) 13.sp else 14.sp, lineHeight = if (small) 18.sp else 20.sp)
}
