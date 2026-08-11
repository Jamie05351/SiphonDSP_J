package app.siphondsp.activity

import android.os.Bundle
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import app.siphondsp.R
import app.siphondsp.fragment.GainLimiterFragment
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

class GainLimiterActivity : DspWorkspaceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parametric_eq)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        DspCrossNavBar.populate(this, findViewById<LinearLayout>(R.id.dsp_cross_nav), DspDestination.GAINS_DELAY)
        setUpWorkspaceSidebarActions()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, GainLimiterFragment())
                .commit()
        }
    }
}
