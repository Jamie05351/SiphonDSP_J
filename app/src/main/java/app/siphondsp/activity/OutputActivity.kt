package app.siphondsp.activity

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import app.siphondsp.R
import app.siphondsp.fragment.OutputFragment
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination
import com.google.android.material.appbar.MaterialToolbar

/** The Output workspace: one page ([OutputFragment]), so no page finder on the toolbar line. */
class OutputActivity : DspWorkspaceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parametric_eq)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Full-screen workspace: no toolbar title; the rail's lit tile identifies the screen.
        supportActionBar?.title = null
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        DspCrossNavBar.populate(this, findViewById<ComposeView>(R.id.dsp_cross_nav), DspDestination.OUTPUT)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, OutputFragment())
                .commit()
        }
    }
}
