package app.siphondsp.activity

import android.os.Bundle
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import app.siphondsp.R
import app.siphondsp.fragment.NativeBmwCompressorFragment
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

class NativeBmwCompressorActivity : DspWorkspaceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parametric_eq)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        DspCrossNavBar.populate(this, findViewById<LinearLayout>(R.id.dsp_cross_nav), DspDestination.COMPRESSOR)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, NativeBmwCompressorFragment())
                .commitNow()
        }

        // Apply the same BMW dashboard chrome as the other DSP workspaces once the fragment
        // view is present. This is visual-only and deliberately not tied to audio lifecycle.
        findViewById<android.view.View>(android.R.id.content).post {
            BmwDashboardSkin.styleWorkspace(findViewById(android.R.id.content))
        }
    }
}
