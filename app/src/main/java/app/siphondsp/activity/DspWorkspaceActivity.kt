package app.siphondsp.activity

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.siphondsp.R

/**
 * Shared chrome for the dedicated BMW DSP workspaces. These screens run **full-screen**: the
 * per-destination backdrop art (drawable-mdpi-v4/dsp_workspace_backdrop_*.png, set by
 * DspCrossNavBar.populate() on R.id.dsp_workspace_backdrop) is authored at the head unit's full
 * 1280x480 with no system bars, and the toolbar floats over it transparently. So this base hides
 * the system bars and lets content draw edge to edge.
 *
 * There is no power control here -- DSP power lives solely on MainActivity's bottom bar. Settings,
 * Presets, Revert and Blocklist are likewise reachable only from that bottom bar.
 */
abstract class DspWorkspaceActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
            // The stock AppCompat back arrow (24dp) was reading small and, on the head unit,
            // sitting close enough to the physical bezel to look cropped. +25% (30dp) on every
            // workspace screen -- see ic_dsp_back_arrow and header_toolbar_height's paired
            // 10dp top-padding fix. Reapplied on every focus change (idempotent) rather than
            // once in onCreate, since setDisplayHomeAsUpEnabled(true) -- called by each
            // subclass afterward -- would otherwise overwrite it with the stock icon.
            supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_dsp_back_arrow)
        }
    }
}
