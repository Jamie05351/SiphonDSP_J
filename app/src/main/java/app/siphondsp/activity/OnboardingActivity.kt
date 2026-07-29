package app.siphondsp.activity

import android.os.Bundle
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import app.siphondsp.BuildConfig
import app.siphondsp.R
import app.siphondsp.databinding.ActivityOnboardingBinding
import app.siphondsp.fragment.OnboardingFragment
import app.siphondsp.utils.extensions.ContextExtensions.showAlert
import app.siphondsp.utils.isRoot
import app.siphondsp.utils.isRootless


class OnboardingActivity : BaseActivity(){
    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var fragment: OnboardingFragment

    override fun onCreate(savedInstanceState:Bundle?)
    {
        WindowCompat.setDecorFitsSystemWindows(window,false)
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) { navigateUp() }

        fragment = if (savedInstanceState != null) {
            supportFragmentManager.getFragment(savedInstanceState, "onboarding") as OnboardingFragment
        } else {
            OnboardingFragment.newInstance()
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.onboarding_fragment_container, fragment)
            .commit()

        // Root: onboarding currently not required, except when setting up DUMP permission for enhanced processing
        // Plugin: no setup required
        if(!isRootless() && !intent.getBooleanExtra(EXTRA_ROOT_SETUP_DUMP_PERM, false)) {
            this.finish()
            return
        }

        // Request to fix permissions using the wizard
        if(intent.getBooleanExtra(EXTRA_FIX_PERMS, false)){
            showAlert(R.string.onboarding_fix_permissions_title, R.string.onboarding_fix_permissions)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        supportFragmentManager.putFragment(outState, "onboarding", fragment)
    }

    private fun navigateUp(): Boolean
    {
        val finished = !fragment.onBackPressed()
        if(finished)
            finish()
        return finished
    }

    override fun onSupportNavigateUp(): Boolean
    {
        return navigateUp()
    }

    companion object
    {
        const val EXTRA_FIX_PERMS = "FixPermissions"
        const val EXTRA_ROOT_SETUP_DUMP_PERM = "RootSetupDumpPerm"
        const val EXTRA_ROOTLESS_REDO_ADB_SETUP = "RootlessRedoAdbSetup"
    }
}