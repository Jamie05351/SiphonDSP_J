package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.ArtTitle
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSectionHeader
import app.siphondsp.compose.controls.BmwSliderRow
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
 * Phase 5 of COMPOSE_MIGRATION_ROADMAP.md -- ports `GainLimiterFragment`'s Output page. Same
 * shape as [TonalityTiltScreen] (a lean [BmwPanel] of [BmwSliderRow]s) plus two firsts:
 * [BmwSectionHeader] (the "Limiter" sub-header + enable toggle) and an `AndroidView`-hosted
 * live meter ([LimiterGrMeter], the master-limiter gain-reduction bar).
 *
 * A Compose `HorizontalPager` (see `GainLimiterFragment`) hosts this alongside the Gains & Delay
 * diagram page and the bus-limiter page ([CompressorDriverPage], moved here from the compressor
 * pager).
 */
@Composable
fun HeadroomOutputScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val headroomColor = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR)
    val greenColor = Color(BmwDashboardSkin.M_GREEN)
    val blueColor = Color(BmwDashboardSkin.M_BLUE)
    val limiterOn = dsp.isOn(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED)
    val headroom = stringResource(R.string.bmw_dsp_headroom)

    if (LocalContext.current.isHeadUnitDisplay()) {
        BmwDspTheme { HeadUnitOutputPage(dsp, headroom, modifier) }
        return
    }

    BmwDspTheme {
        BmwPanel(
            title = "Output",
            modifier = modifier.fillMaxWidth(),
            // Match the centered 20dp content frame used by every DSP workspace page.
            leanStart = 20.dp,
            leanEnd = 20.dp,
            // dashboardPanel default topContentGapDp -- this panel doesn't bump it.
            topContentGap = 2.dp,
            sliderLabels = listOf(headroom, "Post gain L", "Post gain R", "Threshold", "Limiter"),
        ) {
            BmwSliderRow(
                label = headroom,
                value = dsp.get(NativeBmwDspValues.INDEX_HEADROOM),
                valueRange = -12f..0f, step = 1f, unit = "dB",
                accentColor = headroomColor,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_HEADROOM, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_HEADROOM, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_HEADROOM, it) },
            )
            BmwSliderRow(
                label = "Post gain L",
                value = dsp.get(NativeBmwDspValues.INDEX_POST_GAIN_L),
                valueRange = -6f..6f, step = 0.5f, unit = "dB",
                accentColor = greenColor,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_POST_GAIN_L, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_POST_GAIN_L, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_POST_GAIN_L, it) },
            )
            BmwSliderRow(
                label = "Post gain R",
                value = dsp.get(NativeBmwDspValues.INDEX_POST_GAIN_R),
                valueRange = -6f..6f, step = 0.5f, unit = "dB",
                accentColor = greenColor,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_POST_GAIN_R, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_POST_GAIN_R, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_POST_GAIN_R, it) },
            )
            BmwSectionHeader(
                title = "Limiter",
                accentColor = blueColor,
                fontSize = 13.sp,
                toggleChecked = limiterOn,
                onToggleChange = { on ->
                    dsp.commit(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED, if (on) 1f else 0f)
                },
            )
            BmwSliderRow(
                label = "Threshold",
                value = dsp.get(NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD),
                valueRange = -12f..0f, step = 0.5f, unit = "dB",
                accentColor = blueColor,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD, it) },
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD, it) },
            )
            LimiterGrMeter(modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        }
    }
}

/** Head unit: the same controls placed on the workspace art (REW/_UI/submenu_layout_editor.html),
 *  so the page fits the 480 dp screen without scrolling. */
@Composable
private fun HeadUnitOutputPage(dsp: BmwDspState, headroom: String, modifier: Modifier) {
    val headroomColor = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR)
    val greenColor = Color(BmwDashboardSkin.M_GREEN)
    val blueColor = Color(BmwDashboardSkin.M_BLUE)

    WorkspaceArtBox(modifier.fillMaxSize()) {
        ArtTitle("Output", Modifier.artRect(artDp(190, 62, 520, 30)))
        DspArtSlider(
            dsp, headroom, NativeBmwDspValues.INDEX_HEADROOM, -12f..0f, 1f, "dB", headroomColor,
            Modifier.artRect(artDp(190, 100, 1040, 50)),
        )
        DspArtSlider(
            dsp, "Post gain L", NativeBmwDspValues.INDEX_POST_GAIN_L, -6f..6f, 0.5f, "dB", greenColor,
            Modifier.artRect(artDp(190, 156, 1040, 50)),
        )
        DspArtSlider(
            dsp, "Post gain R", NativeBmwDspValues.INDEX_POST_GAIN_R, -6f..6f, 0.5f, "dB", greenColor,
            Modifier.artRect(artDp(190, 212, 1040, 50)),
        )
        ArtSwitchRow(
            label = "Limiter",
            checked = dsp.isOn(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED),
            onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED, if (on) 1f else 0f) },
            labelColor = blueColor,
            modifier = Modifier.artRect(artDp(190, 270, 400, 38)),
        )
        DspArtSlider(
            dsp, "Threshold", NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD, -12f..0f, 0.5f, "dB", blueColor,
            Modifier.artRect(artDp(190, 314, 1040, 50)),
        )
        ArtMeterRow(Modifier.artRect(artDp(190, 372, 1040, 36))) { LimiterGrMeter(it) }
    }
}

/**
 * The master-limiter gain-reduction meter -- the View `MbcBandGrMeter` (stage = LIMITER) kept
 * as-is via `AndroidView` (roadmap section 2 / Phase 5). Polled at ~30fps while the composition
 * is at least STARTED, mirroring the fragment's old `onStart`/`onStop` `Handler` loop.
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
