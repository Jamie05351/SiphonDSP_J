package app.siphondsp.activity

import android.os.Bundle
import app.siphondsp.compose.screens.NativeTruthScreen
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.databinding.ActivityNativeTruthBinding

/**
 * Read-only developer screen proving what NativeBmwDspProcessor and the native PEQ engine are
 * actually running -- see NativeTruthScreen's doc comment for exactly where each displayed value
 * comes from. No controls here alter DSP configuration.
 */
class NativeTruthActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityNativeTruthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.nativeTruthContent.setContent {
            BmwDspTheme { NativeTruthScreen() }
        }
    }
}
