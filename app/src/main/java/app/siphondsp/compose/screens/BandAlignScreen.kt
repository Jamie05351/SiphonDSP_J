package app.siphondsp.compose.screens

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
 * Band Align: import REW measurements of each side's Low band alone and Mid band alone at each
 * seat, and it works out the Low delay and polarity that make the two bands add up best through
 * their crossover at those seats -- shows the before and after, and applies it (with undo). See
 * [BandAlignSolver] for the method.
 */
@Composable
fun BandAlignScreen(model: BandAlignViewModel = viewModel()) {
    val context = LocalContext.current
    val dsp = rememberBmwDspState()
    BmwDspTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("BAND ALIGN", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Body(
                "Lines each side's Low band up with its Mid band. Through the crossover both bands play, so " +
                    "how they add up there depends on their timing and polarity. Band Align finds the Low " +
                    "delay and polarity, against Mid, that make them add up best at the seats you measure -- " +
                    "both seats together, rejecting any setting that leaves either seat worse. Mid's delays are left as they are.",
            )

            Section("1  HOW TO MEASURE") {
                Body(
                    "With SiphonDSP on and your tune as it is now, but Mono Bass, Virtual Bass and Virtual Centre off (they mix the two sides). Same mic position for " +
                        "every measurement at a seat, and REW's acoustic timing reference on. For each side, play " +
                        "REW's sweep on that channel only:",
                    small = true,
                )
                Body(
                    "• Low only: Signal Generator › Routing › Mute mid band and Mute high band on.\n" +
                        "• Mid only: Mute low band and Mute high band on.\n" +
                        "• Low + Mid (optional, checks the timing): only Mute high band on.",
                    small = true,
                )
                Body(
                    "Leave Band isolation OFF -- it adds brick-wall filters that change the phase. Unmute " +
                        "everything before analysing. Export each from REW with File › Export › Measurement as " +
                        "text, smoothing 1/48 octave.",
                    color = Warn, small = true,
                )
            }

            Section("2  IMPORT REW MEASUREMENTS") {
                for (seat in BandAlignViewModel.Seat.entries) SeatImports(model, seat)
            }

            Section("3  ANALYSE") {
                val ready = model.readySides()
                if (model.measuredBeforeApply) {
                    Body(
                        "Applied. The files you imported were measured before that change, so they've been cleared. " +
                            "To confirm, measure Low + Mid again and compare it with the old one in REW. To analyse again, " +
                            "measure Low only and Mid only afresh and import those.",
                        color = Accent, small = true,
                    )
                }
                Body(
                    if (ready.isEmpty()) {
                        "Import at least one side's Low only and Mid only at a seat."
                    } else {
                        ready.entries.joinToString(" ") { (side, seats) ->
                            "${sideName(side)} side: ${seats.joinToString(" and ") { it.seat.lowercase() }}."
                        }
                    },
                    small = true,
                )
                ActionPill(if (model.analysing) "Analysing..." else "Analyse", enabled = ready.isNotEmpty() && !model.analysing) {
                    model.analyse(dsp.values, BmwPeqState.load(context))
                }
                if (model.analysing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
                model.failure?.let { Body(it, color = Warn, small = true) }
            }

            model.results?.let { results -> ResultSection(model, results, dsp.values, dsp.isOn(NativeBmwDspValues.INDEX_DELAY_LINKED)) { dsp.commitAll(it) } }
            if (model.undo != null) {
                Section("UNDO") {
                    Body("Puts the Low delay and polarity Band Align changed back the way they were (a side you've since edited by hand is left as you set it).", small = true)
                    ActionPill("Undo Band Align") {
                        val message = model.undoApply(dsp.values) { dsp.commitAll(it) }
                        Toast.makeText(context, message ?: model.failure ?: "Couldn't restore", Toast.LENGTH_LONG).show()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SeatImports(model: BandAlignViewModel, seat: BandAlignViewModel.Seat) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(6.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(seat.label.uppercase(), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        for (side in BmwOutputChannel.entries) {
            Text("${sideName(side).uppercase()} SIDE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            for (slot in BandAlignViewModel.Slot.entries) {
                val key = BandAlignViewModel.Key(seat, side, slot)
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) model.import(key, uri)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val imported = model.imports[key]
                        val err = model.importErrors[key]
                        when {
                            err != null -> Text(err, color = Warn, fontSize = 12.sp)
                            imported != null -> {
                                Text(imported.fileName, color = Color(0xFFB8C0CA), fontSize = 12.sp)
                                if (imported.measurement.heavilySmoothed) {
                                    Text("Smoothed ${imported.measurement.smoothing} -- export at 1/48 octave", color = Warn, fontSize = 12.sp)
                                }
                            }
                            else -> Text(slot.hint, color = Before, fontSize = 12.sp)
                        }
                    }
                    if (model.imports[key] != null) ActionPill("Clear", small = true) { model.clear(key) }
                    ActionPill(if (model.imports[key] != null) "Replace" else "Import", small = true) {
                        try {
                            launcher.launch(arrayOf("text/*", "application/octet-stream"))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "No file picker on this device -- import the files on a phone", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}

private fun describe(s: BandAlignSolver.Setting) =
    "${"%.2f".format(s.delayMs)} ms, ${if (s.inverted) "inverted" else "normal"} polarity"

@Composable
private fun ResultSection(
    model: BandAlignViewModel,
    results: List<BandAlignSolver.SideResult>,
    values: FloatArray,
    delayLinked: Boolean,
    commit: (Map<Int, Float>) -> Boolean,
) {
    val context = LocalContext.current
    Section("4  RESULT") {
        for (r in results) {
            Text("${sideName(r.side).uppercase()} SIDE", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val p = r.proposal
            if (p == null) {
                Body("Low ${describe(r.current)} already adds up with Mid as well as any Low delay and polarity can -- nothing to change.")
            } else {
                Text("Low: ${describe(p)}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Body(
                    "Now ${describe(r.current)}. Average gain over ${r.bandHz.start.roundToInt()}-" +
                        "${r.bandHz.endInclusive.roundToInt()} Hz: +${"%.1f".format(r.scoreDb)} dB (cuts counted double).",
                    small = true,
                )
                if (r.atRangeLimit) {
                    Body(
                        "That's at the end of the 0-2.8 ms delay range, so the best may lie beyond it. " +
                            if (p.delayMs <= 0.0) "Adding delay to Mid instead would let Low go earlier." else "",
                        color = Warn, small = true,
                    )
                }
            }
            for (seat in r.seats) {
                Text(seat.name.uppercase(), color = Before, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                BeforeAfterChart(seat, r.bandHz, Modifier.fillMaxWidth().height(160.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(Before, "Low + Mid now")
                    if (p != null) Legend(Accent, "with Band Align")
                }
                seat.fitRmsDb?.let { fit ->
                    val shift = if (seat.shiftMs != 0.0) ", Low/Mid timing corrected by ${"%.2f".format(seat.shiftMs)} ms" else ""
                    Body(
                        "Low + Mid match the Low + Mid measurement within ${"%.1f".format(fit)} dB$shift." +
                            if (fit > 2.0) " That's a loose match -- re-measure without moving the mic." else "",
                        color = if (fit > 2.0) Warn else Before, small = true,
                    )
                } ?: Body("No Low + Mid measurement: timing taken from REW's delay notes.", color = Before, small = true)
                if (seat.timingAmbiguous) {
                    Body(
                        "The Low + Mid measurement fits a timing offset either way round, so REW's own timing was kept.",
                        color = Warn, small = true,
                    )
                }
            }
        }
        val proposals = results.mapNotNull { r -> r.proposal?.let { r.side to it } }.toMap()
        if (proposals.isNotEmpty()) {
            val l = proposals[BmwOutputChannel.LEFT]?.delayMs ?: results.firstOrNull { it.side == BmwOutputChannel.LEFT }?.current?.delayMs
            val rr = proposals[BmwOutputChannel.RIGHT]?.delayMs ?: results.firstOrNull { it.side == BmwOutputChannel.RIGHT }?.current?.delayMs
            if (delayLinked && l != null && rr != null && l != rr) {
                Body(
                    "Link L/R Delay is on: Apply sets each side's Low delay on its own, but editing a delay on the " +
                        "Gains & Delay page with the link on will copy one side onto the other.",
                    color = Warn, small = true,
                )
            }
            if (model.appliedResults !== results) {
                ActionPill("Apply") {
                    val ok = model.apply(values, BmwPeqState.load(context), commit)
                    Toast.makeText(context, if (ok) "Band Align applied" else model.failure ?: "Couldn't save the change", Toast.LENGTH_LONG).show()
                }
            } else {
                Body("Applied.", color = Accent, small = true)
            }
            Body("Afterwards, measure Low + Mid again and compare it in REW to confirm.", small = true)
        }
    }
}

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
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun Body(text: String, color: Color = Color(0xFFD5DAE0), small: Boolean = false) {
    Text(text, color = color, fontSize = if (small) 13.sp else 14.sp, lineHeight = if (small) 18.sp else 20.sp)
}

@Composable
private fun ActionPill(text: String, enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit) {
    val c = if (enabled) Accent else Before
    Box(
        Modifier
            .widthIn(min = if (small) 72.dp else 120.dp)
            .height(if (small) 36.dp else 44.dp)
            .border(1.5.dp, c, RoundedCornerShape(22.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c, fontSize = if (small) 13.sp else 15.sp, fontWeight = FontWeight.Bold)
    }
}
