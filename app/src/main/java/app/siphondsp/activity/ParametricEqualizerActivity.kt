package app.siphondsp.activity

import android.os.Bundle
import app.siphondsp.R
import app.siphondsp.databinding.ActivityParametricEqBinding
import app.siphondsp.fragment.ParametricEqualizerFragment
import app.siphondsp.view.BmwDashboardSkin

class ParametricEqualizerActivity : DspWorkspaceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityParametricEqBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.params, ParametricEqualizerFragment.newInstance())
                .commitNow()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Keep Graph/List where the PEQ landscape layout owns it: the dedicated left-side
        // mode strip. Moving it into the top-right content area caused it to cover the
        // Add/Edit Filter action buttons on the 1280x480 workspace.
        //
        // Skin once after fragment restoration/inflation. This is deliberately UI-only and
        // is not attached to onStart/onResume or any DSP/service lifecycle callback.
        binding.root.post { BmwDashboardSkin.styleWorkspace(binding.root) }
    }
}
