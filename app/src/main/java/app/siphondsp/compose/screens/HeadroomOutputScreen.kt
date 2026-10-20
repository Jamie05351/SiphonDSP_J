package app.siphondsp.compose.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.ArtSlider
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.state.rememberPeqState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.dsp.AutoHeadroom
import app.siphondsp.dsp.AutoHeadroomCalculator
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.MbcBandGrMeter
import app.siphondsp.view.isHeadUnitDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Where one row of a group goes: [y] dp down the group (its head-unit position) and [h] tall.
 * The head unit places the row at that spot; the phone stacks the rows and uses only [h].
 */
private typealias GroupRow = (y: Int, h: Int) -> Modifier

/**
 * The Output workspace's page (OutputFragment): two groups side by side, laid out the same way on
 * both screens (Figma "SiphonDSP Front Panel (from code)", Steppers page) --
 *
 * - **Output**: the Pre EQ Preamp (blue), Headroom (purple) with its Auto switch, Post gain L and
 *   R (green), each a label + value box + −/+ row.
 * - **Limiter** (cyan): the master limiter's on/off switch in the group's header, its Threshold,
 *   and the live gain-reduction meter ([LimiterGrMeter]).
 *
 * Each group lists its rows once against a [GroupRow] that the head unit ([HeadUnitOutputPage])
 * or the phone ([PhoneOutputPage]) turns into a position.
 */
@Composable
fun HeadroomOutputScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val peq = rememberPeqState()
    TrackPeqState(peq)
    val headroom = stringResource(R.string.bmw_dsp_headroom)
    val output: @Composable (GroupRow) -> Unit = { row ->
        OutputGroup(dsp, peq, headroom, row)
        LoudnessGroup(dsp, row)
    }
    val limiter: @Composable (GroupRow) -> Unit = { row ->
        LimiterGroup(dsp, row)
        VolumeGroup(dsp, row)
    }

    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            HeadUnitOutputPage(output, limiter, modifier)
        } else {
            PhoneOutputPage(output, limiter, modifier)
        }
    }
}

/**
 * Keeps [peq] on the PEQ that's actually stored: reloaded on resume, and whenever the PEQ screen,
 * an import, a preset load or a backup restore changes it.
 */
@Composable
private fun TrackPeqState(peq: PeqStateHolder) {
    val context = LocalContext.current
    LifecycleResumeEffect(peq) {
        peq.refreshFromDisk()
        onPauseOrDispose { }
    }
    DisposableEffect(peq) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = peq.refreshFromDisk()
        }
        context.registerLocalReceiver(
            receiver,
            IntentFilter(Constants.ACTION_PARAMETRIC_EQ_CHANGED).apply {
                addAction(Constants.ACTION_PRESET_LOADED)
                addAction(Constants.ACTION_BACKUP_RESTORED)
            },
        )
        onDispose { context.unregisterLocalReceiver(receiver) }
    }
}

@Composable
private fun OutputGroup(dsp: BmwDspState, peq: PeqStateHolder, headroom: String, row: GroupRow) {
    ArtGroupHeader("Output", Color.White, row(0, HeaderHeight))
    PreampRow(peq, row(48, 48))
    HeadroomRows(dsp, peq, headroom, headroomRow = row(104, 48), autoRow = row(160, 40))
    DspArtSlider(
        dsp, "Post gain L", NativeBmwDspValues.INDEX_POST_GAIN_L, -6f..6f, 0.5f, "dB", PostGainAccent, row(208, 48),
    )
    DspArtSlider(
        dsp, "Post gain R", NativeBmwDspValues.INDEX_POST_GAIN_R, -6f..6f, 0.5f, "dB", PostGainAccent, row(264, 48),
    )
}

/**
 * Headroom and its Auto switch. Manual: the stepper sets the headroom. Auto: the stepper is locked
 * and shows the headroom the engine is running -- computed from the preamp, Pre EQ and each band's
 * response (see [AutoHeadroom]) -- while the manual value stays saved for switching back.
 */
@Composable
private fun HeadroomRows(
    dsp: BmwDspState,
    peq: PeqStateHolder,
    label: String,
    headroomRow: Modifier,
    autoRow: Modifier,
) {
    val auto = dsp.isOn(NativeBmwDspValues.INDEX_HEADROOM_AUTO)
    if (auto) {
        ArtSlider(
            label = label,
            value = autoHeadroomShown(dsp, peq) ?: dsp.get(NativeBmwDspValues.INDEX_HEADROOM),
            valueRange = HeadroomRange,
            step = 0.5f,
            unit = "dB",
            accentColor = HeadroomAccent,
            onPreview = {},
            onCommit = {},
            enabled = false,
            modifier = headroomRow,
        )
    } else {
        DspArtSlider(dsp, label, NativeBmwDspValues.INDEX_HEADROOM, HeadroomRange, 1f, "dB", HeadroomAccent, headroomRow)
    }
    ArtSwitchRow(
        label = "Auto headroom",
        checked = auto,
        onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_HEADROOM_AUTO, if (on) 1f else 0f) },
        modifier = autoRow,
    )
}

/**
 * The automatic headroom to show: what the running engine was last sent, polled while the page is
 * up; or, with no engine running (or before its first auto push), the same calculation done here
 * off the main thread from the saved settings. Rounded to the 0.1 dB the value box shows.
 */
@Composable
private fun autoHeadroomShown(dsp: BmwDspState, peq: PeqStateHolder): Float? {
    var applied by remember { mutableStateOf(RootlessAudioProcessorService.appliedAutoHeadroomDb()) }
    LaunchedEffect(Unit) {
        while (true) {
            applied = RootlessAudioProcessorService.appliedAutoHeadroomDb()
            delay(AutoHeadroomPollMs)
        }
    }
    var local by remember { mutableStateOf<Float?>(null) }
    val values = dsp.values
    val peqState = peq.peqState
    LaunchedEffect(values, peqState) {
        if (values.size != NativeBmwDspValues.SIZE) return@LaunchedEffect
        val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate()?.takeIf { it >= 8000f } ?: 48_000f
        // A calculator per run: a superseded run keeps computing after it's cancelled, so two
        // runs can overlap, and a calculator isn't thread-safe.
        local = withContext(Dispatchers.Default) {
            AutoHeadroomCalculator().headroomDb(values, peqState, sampleRate.toDouble())
        }
    }
    return (applied ?: local)?.let { Math.round(it * 10f) / 10f }
}

/**
 * The Pre EQ preamp: the gain ahead of the Pre EQ bands, set by an imported "Preamp:" line or
 * here. It lives in the PEQ store, not the DSP array, so it goes through [PeqStateHolder.setPreamp]
 * -- on release only: each apply validates, pushes and saves the whole PEQ.
 */
@Composable
private fun PreampRow(peq: PeqStateHolder, modifier: Modifier) {
    var preview by remember { mutableStateOf<Float?>(null) }
    ArtSlider(
        label = "Preamp",
        value = preview ?: peq.peqState.preampDb,
        valueRange = PreampRange,
        step = 0.5f,
        unit = "dB",
        accentColor = PreampAccent,
        onPreview = { preview = it },
        onCommit = {
            preview = null
            peq.setPreamp(it)
        },
        modifier = modifier,
    )
}

/**
 * Loudness compensation: as the master volume drops, lifts the bass (and a little treble) the ear
 * loses first at low level, so quiet listening keeps its body. None at full volume. Header switch
 * = on/off; Strength scales the whole lift.
 */
@Composable
private fun LoudnessGroup(dsp: BmwDspState, row: GroupRow) {
    ArtGroupHeader(
        title = "Loudness",
        accent = LoudnessAccent,
        modifier = row(332, HeaderHeight),
        checked = dsp.isOn(NativeBmwDspValues.INDEX_LOUDNESS_ENABLED),
        onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_LOUDNESS_ENABLED, if (on) 1f else 0f) },
    )
    DspArtSlider(
        dsp, "Strength", NativeBmwDspValues.INDEX_LOUDNESS_STRENGTH, 0f..100f, 5f, "%", LoudnessAccent,
        row(384, 48),
    )
}

@Composable
private fun LimiterGroup(dsp: BmwDspState, row: GroupRow) {
    ArtGroupHeader(
        title = "Limiter",
        accent = LimiterAccent,
        modifier = row(0, HeaderHeight),
        checked = dsp.isOn(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED),
        onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED, if (on) 1f else 0f) },
    )
    DspArtSlider(
        dsp, "Threshold", NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD, -12f..0f, 0.5f, "dB", LimiterAccent,
        row(52, 48),
    )
    Column(row(120, 64), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ArtLabel("Gain reduction")
        LimiterGrMeter(Modifier.fillMaxWidth().height(36.dp))
    }
}

/**
 * The DSP master volume, under the limiter -- the last gain in the chain. The header switch is
 * "sound on" (off = muted); the steering-wheel +/- move the same value through Tasker
 * (MasterVolumeReceiver), so this page always shows the exact level a measurement was taken at.
 */
@Composable
private fun VolumeGroup(dsp: BmwDspState, row: GroupRow) {
    ArtGroupHeader(
        title = "Volume",
        accent = VolumeAccent,
        modifier = row(200, HeaderHeight),
        checked = !dsp.isOn(NativeBmwDspValues.INDEX_MASTER_MUTE),
        onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_MASTER_MUTE, if (on) 0f else 1f) },
    )
    DspArtSlider(
        dsp, "Master", NativeBmwDspValues.INDEX_MASTER_VOLUME_DB,
        NativeBmwDspValues.MASTER_VOLUME_MIN_DB..NativeBmwDspValues.MASTER_VOLUME_MAX_DB, 1f, "dB", VolumeAccent,
        row(252, 48),
    )
}

/** Head unit: the two groups side by side, centred on the page. */
@Composable
private fun HeadUnitOutputPage(
    output: @Composable (GroupRow) -> Unit,
    limiter: @Composable (GroupRow) -> Unit,
    modifier: Modifier,
) {
    WorkspaceArtBox(modifier.fillMaxSize()) {
        output { y, h -> Modifier.artRect(artDp(OutputX, GroupTop + y, GroupWidth, h)) }
        limiter { y, h -> Modifier.artRect(artDp(LimiterX, GroupTop + y, GroupWidth, h)) }
    }
}

/** Phone: the same two groups side by side, centred in the phone's workspace. A phone too narrow
 *  for both stacks them instead, and the page scrolls only if they don't fit its height. */
@Composable
private fun PhoneOutputPage(
    output: @Composable (GroupRow) -> Unit,
    limiter: @Composable (GroupRow) -> Unit,
    modifier: Modifier,
) {
    val stack: GroupRow = { _, h -> Modifier.fillMaxWidth().height(h.dp) }
    val group: @Composable (@Composable (GroupRow) -> Unit) -> Unit = { content ->
        Column(Modifier.width(GroupWidth.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content(stack) }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= GroupWidth.dp * 2 + PhoneGroupGap + 24.dp
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(PhoneGroupGap)) {
                    group(output)
                    group(limiter)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    group(output)
                    group(limiter)
                }
            }
        }
    }
}

/**
 * The master-limiter gain-reduction meter -- the View `MbcBandGrMeter` (stage = LIMITER) kept
 * as-is via `AndroidView` (roadmap section 2 / Phase 5): its bar is coloured by how hard the
 * limiter is working (clear / working / heavy), which reads faster than one fixed colour. Polled
 * at ~30fps while the composition is at least STARTED, mirroring the fragment's old
 * `onStart`/`onStop` `Handler` loop.
 */
@Composable
private fun LimiterGrMeter(modifier: Modifier = Modifier) {
    val gainReductionDb = remember { mutableFloatStateOf(0f) }

    LifecycleStartEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                RootlessAudioProcessorService.nativeBmwMasterLimiterMeter()?.let { gainReductionDb.floatValue = it[0] }
                handler.postDelayed(this, 33L)
            }
        }
        handler.post(tick)
        onStopOrDispose { handler.removeCallbacks(tick) }
    }

    AndroidView(
        factory = { ctx -> MbcBandGrMeter(ctx).apply { stage = MbcBandGrMeter.Stage.LIMITER } },
        update = { it.setGainReductionDb(gainReductionDb.floatValue) },
        modifier = modifier.fillMaxWidth(),
    )
}

private val PreampAccent = Color(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
// The range BmwPeqState.validate() and the native PEQ accept.
private val PreampRange = -30f..12f
private val HeadroomAccent = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR)
private val HeadroomRange = NativeBmwDspValues.HEADROOM_MIN_DB..NativeBmwDspValues.HEADROOM_MAX_DB
private const val AutoHeadroomPollMs = 250L
private val PostGainAccent = Color(BmwDashboardSkin.M_GREEN)
private val LimiterAccent = Color(BmwDashboardSkin.SLIDER_LIMITER_COLOR)
private val VolumeAccent = Color.White
private val LoudnessAccent = Color(BmwDashboardSkin.SLIDER_TILT_COLOR)

// Head-unit layout, in the 1280x480 editor's dp: two 333dp groups (a 150dp label column, then a
// stepper) with 120dp between them, centred across the content area (x 190..1230), and centred
// vertically on the taller group -- the Output + Loudness column, 432dp with the Preamp and Auto
// headroom rows (rows 8dp apart instead of 12 to fit). It keeps the 260dp centre line the 340dp
// column had at 90, so both columns now start at 44 and the Output column ends at 476 of 480.
private const val GroupWidth = 333
private const val OutputX = 317
private const val LimiterX = 770
private const val GroupTop = 44
private const val HeaderHeight = 40
private val PhoneGroupGap = 60.dp
