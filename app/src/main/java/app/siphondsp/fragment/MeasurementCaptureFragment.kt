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
import app.siphondsp.R
import app.siphondsp.activity.MeasurementCaptureActivity
import app.siphondsp.databinding.FragmentMeasurementCaptureBinding
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.extensions.ContextExtensions.toast
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeasurementCaptureFragment : Fragment() {

    private lateinit var binding: FragmentMeasurementCaptureBinding
    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false

    private val rawInFile by lazy { File(requireContext().cacheDir, "capture_raw_in.wav") }
    private val outFile by lazy { File(requireContext().cacheDir, "capture_output.wav") }

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
        if (!RootlessAudioProcessorService.startNativeBmwCapture()) {
            requireContext().toast(getString(R.string.measurement_engine_not_running))
            return
        }
        capturing = true
        binding.measurementReadout.isVisible = false
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

        val result = RootlessAudioProcessorService.exportNativeBmwCaptureWav(rawInFile.absolutePath, outFile.absolutePath)
        if (result != null && result.size >= 3) {
            binding.measurementReadout.text = getString(R.string.measurement_readout, result[0], result[1], result[2])
            binding.measurementReadout.isVisible = true
            binding.measurementExportButton.isEnabled = true
        } else {
            requireContext().toast(getString(R.string.measurement_export_failed))
        }
    }

    fun onExportLocationSelected(treeUri: Uri?) {
        treeUri ?: return
        val context = requireContext()
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val root = UniFile.fromUri(context, treeUri)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = root?.createDirectory("measurement_$timestamp")
        val ok = dir != null &&
            copyFileInto(dir, rawInFile, "input.wav") &&
            copyFileInto(dir, outFile, "output.wav")
        context.toast(getString(if (ok) R.string.measurement_export_succeeded else R.string.measurement_export_failed))
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
