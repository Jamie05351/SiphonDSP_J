package app.siphondsp.activity

import android.os.Bundle
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import app.siphondsp.R
import app.siphondsp.fragment.CrossoverTiltFragment
import app.siphondsp.fragment.OutputAllPassFragment
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
        val allPassMode = mode == MODE_ALLPASS
        // DspDestination.ALLPASS's primary-nav tile opens All-pass directly, so All-pass shares
        // its nav identity here rather than leaving a gap.
        val current = if (allPassMode) DspDestination.ALLPASS else DspDestination.CROSSOVER_TILT
        supportActionBar?.title = getString(
            if (allPassMode) R.string.action_output_allpass else R.string.action_crossover_tilt
        )
        DspCrossNavBar.populate(this, findViewById<LinearLayout>(R.id.dsp_cross_nav), current)

        if (savedInstanceState == null) {
            val fragment = if (allPassMode) OutputAllPassFragment() else CrossoverTiltFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, fragment)
                .commit()
        }
    }

    companion object {
        const val EXTRA_WORKSPACE_MODE = "dsp_workspace_mode"
        const val MODE_CROSSOVER = "crossover"
        const val MODE_ALLPASS = "allpass"
    }
}
