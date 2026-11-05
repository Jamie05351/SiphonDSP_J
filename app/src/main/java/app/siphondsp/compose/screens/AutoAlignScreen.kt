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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.siphondsp.compose.controls.BmwSegmentedControl
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.autoalign.AutoAlignSolver
import app.siphondsp.model.BmwPeqState
import app.siphondsp.view.BmwDashboardSkin
import kotlin.math.ln
import kotlin.math.roundToInt

private val Accent = Color(BmwDashboardSkin.LIGHT_BLUE)
private val Before = Color(0xFF8A9099)
private val Warn = Color(BmwDashboardSkin.MID_BAND_YELLOW)
private val Panel = Color(0xFF101318)

/**
 * Auto Align: import REW measurements of each seat (left only, right only, and both together),
 * and it works out the all-pass that makes the two sides add up best at those seats through the
 * DSP as it's set now -- shows the before and after, and applies it (with undo). See
 * [AutoAlignSolver] for the method.
 */
@Composable
fun AutoAlignScreen(model: AutoAlignViewModel = viewModel()) {
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
            Text("AUTO ALIGN", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Body(
                "Lines the left and right speakers up at your seats. In a car each seat hears the two " +
                    "sides by different paths, so through the bass and lower mids they arrive out of step " +
                    "and cancel -- a loss EQ can't fill. Auto Align finds the all-pass that rotates one side " +
                    "into step, through your crossovers and settings as they are now, and puts it in " +
                    "all-pass section 2 of every output on that side.",
            )

            Section("1  HOW YOU MEASURED") {
                BmwSegmentedControl(
                    options = listOf("SiphonDSP OFF (factory)", "SiphonDSP ON (this tune)"),
                    selectedIndex = model.measuredWith.ordinal,
                    onSelect = { model.measuredWith = AutoAlignSolver.MeasuredWith.entries[it] },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    segmentHeight = 36.dp,
                    segmentGap = 6.dp,
                )
                Body(
                    if (model.measuredWith == AutoAlignSolver.MeasuredWith.DSP_OFF) {
                        "With SiphonDSP off its left/right swap (which corrects the car's reversed speaker " +
                            "harness) isn't running, so the file REW calls \"L\" played through the right-hand " +
                            "speakers. Auto Align accounts for that."
                    } else {
                        "Measured through this tune exactly as it's set now. Change nothing between measuring and analysing."
                    },
                    small = true,
                )
            }

            Section("2  IMPORT REW MEASUREMENTS") {
                Body(
                    "In REW: File > Export > Measurement as text, with smoothing at 1/48 octave or none " +
                        "(1/3 octave blurs the phase too much). Measure each seat three times from the same " +
                        "mic position: left only, right only, then both together.",
                    small = true,
                )
                for (seat in AutoAlignViewModel.Seat.entries) SeatImports(model, seat)
            }

            Section("3  ANALYSE") {
                val ready = model.readySeats()
                Body(
                    when (ready.size) {
                        0 -> "Import at least one seat's left and right measurements."
                        1 -> "Optimising for the ${ready.single().name.lowercase()}. Add the other seat to tune for both."
                        else -> "Optimising for both seats together."
                    },
                    small = true,
                )
                val busy = model.progress != null
                ActionPill(if (busy) "Analysing..." else "Analyse", enabled = ready.isNotEmpty() && !busy) {
                    model.analyse(dsp.values, BmwPeqState.load(context))
                }
                model.progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Accent)
                }
                model.failure?.let { Body("Analysis failed: $it", color = Warn, small = true) }
            }

            model.result?.let { result -> ResultSection(model, result, dsp.values) { dsp.commitAll(it) } }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SeatImports(model: AutoAlignViewModel, seat: AutoAlignViewModel.Seat) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(6.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(seat.label.uppercase(), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        for (slot in AutoAlignViewModel.Slot.entries) {
            val key = seat to slot
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) model.import(seat, slot, uri)
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
                                Text("Smoothed ${imported.measurement.smoothing} -- export at 1/48 octave for a reliable result", color = Warn, fontSize = 12.sp)
                            }
                        }
                        else -> Text(slot.hint, color = Before, fontSize = 12.sp)
                    }
                }
                if (model.imports[key] != null) ActionPill("Clear", small = true) { model.clear(seat, slot) }
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

@Composable
private fun ResultSection(
    model: AutoAlignViewModel,
    result: AutoAlignSolver.Result,
    values: FloatArray,
    commit: (Map<Int, Float>) -> Boolean,
) {
    val context = LocalContext.current
    Section("4  RESULT") {
        val p = result.proposal
        if (p == null) {
            Body("Your seats already add up as well as one all-pass can make them -- nothing to change.")
        } else {
            val side = if (p.side == BmwOutputChannel.LEFT) "LEFT" else "RIGHT"
            Text(
                "All-pass on the $side outputs: ${p.frequencyHz.roundToInt()} Hz, Q ${"%.1f".format(p.q)}, 2nd order",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            Body(
                "Section 2 of Low, Mid and High ${side.lowercase()}. Average gain over 30-400 Hz: " +
                    "+${"%.1f".format(result.scoreDb)} dB (cuts counted double).",
                small = true,
            )
            if (result.replacesExisting) {
                Body("This replaces an all-pass section 2 that's switched on now.", color = Warn, small = true)
            }
        }
        for (seat in result.seats) {
            Text(seat.name.uppercase(), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            BeforeAfterChart(seat, Modifier.fillMaxWidth().height(170.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Before, "now")
                if (p != null) Legend(Accent, "with Auto Align")
            }
            seat.fitRmsDb?.let { fit ->
                val shift = if (seat.shiftMs != 0.0) ", left/right timing corrected by ${"%.2f".format(seat.shiftMs)} ms" else ""
                Body(
                    "Left + right match the both-together measurement within ${"%.1f".format(fit)} dB$shift." +
                        if (fit > 2.0) " That's a loose match -- re-measure without moving the mic." else "",
                    color = if (fit > 2.0) Warn else Before,
                    small = true,
                )
            } ?: Body("No both-together measurement: timing taken from REW's delay notes.", color = Warn, small = true)
        }
        if (p != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (model.undo == null) ActionPill("Apply") {
                    val ok = model.apply(values, commit)
                    Toast.makeText(context, if (ok) "Auto Align applied" else model.failure ?: "Couldn't save the change", Toast.LENGTH_LONG).show()
                }
                if (model.undo != null) {
                    ActionPill("Undo") {
                        val ok = model.undoApply(commit)
                        Toast.makeText(context, if (ok) "Restored the previous all-pass" else "Couldn't restore", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Body("Measure again afterwards with SiphonDSP on to confirm, and run Auto Align on that if you like.", small = true)
        }
    }
}

@Composable
private fun BeforeAfterChart(seat: AutoAlignSolver.SeatResult, modifier: Modifier) {
    val f = seat.frequencies
    val all = seat.beforeDb.indices.filter { f[it] in 25.0..1000.0 }
    val top = all.maxOf { maxOf(seat.beforeDb[it], seat.afterDb[it]) } + 3
    val bottom = top - 36
    Canvas(modifier.background(Panel, RoundedCornerShape(6.dp))) {
        val lnMin = ln(25.0)
        val lnMax = ln(1000.0)
        fun x(hz: Double) = ((ln(hz) - lnMin) / (lnMax - lnMin) * size.width).toFloat()
        fun y(db: Double) = ((top - db.coerceIn(bottom, top)) / (top - bottom) * size.height).toFloat()
        // The band Auto Align scores, and the decade/octave grid.
        drawRect(Color(0x1446B5E8), topLeft = Offset(x(30.0), 0f), size = androidx.compose.ui.geometry.Size(x(400.0) - x(30.0), size.height))
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
    // Each label under its own grid line (the axis is log-scaled, so they aren't evenly spaced).
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
        val lnMin = ln(25.0)
        val lnMax = ln(1000.0)
        for ((hz, label) in listOf(50.0 to "50", 100.0 to "100", 200.0 to "200", 500.0 to "500 Hz")) {
            val frac = ((ln(hz) - lnMin) / (lnMax - lnMin)).toFloat()
            Text(
                label, color = Before, fontSize = 11.sp,
                modifier = Modifier.offset(x = maxWidth * frac - 10.dp),
            )
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
