package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtSwitchRow
import app.siphondsp.compose.controls.BmwPanel
import app.siphondsp.compose.controls.BmwSliderRow
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay

/**
 * First real (non-demo) leaf-screen port -- replaces `CrossoverTiltFragment`'s `tiltPage`
 * content (Phase 4 of COMPOSE_MIGRATION_ROADMAP.md). Parity target: a lean glass panel titled
 * "Tonality Tilt" with a section-enable toggle on `INDEX_TILT_ENABLED`, then Tilt amount
 * (-6..6 dB) and Tilt pivot (200..2000 Hz) sliders in the tilt accent colour. The section
 * toggle greys the two sliders when off.
 *
 * The other Crossovers & Tilt pages ([CrossoverLowMidPage], [CrossoverMidHighPage]) are Compose
 * too; a Compose `HorizontalPager` (see `CrossoverTiltFragment`) hosts all three.
 */
@Composable
fun TonalityTiltScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val tiltColor = Color(BmwDashboardSkin.SLIDER_TILT_COLOR)
    val enabled = dsp.isOn(NativeBmwDspValues.INDEX_TILT_ENABLED)

    if (LocalContext.current.isHeadUnitDisplay()) {
        // Head unit: placed on the workspace art (REW/_UI/submenu_layout_editor.html). The section
        // switch that lives in the phone panel's header gets its own row above the sliders.
        BmwDspTheme {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                ArtSwitchRow(
                    label = stringResource(R.string.bmw_dsp_tilt_section),
                    checked = enabled,
                    onCheckedChange = { on -> dsp.commit(NativeBmwDspValues.INDEX_TILT_ENABLED, if (on) 1f else 0f) },
                    labelWidth = 200.dp,
                    labelColor = Color.White,
                    modifier = Modifier.artRect(artDp(184, 84, 400, 40)),
                )
                DspArtSlider(
                    dsp, stringResource(R.string.bmw_dsp_tilt_amount), NativeBmwDspValues.INDEX_TILT_AMOUNT,
                    -6f..6f, 0.1f, "dB", tiltColor, Modifier.artRect(artDp(184, 140, 1040, 54)), valueWidth = 84.dp,
                )
                DspArtSlider(
                    dsp, stringResource(R.string.bmw_dsp_tilt_pivot), NativeBmwDspValues.INDEX_TILT_FREQ,
                    200f..2000f, 1f, "Hz", tiltColor, Modifier.artRect(artDp(184, 228, 1040, 54)), valueWidth = 84.dp,
                )
            }
        }
        return
    }

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
            leanStart = 20.dp,
            leanEnd = 20.dp,
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
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_TILT_AMOUNT, it) },
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
                onValueEntered = { dsp.commit(NativeBmwDspValues.INDEX_TILT_FREQ, it) },
            )
        }
    }
}
