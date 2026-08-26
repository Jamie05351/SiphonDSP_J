package app.siphondsp.activity

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import app.siphondsp.R
import app.siphondsp.fragment.CrossoverTiltFragment
import app.siphondsp.fragment.OutputAllPassFragment
import app.siphondsp.fragment.RoutingFragment
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

class CrossoverTiltActivity : DspWorkspaceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parametric_eq)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val mode = intent.getStringExtra(EXTRA_WORKSPACE_MODE) ?: MODE_CROSSOVER
        val routingMode = mode == MODE_ROUTING
        val allPassMode = mode == MODE_ALLPASS
        // DspDestination.ROUTING's primary nav tile now opens All-pass directly (see
        // DspDestination.kt). Routing itself (the matrix diagram/sliders) has no nav entry point
        // of its own any more -- reachable only if something still launches MODE_ROUTING
        // directly -- so it shares All-pass's nav identity here rather than leaving a gap.
        val current = if (routingMode || allPassMode) DspDestination.ROUTING else DspDestination.CROSSOVER_TILT
        supportActionBar?.title = when {
            allPassMode -> getString(R.string.action_output_allpass)
            routingMode -> getString(R.string.action_routing)
            else -> getString(R.string.action_crossover_tilt)
        }
        DspCrossNavBar.populate(this, findViewById<LinearLayout>(R.id.dsp_cross_nav), current)

        if (savedInstanceState == null) {
            val fragment = when {
                allPassMode -> OutputAllPassFragment()
                routingMode -> RoutingFragment()
                else -> CrossoverTiltFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, fragment)
                .commit()
        }

        // Same continuous chrome background as the other DSP workspaces, so the toolbar strip,
        // sidebar, and content area read as one panel instead of leaving a seam.
        findViewById<View>(android.R.id.content).post {
            BmwDashboardSkin.paintWorkspaceBackground(findViewById(android.R.id.content))
        }
    }

    companion object {
        const val EXTRA_WORKSPACE_MODE = "dsp_workspace_mode"
        const val MODE_CROSSOVER = "crossover"
        const val MODE_ROUTING = "routing"
        const val MODE_ALLPASS = "allpass"
    }
}
