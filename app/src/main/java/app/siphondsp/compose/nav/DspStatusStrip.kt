package app.siphondsp.compose.nav

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.activity.EngineLauncherActivity
import app.siphondsp.compose.dialogs.AppDialog
import app.siphondsp.compose.dialogs.AppDialogs
import app.siphondsp.compose.state.SelfTestState
import app.siphondsp.compose.state.rememberEngineStatus
import app.siphondsp.compose.state.rememberSelfTestState
import app.siphondsp.model.DspStage
import app.siphondsp.service.AudioHealthLog
import app.siphondsp.service.DspHealthBadge
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspDestination
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Active stages read in the app's "lit" green (the ON/OFF switch's neon); inactive a dim grey.
private val OnColor = Color(BmwDashboardSkin.TOGGLE_ON_GREEN)
private val OffColor = Color(0xFF78808A)
private val WarnColor = Color(0xFFF2B33D)
private val SeparatorColor = Color(0xFF5A6068)

private const val HEALTH_POLL_MS = 1_000L
private const val LOG_LINES_SHOWN = 12

/** As large as fits, never above MAX (the page tabs' height) or below MIN. */
private const val MAX_TEXT_SP = 18f
private const val MIN_TEXT_SP = 14f

/** The widest each cell gets: the longest of each cell's labels, the neutral cell included. */
private val WorstCaseCells = listOf(
    "Xover 3-way", "Tilt off", "MBC off", "Limiter -12.5 dB", "Neutral ✗", "● DSP recovering",
)

/**
 * The bypass-state readout on the DSP workspace toolbar line, after the back arrow: the state of
 * the global stages otherwise only visible on their own screen -- the crossover (2-way, 3-way or
 * "Xover off" for the full-range bypass), Tilt, MBC and the master limiter (with its threshold) --
 * each tappable to open the workspace that owns it. While measurement neutral is on, a Neutral
 * cell shows the self-test's verdict (✓ verified, ✗ something still alters the signal, … testing)
 * and opens Native DSP Truth.
 *
 * Every one of those is what the native engine reports it is running ([rememberEngineStatus]),
 * never the saved settings: with no engine running they read "—".
 *
 * A last cell shows the audio path's health (DSP ok / idle / starting / recovering / NO AUDIO /
 * DSP off), polled once a second; tapping it opens the details -- reason, underruns, recoveries,
 * the saved event log -- with a Restart button. The log outlives a head-unit reset.
 */
@Composable
fun DspStatusStrip(onOpen: (DspDestination) -> Unit, onOpenTruth: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by rememberEngineStatus()
    val selfTest by rememberSelfTestState()
    var badge by remember { mutableStateOf(DspHealthBadge.evaluate(RootlessAudioProcessorService.pipelineRuntimeSnapshot())) }

    LaunchedEffect(Unit) {
        while (true) {
            badge = DspHealthBadge.evaluate(RootlessAudioProcessorService.pipelineRuntimeSnapshot())
            delay(HEALTH_POLL_MS)
        }
    }

    // Worked out once from the worst case rather than the live text, so the size never jumps as
    // the health cell's label changes.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxWidth = dimensionResource(R.dimen.dsp_status_strip_max_width)
    val fontSize = remember(density, maxWidth) {
        val style = TextStyle(fontSize = MAX_TEXT_SP.sp)
        val textPx = WorstCaseCells.sumOf { measurer.measure(it, style).size.width.toDouble() }.toFloat() +
            (WorstCaseCells.size - 1) * measurer.measure("·", style).size.width
        val paddingPx = with(density) { (12.dp * WorstCaseCells.size + 4.dp * (WorstCaseCells.size - 1)).toPx() }
        val roomPx = with(density) { maxWidth.toPx() } - paddingPx
        (MAX_TEXT_SP * (roomPx / textPx).coerceAtMost(1f)).coerceAtLeast(MIN_TEXT_SP).sp
    }

    Row(modifier.padding(top = 25.dp), verticalAlignment = Alignment.CenterVertically) {
        val engine = status
        val sp = fontSize.value
        val xover = when (val ways = engine?.crossoverWays) {
            null -> "Xover —"
            0 -> "Xover off"
            else -> "Xover $ways-way"
        }
        // Off is the notable state here (a measurement setup), so it is the one lit.
        Cell(xover, if (engine?.crossoverWays == 0) WarnColor else if (engine != null) OnColor else OffColor, sp) {
            onOpen(DspDestination.CROSSOVER_TILT)
        }
        Separator(sp)
        StageCell("Tilt", engine?.let { it.isActive(DspStage.TILT) }, null, sp) { onOpen(DspDestination.CROSSOVER_TILT) }
        Separator(sp)
        StageCell("MBC", engine?.let { it.isActive(DspStage.MBC) }, null, sp) { onOpen(DspDestination.DYNAMICS) }
        Separator(sp)
        // The master limiter lives on the Output workspace.
        StageCell(
            "Limiter", engine?.let { it.isActive(DspStage.MASTER_LIMITER) },
            engine?.limiterThresholdDb?.let { "${formatDb(it)} dB" }, sp,
        ) { onOpen(DspDestination.OUTPUT) }
        if (engine?.measurementNeutral == true) {
            Separator(sp)
            val (mark, color) = when (val t = selfTest) {
                SelfTestState.Running -> "…" to OffColor
                is SelfTestState.Done -> if (t.report.verified) "✓" to OnColor else "✗" to WarnColor
                else -> "?" to WarnColor
            }
            Cell("Neutral $mark", color, sp, onOpenTruth)
        }
        Separator(sp)
        val healthColor = when (badge.level) {
            DspHealthBadge.Level.OK -> OnColor
            DspHealthBadge.Level.IDLE -> OffColor
            DspHealthBadge.Level.WARN -> WarnColor
            DspHealthBadge.Level.BAD -> Color(BmwDashboardSkin.M_RED)
        }
        Cell("● ${badge.label}", healthColor, sp) { showHealthDialog(context) }
    }
}

/** "<label> on/off", or "<label> <value>" while on with a [value]; "<label> —" while the engine is unknown ([on] null). */
@Composable
private fun StageCell(label: String, on: Boolean?, value: String?, sp: Float, onClick: () -> Unit) {
    val text = when (on) {
        null -> "$label —"
        true -> if (value != null) "$label $value" else "$label on"
        false -> "$label off"
    }
    Cell(text, if (on == true) OnColor else OffColor, sp, onClick)
}

@Composable
private fun Cell(text: String, color: Color, sp: Float, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = sp.sp,
        maxLines = 1,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun Separator(sp: Float) {
    Text("·", color = SeparatorColor, fontSize = sp.sp, modifier = Modifier.padding(horizontal = 2.dp))
}

private fun formatDb(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else "%.1f".format(value)

private fun showHealthDialog(context: Context) {
    val snapshot = RootlessAudioProcessorService.pipelineRuntimeSnapshot()
    val badge = DspHealthBadge.evaluate(snapshot)
    val message = buildString {
        appendLine("${badge.label}: ${badge.detail}")
        if (snapshot != null) {
            appendLine()
            appendLine("State: ${snapshot.pipelineHealthState ?: "-"}")
            appendLine(
                "Last audio out: " +
                    if (snapshot.lastFlowAgeMs < 0) "none yet" else "${snapshot.lastFlowAgeMs / 1000} s ago",
            )
            appendLine("Playback sessions processed: ${snapshot.playbackSessions}")
            appendLine("Excluded apps: " + snapshot.excludedApps.ifEmpty { listOf("none") }.joinToString())
            if (snapshot.autoExcludedApps.isNotEmpty()) {
                appendLine("Auto-excluded (block capture): ${snapshot.autoExcludedApps.joinToString()}")
            }
            if (snapshot.excludedPlaying.isNotEmpty()) {
                appendLine("Excluded and playing now: ${snapshot.excludedPlaying.joinToString()}")
            }
            appendLine("Underruns: ${snapshot.underrunCount}")
            appendLine("Recoveries this session: ${snapshot.recoveriesThisSession}")
            appendLine(
                "Recorder: ${if (snapshot.recorderRecording) "recording" else "not recording"}  " +
                    "Track: ${if (snapshot.trackPlaying) "playing" else "not playing"}",
            )
        }
        val log = AudioHealthLog.read(context)
        appendLine()
        if (log.isEmpty()) {
            append("No events recorded yet.")
        } else {
            appendLine("Recent events (newest last):")
            log.takeLast(LOG_LINES_SHOWN).forEach { appendLine(it) }
        }
    }
    AppDialogs.show(
        AppDialog.Message(
            title = "DSP audio health",
            message = message.trimEnd(),
            confirm = if (snapshot == null) "Start audio" else "Restart audio",
            onConfirm = {
                if (snapshot == null || !RootlessAudioProcessorService.requestPipelineRestart()) {
                    // Not running (or gone since the dialog opened): the engine needs a fresh
                    // capture permission, which the launcher activity requests.
                    context.startActivity(
                        Intent(context, EngineLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            dismiss = "Close",
        ),
    )
}
