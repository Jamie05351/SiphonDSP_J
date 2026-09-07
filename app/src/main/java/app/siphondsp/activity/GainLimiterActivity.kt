package app.siphondsp.activity

import android.os.Bundle
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import app.siphondsp.R
import app.siphondsp.fragment.GainLimiterFragment
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination
import app.siphondsp.view.DspWorkspaceFormFactor

class GainLimiterActivity : DspWorkspaceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parametric_eq)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Head-unit full-screen: no toolbar title (the backdrop's lit rail tile identifies the
        // screen). On a phone the manifest android:label title stays -- see DspCrossNavBar.
        if (DspWorkspaceFormFactor.isHeadUnit(this)) supportActionBar?.title = null
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        DspCrossNavBar.populate(this, findViewById<LinearLayout>(R.id.dsp_cross_nav), DspDestination.GAINS_DELAY)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, GainLimiterFragment())
                .commit()
        }
    }
}
