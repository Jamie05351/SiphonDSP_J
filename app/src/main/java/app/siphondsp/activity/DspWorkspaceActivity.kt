package app.siphondsp.activity

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.siphondsp.view.DspWorkspaceFormFactor

/**
 * Shared chrome for the dedicated BMW DSP workspaces. On the head unit (see
 * [DspWorkspaceFormFactor.isHeadUnit]) these screens run **full-screen**: the per-destination
 * backdrop art (drawable-mdpi/dsp_workspace_backdrop_*.png, set by DspCrossNavBar.populate() on
 * R.id.dsp_workspace_backdrop) is authored at 1280x480 with no system bars, and the toolbar
 * floats over it transparently. On a phone / tablet / portrait the system bars stay and the
 * toolbar is opaque and titled -- see DspCrossNavBar.populate().
 *
 * There is no power control here -- DSP power lives solely on MainActivity's bottom bar. Settings,
 * Presets, Revert and Blocklist are likewise reachable only from that bottom bar.
 */
abstract class DspWorkspaceActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DspWorkspaceFormFactor.isHeadUnit(this)) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && DspWorkspaceFormFactor.isHeadUnit(this)) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
