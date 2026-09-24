package app.siphondsp.activity

import android.os.Bundle
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.platform.ComposeView
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtPagerFinder
import app.siphondsp.compose.controls.DspPagerArrows
import app.siphondsp.compose.controls.WorkspaceArt
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.fragment.NativeBmwCompressorFragment
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination
import app.siphondsp.view.isHeadUnitDisplay
import com.google.android.material.appbar.MaterialToolbar

class NativeBmwCompressorActivity : DspWorkspaceActivity() {
    /** Shared with [NativeBmwCompressorFragment]'s `HorizontalPager` so [DspPagerArrows] (hosted
     *  here, on the toolbar line) can drive it -- see `dsp_toolbar_actions` in
     *  activity_parametric_eq.xml. */
    val pagerState: PagerState by lazy { PagerState(currentPage = 0) { NativeBmwCompressorFragment.PAGE_COUNT } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parametric_eq)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Full-screen workspace: no toolbar title (the manifest android:label would otherwise
        // show); the backdrop's lit rail tile identifies the screen.
        supportActionBar?.title = null
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        DspCrossNavBar.populate(this, findViewById<ComposeView>(R.id.dsp_cross_nav), DspDestination.COMPRESSOR)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, NativeBmwCompressorFragment())
                .commitNow()
        }

        findViewById<ComposeView>(R.id.dsp_toolbar_actions).apply {
            val headUnit = isHeadUnitDisplay()
            setContent {
                BmwDspTheme {
                    if (headUnit) ArtPagerFinder(pagerState, COMPRESSOR_PAGE_LABELS, WorkspaceArt.finder5)
                    else DspPagerArrows(pagerState)
                }
            }
            visibility = android.view.View.VISIBLE
        }

        // Apply the same BMW dashboard chrome as the other DSP workspaces once the fragment
        // view is present. This is visual-only and deliberately not tied to audio lifecycle.
        // styleTree only (not styleWorkspace): the background half is now painted by
        // DspCrossNavBar's per-destination full-screen workspace backdrop above (R.id.dsp_workspace_backdrop).
        findViewById<android.view.View>(android.R.id.content).post {
            BmwDashboardSkin.styleTree(findViewById(android.R.id.content))
        }
    }

    private companion object {
        /** The visualiser page, then one per MBC band (NativeBmwCompressorFragment's pager order). */
        val COMPRESSOR_PAGE_LABELS = listOf("OVERVIEW") + List(NativeBmwDspValues.MBC_BAND_COUNT) { "B${it + 1}" }
    }
}
