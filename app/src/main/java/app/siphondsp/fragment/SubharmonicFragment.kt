package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import app.siphondsp.compose.screens.SubharmonicBandPage
import app.siphondsp.compose.screens.SubharmonicGlobalPage
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspPager

/**
 * Subharmonic synthesizer full-control workspace (Task 5). Four swipe pages, all Compose, same
 * `DspPager` host shape as [OutputAllPassFragment] / [CrossoverTiltFragment]:
 * - Three [SubharmonicBandPage]s (band 0 "Low", band 1 "Upper", band 2 "Extension" -- matching
 *   the defaults documented in NativeBmwDspProcessor.h's kConfigSize comment).
 * - One [SubharmonicGlobalPage]: sub ceiling, the live suggested-preamp-cut readout, and
 *   per-output level + momentary solo.
 */
class SubharmonicFragment : Fragment() {
    private lateinit var container: FrameLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        this.container = FrameLayout(requireContext())
        rebuild()
        return this.container
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every resume so a fresh set of ComposeViews (and their BmwDspState) pick up
        // edits made elsewhere while this screen was stopped.
        if (::container.isInitialized) rebuild()
    }

    private fun rebuild() {
        val ctx = requireContext()
        val lowAccent = Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR)
        val midAccent = Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR)
        val extAccent = Color(BmwDashboardSkin.SLIDER_DEFAULT_COLOR)

        fun bandPage(band: Int, title: String, accent: Color, freqLoRange: ClosedFloatingPointRange<Float>, freqHiRange: ClosedFloatingPointRange<Float>): View =
            ComposeView(ctx).apply {
                setContent { SubharmonicBandPage(band, title, accent, freqLoRange, freqHiRange) }
            }

        // Read back whatever page the outgoing pager (if any) was on -- the rebuild below always
        // creates page 0 otherwise, which snapped this screen back to its first page on every
        // resume (including just backgrounding and returning to the app).
        val page = DspPager.currentPage(container.getChildAt(0))

        container.removeAllViews()
        container.addView(
            DspPager.build(
                ctx,
                listOf(
                    bandPage(0, "Band 1 · Low", lowAccent, 20f..120f, 30f..160f),
                    bandPage(1, "Band 2 · Upper", midAccent, 40f..150f, 60f..200f),
                    bandPage(2, "Band 3 · Extension", extAccent, 60f..200f, 90f..300f),
                    ComposeView(ctx).apply { setContent { SubharmonicGlobalPage() } },
                ),
                initialPage = page,
            ),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }
}
