package app.siphondsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.compose.screens.CompressorBandPage
import app.siphondsp.compose.screens.CompressorVisualiserPage
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.DspPager

/**
 * The pre-crossover multiband compressor screen: a full-bleed [DspPager] of five Compose pages --
 * the [CompressorVisualiserPage] (the `CompressorSurface` visualiser + the MBC enable / dry-wet
 * Mix master strip) and four [CompressorBandPage]s (enable, stereo link, a live GR meter, and the
 * threshold / ratio / knee / attack / release / makeup sliders). The per-bus brick-wall limiters
 * (`CompressorDriverPage`) now live on the Gains & Delay pager. See COMPOSE_MIGRATION_ROADMAP.md
 * Phase 7.
 *
 * Every control writes into the shared `NativeBmwDspValues` array via `BmwDspState` and
 * broadcasts the same way; the legacy per-output compressor this screen used to edit is retired
 * and force-disabled on load (NativeBmwDspValues.migrateDisableLegacyCompressorIfNeeded).
 */
class NativeBmwCompressorFragment : Fragment() {

    private lateinit var pagerContainer: FrameLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_native_bmw_compressor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pagerContainer = view.findViewById(R.id.compressor_slider_pager_container)
    }

    override fun onStart() {
        super.onStart()
        // Rebuilt on every entry so a fresh set of ComposeViews (and their BmwDspState) pick up
        // the load-time legacy-compressor migration and any edits made elsewhere.
        rebuild()
    }

    private fun rebuild() {
        val ctx = requireContext()
        // Read back whatever page the outgoing pager (if any) was on -- the rebuild below always
        // creates page 0 otherwise, which snapped this screen back to its first page on every
        // entry (including just backgrounding and returning to the app).
        val page = DspPager.currentPage(pagerContainer.getChildAt(0))
        val pages = buildList<View> {
            add(ComposeView(ctx).apply { setContent { CompressorVisualiserPage() } })
            for (band in 0 until NativeBmwDspValues.MBC_BAND_COUNT) {
                add(ComposeView(ctx).apply { setContent { CompressorBandPage(band) } })
            }
        }

        pagerContainer.removeAllViews()
        pagerContainer.addView(
            DspPager.build(ctx, pages, initialPage = page),
        )
    }
}
