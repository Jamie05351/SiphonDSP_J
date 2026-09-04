package app.siphondsp.activity

import android.os.Bundle
import app.siphondsp.R
import app.siphondsp.databinding.ActivityParametricEqBinding
import app.siphondsp.fragment.ParametricEqualizerFragment
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

class ParametricEqualizerActivity : DspWorkspaceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityParametricEqBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val fragment = if (savedInstanceState == null) {
            ParametricEqualizerFragment.newInstance().also {
                supportFragmentManager.beginTransaction().replace(R.id.params, it).commitNow()
            }
        } else {
            supportFragmentManager.findFragmentById(R.id.params) as? ParametricEqualizerFragment
                ?: error("ParametricEqualizerFragment missing from restored state")
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        DspCrossNavBar.populate(this, binding.dspCrossNav, DspDestination.PARAMETRIC_EQ) {
            fragment.canSwitchDspScreens()
        }

        // Skin once after fragment restoration/inflation. This is deliberately UI-only and
        // is not attached to onStart/onResume or any DSP/service lifecycle callback. styleTree
        // only (not styleWorkspace): the background half is now painted by DspCrossNavBar's
        // per-destination workspace backdrop above, on R.id.dsp_workspace_content.
        binding.root.post { BmwDashboardSkin.styleTree(binding.root) }
    }
}
