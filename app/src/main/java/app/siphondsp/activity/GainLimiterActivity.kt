package app.siphondsp.activity

import android.os.Bundle
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.platform.ComposeView
import com.google.android.material.appbar.MaterialToolbar
import app.siphondsp.R
import app.siphondsp.compose.controls.DspPagerArrows
import app.siphondsp.fragment.GainLimiterPageFinder
import app.siphondsp.view.isHeadUnitDisplay
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.fragment.GainLimiterFragment
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

class GainLimiterActivity : DspWorkspaceActivity() {
    /** Shared with [GainLimiterFragment]'s `HorizontalPager` so [DspPagerArrows] (hosted here, on
     *  the toolbar line) can drive it -- see `dsp_toolbar_actions` in activity_parametric_eq.xml. */
    val pagerState: PagerState by lazy { PagerState(currentPage = 0) { GainLimiterFragment.PAGE_COUNT } }

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
        DspCrossNavBar.populate(this, findViewById<ComposeView>(R.id.dsp_cross_nav), DspDestination.GAINS_DELAY)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, GainLimiterFragment())
                .commit()
        }

        findViewById<ComposeView>(R.id.dsp_toolbar_actions).apply {
            val headUnit = isHeadUnitDisplay()
            setContent {
                BmwDspTheme { if (headUnit) GainLimiterPageFinder(pagerState) else DspPagerArrows(pagerState) }
            }
            visibility = android.view.View.VISIBLE
        }
    }
}
