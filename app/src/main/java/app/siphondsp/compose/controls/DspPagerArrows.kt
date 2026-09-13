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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val EnabledColor = Color(0xFFB0B2BA)
private val DisabledColor = Color(0xFF4A4C54)
private val ArrowBoxHeight = 24.dp
private val ArrowBoxWidth = 28.dp

/**
 * Explicit prev/next page buttons for a DSP workspace's toolbar line -- the tap-driven answer to
 * the swipe-vs-slider conflict a plain [androidx.compose.foundation.pager.HorizontalPager] can't
 * resolve on its own: these pages are full of full-width [BmwSlider] rows, and a swipe/flick
 * starting on one gets eaten by the slider's own drag detection instead of turning the page
 * (Compose has no equivalent of the View system's buffer-and-replay trick the old `DspPager` +
 * `PagerChildSwipeGate` used to solve this). A tap never has that ambiguity, so this sidesteps the
 * problem entirely rather than trying to out-guess it.
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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier
                .padding(top = 25.dp, end = 16.dp)
                .drawBehind { drawSegmentTrack() }
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val atStart = pagerState.currentPage == 0
            val atEnd = pagerState.currentPage == pagerState.pageCount - 1
            ArrowSegment(
                glyph = "‹",
                enabled = !atStart,
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
            )
            ArrowSegment(
                glyph = "›",
                enabled = !atEnd,
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            )
        }
    }
}

@Composable
private fun ArrowSegment(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(ArrowBoxHeight)
            .width(ArrowBoxWidth)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // material-icons isn't on the classpath here (see BmwSlider/PeqBandList) -- glyph it.
        Text(glyph, fontSize = 20.sp, color = if (enabled) EnabledColor else DisabledColor)
    }
}
