package app.siphondsp.compose.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.theme.BmwTheme
import kotlinx.coroutines.launch

private val DisabledColor = Color(0xFF4A4C54)
private val ArrowBoxHeight = 30.dp
private val ArrowBoxWidth = 60.dp

/**
 * Explicit prev/next page buttons for a DSP workspace's toolbar line -- the tap-driven answer to
 * the swipe-vs-slider conflict a plain [androidx.compose.foundation.pager.HorizontalPager] can't
 * resolve on its own: these pages are full of full-width [BmwSlider] rows, and a swipe/flick
 * starting on one gets eaten by the slider's own drag detection instead of turning the page
 * (Compose has no equivalent of the View system's buffer-and-replay trick the old `DspPager` +
 * `PagerChildSwipeGate` used to solve this). A tap never has that ambiguity, so this sidesteps the
 * problem entirely rather than trying to out-guess it.
 *
 * Sits on the toolbar's right side, inset [R.dimen.dsp_pager_arrows_margin_end] from the true
 * screen edge rather than flush against it: the head unit's display is sunk into the dash and
 * boxed in by trim, so the extreme edge is physically hard to reach while driving. Sized
 * generously (60x30dp per arrow) rather than a cramped icon-sized target.
 *
 * Boxed in the same glass capsule shell as [BmwSegmentedControl] ([drawSegmentTrack], shared from
 * that file) -- the app's established "boxed toggle group" chrome, e.g. the old PEQ Graph/List
 * switch -- rather than bare floating icons, for visual consistency with every other toolbar-area
 * control.
 *
 * Hosted in `dsp_toolbar_actions` (see activity_parametric_eq.xml) by
 * GainLimiterActivity/CrossoverTiltActivity/NativeBmwCompressorActivity, to the right of
 * `dsp_status_strip`, which they leave visible (unlike ParametricEqualizerActivity, which uses
 * that same slot for `PeqToolbarActions` instead and hides the status strip).
 */
@Composable
fun DspPagerArrows(pagerState: PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val endInset = dimensionResource(R.dimen.dsp_pager_arrows_margin_end)
    // Slider-palette cyan (BmwDashboardSkin.SLIDER_DEFAULT_COLOR) rather than the segmented
    // control's own neutral grey rim -- these buttons sit against a dark backdrop and were hard
    // to pick out until called out in an accent color already used elsewhere in the app.
    val accent = BmwTheme.colors.sliderDefault
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier
                .padding(top = 25.dp, end = endInset)
                .drawBehind { drawSegmentTrack(rimColor = accent) }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val atStart = pagerState.currentPage == 0
            val atEnd = pagerState.currentPage == pagerState.pageCount - 1
            ArrowSegment(
                glyph = "‹",
                enabled = !atStart,
                accent = accent,
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
            )
            ArrowSegment(
                glyph = "›",
                enabled = !atEnd,
                accent = accent,
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            )
        }
    }
}

@Composable
private fun ArrowSegment(glyph: String, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(ArrowBoxHeight)
            .width(ArrowBoxWidth)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // material-icons isn't on the classpath here (see BmwSlider/PeqBandList) -- glyph it.
        // Sized up from 20sp: at that size the glyph read as lost inside a 60x30dp box instead of
        // filling it the way the box's own border chrome does.
        Text(glyph, fontSize = 28.sp, color = if (enabled) accent else DisabledColor)
    }
}
