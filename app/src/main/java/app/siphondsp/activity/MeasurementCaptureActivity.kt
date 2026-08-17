package app.siphondsp.activity

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import app.siphondsp.R
import app.siphondsp.databinding.ActivityMeasurementCaptureBinding
import app.siphondsp.fragment.MeasurementCaptureFragment

class MeasurementCaptureActivity : BaseActivity() {

    lateinit var exportLocationLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMeasurementCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        exportLocationLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            (supportFragmentManager.findFragmentById(R.id.measurement_host) as? MeasurementCaptureFragment)
                ?.onExportLocationSelected(uri)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.measurement_host, MeasurementCaptureFragment())
                .commitNow()
        }
    }
}
