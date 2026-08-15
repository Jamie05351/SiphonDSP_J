package app.siphondsp.activity

import android.os.Bundle
import app.siphondsp.R
import app.siphondsp.databinding.ActivityBlocklistBinding
import app.siphondsp.fragment.BlocklistFragment

class BlocklistActivity : BaseActivity() {

    private lateinit var binding: ActivityBlocklistBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlocklistBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.blocklist_host, BlocklistFragment.newInstance())
                .commit()
        }

        binding.fab.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.blocklist_host) as? BlocklistFragment)?.showAppSelector()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}