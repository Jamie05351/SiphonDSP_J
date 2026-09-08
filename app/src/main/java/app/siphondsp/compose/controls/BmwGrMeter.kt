package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.siphondsp.view.MbcBandGrMeter

/**
 * The View `MbcBandGrMeter` (a thin gain-reduction bar) hosted via `AndroidView` -- the roadmap
 * decision for the live meters (section 2 / "AndroidView for the hard Canvas views"). The caller
 * owns the poll and passes the current GR value in dB; [stage] picks the meter's scale/labels.
 */
@Composable
fun BmwGrMeter(
    gainReductionDb: Float,
    modifier: Modifier = Modifier,
    stage: MbcBandGrMeter.Stage = MbcBandGrMeter.Stage.COMPRESSOR_BAND,
) {
    AndroidView(
        factory = { ctx -> MbcBandGrMeter(ctx).apply { this.stage = stage } },
        update = {
            it.stage = stage
            it.setGainReductionDb(gainReductionDb)
        },
        modifier = modifier.fillMaxWidth(),
    )
}
