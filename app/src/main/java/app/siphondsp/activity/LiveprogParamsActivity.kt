package app.siphondsp.activity

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import app.siphondsp.R
import app.siphondsp.databinding.ActivityLiveprogParamsBinding
import app.siphondsp.fragment.LiveprogParamsFragment

class LiveprogParamsActivity : BaseActivity() {
    private var showReset = false
    private var enableReset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityLiveprogParamsBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            val target = intent.getStringExtra(EXTRA_TARGET_FILE) ?: ""
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.params, LiveprogParamsFragment.newInstance(target))
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_liveprog_params, menu)
        val reset = menu.findItem(R.id.action_reset)
        reset?.isVisible = showReset
        reset?.isEnabled = enableReset
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset -> {
                (supportFragmentManager.findFragmentById(R.id.params) as? LiveprogParamsFragment)?.restoreDefaults()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun setResetVisible(b: Boolean) {
        showReset = b
        invalidateOptionsMenu()
    }

    fun setResetEnabled(b: Boolean) {
        enableReset = b
        invalidateOptionsMenu()
    }

    companion object {
        const val EXTRA_TARGET_FILE = "TargetFile"
    }
}