package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin

/**
 * First real (non-demo) leaf-screen port -- replaces `CrossoverTiltFragment`'s `tiltPage`
 * content (Phase 4 of COMPOSE_MIGRATION_ROADMAP.md). Parity target: a lean glass panel titled
 * "Tonality Tilt" with a section-enable toggle on `INDEX_TILT_ENABLED`, then Tilt amount
 * (-6..6 dB) and Tilt pivot (200..2000 Hz) sliders in the tilt accent colour. The section
 * toggle greys the two sliders when off.
 *
 * The other two Crossovers & Tilt pages (Crossovers, Mono Bass) stay on the View system for now;
 * `DspPager` hosts this as one `ComposeView` page among them.
 */
@Composable
fun TonalityTiltScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val tiltColor = Color(BmwDashboardSkin.SLIDER_TILT_COLOR)
    val enabled = dsp.isOn(NativeBmwDspValues.INDEX_TILT_ENABLED)

    BmwDspTheme {
        BmwPanel(
            title = stringResource(R.string.bmw_dsp_tilt_section),
            // Transparent, like the View builder's `lean` panels -- the workspace's own
            // full-screen dashboard backdrop shows through; the glass boxes keep their own
            // translucent dark fill so text stays legible over it.
            modifier = modifier.fillMaxWidth(),
            toggleChecked = enabled,
            onToggleChange = { on ->
                dsp.commit(NativeBmwDspValues.INDEX_TILT_ENABLED, if (on) 1f else 0f)
            },
            // 4dp (the View page()'s own left pad) + 80dp (dashboardPanel lean indent).
            leanStart = 84.dp,
            topContentGap = 40.dp,
            sliderLabels = listOf(
                stringResource(R.string.bmw_dsp_tilt_amount),
                stringResource(R.string.bmw_dsp_tilt_pivot),
            ),
        ) {
            // Sliders stay fully interactive when the section toggle is off -- parity with the
            // View builder, whose dashboardPanel toggle only writes INDEX_TILT_ENABLED and does
            // not grey its content.
            BmwSliderRow(
                label = stringResource(R.string.bmw_dsp_tilt_amount),
                value = dsp.get(NativeBmwDspValues.INDEX_TILT_AMOUNT),
                valueRange = -6f..6f,
                step = 0.1f,
                unit = "dB",
                accentColor = tiltColor,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_TILT_AMOUNT, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_TILT_AMOUNT, it) },
            )
            BmwSliderRow(
                label = stringResource(R.string.bmw_dsp_tilt_pivot),
                value = dsp.get(NativeBmwDspValues.INDEX_TILT_FREQ),
                valueRange = 200f..2000f,
                step = 1f,
                unit = "Hz",
                accentColor = tiltColor,
                onPreview = { dsp.preview(NativeBmwDspValues.INDEX_TILT_FREQ, it) },
                onCommit = { dsp.commit(NativeBmwDspValues.INDEX_TILT_FREQ, it) },
            )
        }
    }
}
