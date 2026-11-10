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
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.R
import app.siphondsp.activity.EngineLauncherActivity
import app.siphondsp.compose.dialogs.AppDialog
import app.siphondsp.compose.dialogs.AppDialogs
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.AudioHealthLog
import app.siphondsp.service.DspHealthBadge
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspDestination
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private class Segment(
    val label: String,
    val enabledIndex: Int,
    val target: DspDestination,
    /** When set, the label reads "<label> <value> dB" instead of "<label> on/off". */
    val valueIndex: Int? = null,
)

private val Segments = listOf(
    Segment("Tilt", NativeBmwDspValues.INDEX_TILT_ENABLED, DspDestination.CROSSOVER_TILT),
    Segment("MBC", NativeBmwDspValues.INDEX_MBC_ENABLED, DspDestination.DYNAMICS),
    // The master limiter lives on the Output workspace.
    Segment(
        "Limiter", NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED, DspDestination.OUTPUT,
        valueIndex = NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD,
    ),
)

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

/** The widest each cell gets: stages off, a two-digit limiter threshold, the longest health label. */
private val WorstCaseCells = listOf("Tilt off", "MBC off", "Limiter -12.5 dB", "● DSP recovering")

/**
 * The bypass-state readout on the DSP workspace toolbar line, after the back arrow: the on/off
 * state of the global stages otherwise only visible on their own screen -- Tilt, MBC and the
 * master limiter (with its threshold) -- each tappable to open the workspace that owns it.
 *
 * A fourth cell shows the audio path's health (DSP ok / idle / starting / recovering / NO AUDIO /
 * DSP off), polled once a second; tapping it opens the details -- reason, underruns, recoveries,
 * the saved event log -- with a Restart button. The log outlives a head-unit reset.
 *
 * Refreshes on resume and on every [AppEvent.NativeBmwDspUpdated] edit.
 */
@Composable
fun DspStatusStrip(onOpen: (DspDestination) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var values by remember { mutableStateOf(NativeBmwDspValues.load(context)) }
    var badge by remember { mutableStateOf(DspHealthBadge.evaluate(RootlessAudioProcessorService.pipelineRuntimeSnapshot())) }

    LaunchedEffect(Unit) {
        AppEvents.of<AppEvent.NativeBmwDspUpdated>().collect { event ->
            values = event.values.takeIf { it.size == NativeBmwDspValues.SIZE } ?: NativeBmwDspValues.load(context)
        }
    }
    LifecycleResumeEffect(Unit) {
        values = NativeBmwDspValues.load(context)
        onPauseOrDispose {}
    }
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
        Segments.forEachIndexed { index, segment ->
            if (index > 0) Separator(fontSize.value)
            val on = values[segment.enabledIndex] >= 0.5f
            val text = if (segment.valueIndex != null) {
                "${segment.label} ${formatDb(values[segment.valueIndex])} dB"
            } else {
                "${segment.label} ${if (on) "on" else "off"}"
            }
            Cell(text, if (on) OnColor else OffColor, fontSize.value) { onOpen(segment.target) }
        }
        Separator(fontSize.value)
        val healthColor = when (badge.level) {
            DspHealthBadge.Level.OK -> OnColor
            DspHealthBadge.Level.IDLE -> OffColor
            DspHealthBadge.Level.WARN -> WarnColor
            DspHealthBadge.Level.BAD -> Color(BmwDashboardSkin.M_RED)
        }
        Cell("● ${badge.label}", healthColor, fontSize.value) { showHealthDialog(context) }
    }
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
