package app.siphondsp.activity

import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.siphondsp.R

/**
 * Shared chrome for the dedicated BMW DSP workspaces. These screens run **full-screen**: the
 * faceplate (DspWorkspaceBackdrop, set by DspCrossNavBar.populate() on R.id.dsp_workspace_backdrop)
 * is laid out for the head unit's full 1280x480 with no system bars, and the toolbar floats over it
 * transparently. So this base hides the system bars and lets content draw edge to edge.
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

    /** Every workspace installs its toolbar here, so this is where its back arrow gets enlarged. */
    override fun setSupportActionBar(toolbar: Toolbar?) {
        super.setSupportActionBar(toolbar)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_workspace_back_32dp)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
