package app.siphondsp.activity

import android.os.Bundle
import android.view.Menu
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import app.siphondsp.R
import app.siphondsp.fragment.NativeBmwCompressorFragment
import app.siphondsp.model.NativeBmwCompressorState
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

class NativeBmwCompressorActivity : DspWorkspaceActivity() {
    private var bindingEnableSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parametric_eq)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        DspCrossNavBar.populate(this, findViewById<LinearLayout>(R.id.dsp_cross_nav), DspDestination.COMPRESSOR)
        setUpWorkspaceSidebarActions()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_native_bmw_compressor, menu)
        val enableSwitch = menu.findItem(R.id.menu_compressor_enable)?.actionView as? MaterialSwitch
        enableSwitch?.setOnCheckedChangeListener { _, checked ->
            if (bindingEnableSwitch) return@setOnCheckedChangeListener
            NativeBmwCompressorState.load(this).copy(enabled = checked).persistAndApply(this)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val enableSwitch = menu.findItem(R.id.menu_compressor_enable)?.actionView as? MaterialSwitch
        val currentlyEnabled = NativeBmwCompressorState.load(this).enabled
        if (enableSwitch != null && enableSwitch.isChecked != currentlyEnabled) {
            bindingEnableSwitch = true
            enableSwitch.isChecked = currentlyEnabled
            bindingEnableSwitch = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
    }
}
