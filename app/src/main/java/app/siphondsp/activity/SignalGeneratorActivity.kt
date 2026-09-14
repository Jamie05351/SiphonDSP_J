package app.siphondsp.activity

import android.os.Bundle
import app.siphondsp.compose.screens.SignalGeneratorScreen
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.databinding.ActivitySignalGeneratorBinding

class SignalGeneratorActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySignalGeneratorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.signalGeneratorContent.setContent {
            BmwDspTheme { SignalGeneratorScreen() }
        }
    }
}
