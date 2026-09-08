package app.siphondsp.compose.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import app.siphondsp.R
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSectionHeader
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.MbcBandGrMeter

/**
 * Phase 5 of COMPOSE_MIGRATION_ROADMAP.md -- ports `GainLimiterFragment`'s Output page. Same
 * shape as [TonalityTiltScreen] (a lean [BmwPanel] of [BmwSliderRow]s) plus two firsts:
 * [BmwSectionHeader] (the "Limiter" sub-header + enable toggle) and an `AndroidView`-hosted
 * live meter ([LimiterGrMeter], the master-limiter gain-reduction bar).
 *
 * The car-diagram page stays on the View builder (Phase 6); `DspPager` hosts this ComposeView
 * alongside it.
 */
@Composable
fun HeadroomOutputScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val headroomColor = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR)
    val greenColor = Color(BmwDashboardSkin.M_GREEN)
    val blueColor = Color(BmwDashboardSkin.M_BLUE)
    val limiterOn = dsp.isOn(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED)
    val headroom = stringResource(R.string.bmw_dsp_headroom)

    BmwDspTheme {
        BmwPanel(
            title = "Output",
            modifier = modifier.fillMaxWidth(),
            // 4dp (the View page()'s left pad) + 40dp (dashboardPanel default lean indent).
            leanStart = 44.dp,
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
            )
            BmwSliderRow(
                label = "Post gain L",
                value = dsp.get(NativeBmwDspValues.INDEX_POST_GAIN_L),
                valueRange = -6f..6f, step = 0.5f, unit = "dB",
                accentColor = greenColor,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_POST_GAIN_L, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_POST_GAIN_L, it) },
            )
            BmwSliderRow(
                label = "Post gain R",
                value = dsp.get(NativeBmwDspValues.INDEX_POST_GAIN_R),
                valueRange = -6f..6f, step = 0.5f, unit = "dB",
                accentColor = greenColor,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_POST_GAIN_R, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_POST_GAIN_R, it) },
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
            )
            LimiterGrMeter(modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        }
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
