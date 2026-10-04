package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.BmwSwitch
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.MbcBandGrMeter
import app.siphondsp.view.isHeadUnitDisplay

/**
 * Where one row of a group goes: [y] dp down the group (its head-unit position) and [h] tall.
 * The head unit places the row at that spot; the phone stacks the rows and uses only [h].
 */
private typealias GroupRow = (y: Int, h: Int) -> Modifier

/**
 * The Gains & Delay pager's Output page (alongside the Gains & Delay diagram page and the
 * bus-limiter page, [CompressorDriverPage]): two groups side by side, laid out the same way on
 * both screens (Figma "SiphonDSP Front Panel (from code)", Steppers page) --
 *
 * - **Output**: Headroom (purple), Post gain L and R (green), each a label + value box + −/+ row.
 * - **Limiter** (cyan): the master limiter's on/off switch in the group's header, its Threshold,
 *   and the live gain-reduction meter ([LimiterGrMeter]).
 *
 * Each group lists its rows once against a [GroupRow] that the head unit ([HeadUnitOutputPage])
 * or the phone ([PhoneOutputPage]) turns into a position.
 */
@Composable
fun HeadroomOutputScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val headroom = stringResource(R.string.bmw_dsp_headroom)
    val output: @Composable (GroupRow) -> Unit = { row -> OutputGroup(dsp, headroom, row) }
    val limiter: @Composable (GroupRow) -> Unit = { row -> LimiterGroup(dsp, row) }

    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            HeadUnitOutputPage(output, limiter, modifier)
        } else {
            PhoneOutputPage(output, limiter, modifier)
        }
    }
}

@Composable
private fun OutputGroup(dsp: BmwDspState, headroom: String, row: GroupRow) {
    GroupHeader("Output", Color.White, row(0, HeaderHeight))
    DspArtSlider(
        dsp, headroom, NativeBmwDspValues.INDEX_HEADROOM, -12f..0f, 1f, "dB", HeadroomAccent, row(52, 48),
    )
    DspArtSlider(
        dsp, "Post gain L", NativeBmwDspValues.INDEX_POST_GAIN_L, -6f..6f, 0.5f, "dB", PostGainAccent, row(112, 48),
    )
    DspArtSlider(
        dsp, "Post gain R", NativeBmwDspValues.INDEX_POST_GAIN_R, -6f..6f, 0.5f, "dB", PostGainAccent, row(172, 48),
    )
}

@Composable
private fun LimiterGroup(dsp: BmwDspState, row: GroupRow) {
    GroupHeader(
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

/** A group's title in its colour, an optional switch at the far end, and a rule under both. */
@Composable
private fun GroupHeader(
    title: String,
    accent: Color,
    modifier: Modifier,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ArtLabel(title.uppercase(), Modifier.weight(1f), color = accent)
            if (checked != null && onCheckedChange != null) {
                BmwSwitch(checked = checked, onCheckedChange = onCheckedChange, contentDescription = title)
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(accent.copy(alpha = 0.45f)))
    }
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

private val HeadroomAccent = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR)
private val PostGainAccent = Color(BmwDashboardSkin.M_GREEN)
private val LimiterAccent = Color(BmwDashboardSkin.SLIDER_LIMITER_COLOR)

// Head-unit layout, in the 1280x480 editor's dp: two 333dp groups (a 150dp label column, then a
// stepper) with 120dp between them, centred across the content area (x 190..1230), and centred
// vertically on the taller group.
private const val GroupWidth = 333
private const val OutputX = 317
private const val LimiterX = 770
private const val GroupTop = 150
private const val HeaderHeight = 40
private val PhoneGroupGap = 60.dp
