package app.siphondsp.activity

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.platform.ComposeView
import com.google.android.material.appbar.MaterialToolbar
import app.siphondsp.R
import app.siphondsp.compose.controls.DspPagerArrows
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.fragment.CrossoverTiltFragment
import app.siphondsp.fragment.OutputAllPassFragment
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

class CrossoverTiltActivity : DspWorkspaceActivity() {
    /** Shared with whichever fragment this hosts (see [pageCount]) so [DspPagerArrows] (hosted
     *  here, on the toolbar line) can drive its `HorizontalPager` -- see `dsp_toolbar_actions` in
     *  activity_parametric_eq.xml. Page count is fixed for the activity's lifetime (decided once
     *  below, from the launch intent), so a `by lazy` reading it after `onCreate` has run is safe. */
    val pagerState: PagerState by lazy { PagerState(currentPage = 0) { pageCount } }
    private var pageCount = CrossoverTiltFragment.PAGE_COUNT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parametric_eq)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val mode = intent.getStringExtra(EXTRA_WORKSPACE_MODE) ?: MODE_CROSSOVER
        val allPassMode = mode == MODE_ALLPASS
        pageCount = if (allPassMode) OutputAllPassFragment.PAGE_COUNT else CrossoverTiltFragment.PAGE_COUNT
        // DspDestination.ALLPASS's primary-nav tile opens All-pass directly, so All-pass shares
        // its nav identity here rather than leaving a gap.
        val current = if (allPassMode) DspDestination.ALLPASS else DspDestination.CROSSOVER_TILT
        // No toolbar title on the full-screen workspace -- the backdrop's lit rail tile already
        // says which screen this is.
        supportActionBar?.title = null
        DspCrossNavBar.populate(this, findViewById<LinearLayout>(R.id.dsp_cross_nav), current)

        if (savedInstanceState == null) {
            val fragment = if (allPassMode) OutputAllPassFragment() else CrossoverTiltFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, fragment)
                .commit()
        }

        findViewById<ComposeView>(R.id.dsp_toolbar_actions).apply {
            setContent { BmwDspTheme { DspPagerArrows(pagerState) } }
            visibility = View.VISIBLE
        }
    }

    companion object {
        const val EXTRA_WORKSPACE_MODE = "dsp_workspace_mode"
        const val MODE_CROSSOVER = "crossover"
        const val MODE_ALLPASS = "allpass"
    }
}
