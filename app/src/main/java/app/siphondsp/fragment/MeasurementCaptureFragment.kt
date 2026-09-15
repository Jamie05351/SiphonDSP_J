package app.siphondsp.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.siphondsp.R
import app.siphondsp.activity.MeasurementCaptureActivity
import app.siphondsp.audio.MeasurementSpectrumAnalyzer
import app.siphondsp.databinding.FragmentMeasurementCaptureBinding
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.extensions.ContextExtensions.toast
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MeasurementCaptureFragment : Fragment() {

    private lateinit var binding: FragmentMeasurementCaptureBinding
    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false

    // An old view's uncancellable native write may finish after navigation.
    // Separate files prevent it from overwriting a newer screen's capture.
    private lateinit var rawInFile: File
    private lateinit var outFile: File

    // Mirrors NativeBmwDspProcessor::kCaptureMaxSeconds -- the fixed capture buffer duration --
    // used here only to render progress/auto-stop the polling loop, not to size anything.
    private val progressTick = object : Runnable {
        override fun run() {
            val frames = RootlessAudioProcessorService.nativeBmwCaptureFrameCount() ?: 0L
            val sampleRate = currentSampleRate()
            val elapsedSeconds = frames / sampleRate
            if (elapsedSeconds >= CAPTURE_MAX_SECONDS) {
                stopCapture()
                return
            }
            binding.measurementStatus.text = getString(R.string.measurement_status_capturing, elapsedSeconds, CAPTURE_MAX_SECONDS)
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val captureId = UUID.randomUUID().toString()
        rawInFile = File(requireContext().cacheDir, "capture_${captureId}_raw_in.wav")
        outFile = File(requireContext().cacheDir, "capture_${captureId}_output.wav")
        binding = FragmentMeasurementCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.measurementStatus.text = getString(R.string.measurement_status_idle)
        binding.measurementCaptureButton.setOnClickListener {
            if (capturing) stopCapture() else startCapture()
        }
        binding.measurementExportButton.setOnClickListener {
            (requireActivity() as MeasurementCaptureActivity).exportLocationLauncher.launch(null)
        }
    }

    override fun onStop() {
        if (capturing) stopCapture()
        super.onStop()
    }

    private fun currentSampleRate(): Float =
        RootlessAudioProcessorService.nativeBmwPeqSampleRate()?.takeIf { it > 0f } ?: 48000f

    private fun startCapture() {
        if (!binding.measurementCaptureButton.isEnabled) return
        if (!RootlessAudioProcessorService.startNativeBmwCapture()) {
            requireContext().toast(getString(R.string.measurement_engine_not_running))
            return
        }
        capturing = true
        binding.measurementReadout.isVisible = false
        binding.measurementResponseView.isVisible = false
        binding.measurementExportButton.isEnabled = false
        binding.measurementCaptureButton.text = getString(R.string.measurement_stop_capture)
        handler.post(progressTick)
    }

    private fun stopCapture() {
        handler.removeCallbacks(progressTick)
        RootlessAudioProcessorService.stopNativeBmwCapture()
        capturing = false
        binding.measurementCaptureButton.text = getString(R.string.measurement_start_capture)

        val frames = RootlessAudioProcessorService.nativeBmwCaptureFrameCount() ?: 0L
        binding.measurementStatus.text = getString(R.string.measurement_status_captured, frames / currentSampleRate())

        val captureBinding = binding
        val input = rawInFile
        val output = outFile
        captureBinding.measurementCaptureButton.isEnabled = false
        captureBinding.measurementExportButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RootlessAudioProcessorService.exportNativeBmwCaptureWav(input.absolutePath, output.absolutePath)
                }
                if (result == null || result.size < 3) {
                    requireContext().toast(getString(R.string.measurement_export_failed))
                    return@launch
                }
                captureBinding.measurementReadout.text = getString(R.string.measurement_readout, result[0], result[1], result[2])
                captureBinding.measurementReadout.isVisible = true
                val spectrum = withContext(Dispatchers.Default) {
                    try {
                        MeasurementSpectrumAnalyzer.analyze(input, output)
                    } catch (ex: Exception) {
                        if (ex is CancellationException) throw ex
                        Timber.e(ex, "Spectrum analysis failed")
                        null
                    }
                }
                if (spectrum != null) {
                    captureBinding.measurementResponseView.setData(spectrum.rawInDb, spectrum.outDb, spectrum.sampleRate, spectrum.fftSize)
                    captureBinding.measurementResponseView.isVisible = true
                }
                captureBinding.measurementExportButton.isEnabled = true
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Timber.e(ex, "Capture export failed")
                context?.toast(getString(R.string.measurement_export_failed))
            } finally {
                captureBinding.measurementCaptureButton.isEnabled = true
            }
        }
    }

    fun onExportLocationSelected(treeUri: Uri?) {
        treeUri ?: return
        val context = requireContext()
        val captureBinding = binding
        val input = rawInFile
        val output = outFile
        captureBinding.measurementCaptureButton.isEnabled = false
        captureBinding.measurementExportButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val root = UniFile.fromUri(context, treeUri)
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val dir = root?.createDirectory("measurement_$timestamp")
                    dir != null && copyFileInto(dir, input, "input.wav") && copyFileInto(dir, output, "output.wav")
                }
                context.toast(getString(if (ok) R.string.measurement_export_succeeded else R.string.measurement_export_failed))
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Timber.e(ex, "Capture file copy failed")
                context.toast(getString(R.string.measurement_export_failed))
            } finally {
                captureBinding.measurementCaptureButton.isEnabled = true
                captureBinding.measurementExportButton.isEnabled = true
            }
        }
    }

    private fun copyFileInto(dir: UniFile, source: File, name: String): Boolean {
        if (!source.exists()) return false
        val dest = dir.createFile(name) ?: return false
        return try {
            FileInputStream(source).use { input ->
                dest.openOutputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (ex: Exception) {
            false
        }
    }

    companion object {
        private const val CAPTURE_MAX_SECONDS = 30f
    }
}
