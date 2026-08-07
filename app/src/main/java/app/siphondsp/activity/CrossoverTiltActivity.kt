package app.siphondsp.activity

import android.os.Bundle
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import app.siphondsp.R
import app.siphondsp.fragment.CrossoverTiltFragment
import app.siphondsp.fragment.RoutingFragment
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
        val current = if (routingMode) DspDestination.ROUTING else DspDestination.CROSSOVER_TILT
        supportActionBar?.title = if (routingMode) getString(R.string.action_routing) else getString(R.string.action_crossover_tilt)
        DspCrossNavBar.populate(this, findViewById<LinearLayout>(R.id.dsp_cross_nav), current)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, if (routingMode) RoutingFragment() else CrossoverTiltFragment())
                .commit()
        }
    }

    companion object {
        const val EXTRA_WORKSPACE_MODE = "dsp_workspace_mode"
        const val MODE_CROSSOVER = "crossover"
        const val MODE_ROUTING = "routing"
    }
}
