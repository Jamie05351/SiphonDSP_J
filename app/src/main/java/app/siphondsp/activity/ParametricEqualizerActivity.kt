package app.siphondsp.activity

import android.os.Bundle
import android.view.View
import app.siphondsp.R
import app.siphondsp.compose.screens.PeqToolbarActions
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.databinding.ActivityParametricEqBinding
import app.siphondsp.fragment.ParametricEqualizerFragment
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.DspCrossNavBar
import app.siphondsp.view.DspDestination

class ParametricEqualizerActivity : DspWorkspaceActivity() {

    /**
     * Shared with [ParametricEqualizerFragment]'s `ParametricEqScreen` -- one instance so the
     * Pre EQ/Low/Mid scope switch (hosted here, on the toolbar line) and the graph/list (hosted
     * there, in the fragment) stay in sync. `PeqStateHolder`'s properties are `mutableStateOf`,
     * so Compose observes changes across both composition roots as long as it's the same object.
     */
    val peqStateHolder: PeqStateHolder by lazy { PeqStateHolder(applicationContext) }

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
        // Full-screen workspace: no toolbar title (the manifest android:label would otherwise
        // show); the backdrop's lit rail tile identifies the screen.
        supportActionBar?.title = null
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        DspCrossNavBar.populate(this, binding.dspCrossNav, DspDestination.PARAMETRIC_EQ) {
            fragment.canSwitchDspScreens()
        }

        // The Pre EQ/Low/Mid + action-chip row takes the toolbar line on this screen only (see
        // activity_parametric_eq.xml) -- there's no room left for the bypass-state strip once
        // it's showing, so it's hidden here specifically; every other DSP workspace screen still
        // shows it untouched.
        binding.dspStatusStrip.visibility = View.GONE
        binding.peqToolbarActions.setContent {
            BmwDspTheme { PeqToolbarActions(peqStateHolder) }
        }
        binding.peqToolbarActions.visibility = View.VISIBLE

        // Skin once after fragment restoration/inflation. This is deliberately UI-only and
        // is not attached to onStart/onResume or any DSP/service lifecycle callback. styleTree
        // only (not styleWorkspace): the background half is now painted by DspCrossNavBar's
        // per-destination full-screen workspace backdrop above (R.id.dsp_workspace_backdrop).
        binding.root.post { BmwDashboardSkin.styleTree(binding.root) }
    }
}
