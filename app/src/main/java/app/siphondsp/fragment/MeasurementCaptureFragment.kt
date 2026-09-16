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

    // Set when exportNativeBmwCaptureWav fails (e.g. a transient cache write error) -- the
    // engine keeps the completed native capture alive across that failure specifically so it can
    // be retried without recording again (see JamesDspLocalEngine.exportNativeBmwCaptureWav).
    // Cleared by a successful retry, or by starting a genuinely new capture (which invalidates
    // whatever the retry would have re-exported anyway).
    private var pendingExportRetry = false

    // An old view's uncancellable native write may finish after navigation.
    // Separate files prevent it from overwriting a newer screen's capture.
    //
    // captureId is preserved across recreation (onSaveInstanceState/onCreateView's
    // savedInstanceState below) rather than regenerated every time: the export picker's result
    // is delivered by the activity to whichever fragment instance exists when it returns
    // (MeasurementCaptureActivity.exportLocationLauncher), which is a *new* instance if the
    // activity was recreated (e.g. a config change) while the picker was open. A fresh random id
    // here would point that new instance at files that don't exist, silently failing to export a
    // capture that in fact completed and is sitting on disk under the old id.
    private var captureId: String = UUID.randomUUID().toString()
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
        captureId = savedInstanceState?.getString(KEY_CAPTURE_ID) ?: captureId
        pendingExportRetry = savedInstanceState?.getBoolean(KEY_PENDING_RETRY) ?: pendingExportRetry
        val cacheDir = requireContext().cacheDir
        rawInFile = File(cacheDir, "capture_${captureId}_raw_in.wav")
        outFile = File(cacheDir, "capture_${captureId}_output.wav")
        // Each fragment instance's pair of cache filenames is unique (see the field comments
        // above), so unlike the previous fixed filenames, nothing ever overwrites a prior visit's
        // WAVs -- they'd accumulate indefinitely otherwise. Sweep any left by an earlier instance
        // now; this instance's own two (freshly generated, or restored via captureId above) are
        // never among them.
        deleteStaleCaptureFiles(cacheDir, keep = setOf(rawInFile, outFile))
        binding = FragmentMeasurementCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CAPTURE_ID, captureId)
        outState.putBoolean(KEY_PENDING_RETRY, pendingExportRetry)
    }

    private fun deleteStaleCaptureFiles(cacheDir: File, keep: Set<File>) {
        val staleFiles = cacheDir.listFiles { file ->
            file.name.startsWith("capture_") && file.name.endsWith(".wav") && file !in keep
        } ?: return
        for (file in staleFiles) file.delete()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.measurementStatus.text = getString(R.string.measurement_status_idle)
        if (pendingExportRetry) {
            binding.measurementCaptureButton.text = getString(R.string.measurement_retry_export)
        }
        binding.measurementCaptureButton.setOnClickListener {
            when {
                capturing -> stopCapture()
                pendingExportRetry -> retryExport()
                else -> startCapture()
            }
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
        // A pending retry belongs to whatever was captured before -- recording now replaces it
        // (and the engine frees the retained snapshot it would have retried, see
        // JamesDspLocalEngine.startNativeBmwCapture).
        pendingExportRetry = false
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

        val frames = RootlessAudioProcessorService.nativeBmwCaptureFrameCount() ?: 0L
        binding.measurementStatus.text = getString(R.string.measurement_status_captured, frames / currentSampleRate())

        exportCaptureAndAnalyze()
    }

    private fun retryExport() = exportCaptureAndAnalyze()

    private fun exportCaptureAndAnalyze() {
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
                    // The native capture snapshot survives this failure (see
                    // JamesDspLocalEngine.exportNativeBmwCaptureWav) -- offer a retry instead of
                    // requiring the user to record another 30s take from scratch.
                    pendingExportRetry = true
                    captureBinding.measurementCaptureButton.text = getString(R.string.measurement_retry_export)
                    requireContext().toast(getString(R.string.measurement_export_failed))
                    return@launch
                }
                pendingExportRetry = false
                captureBinding.measurementCaptureButton.text = getString(R.string.measurement_start_capture)
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
                pendingExportRetry = true
                captureBinding.measurementCaptureButton.text = getString(R.string.measurement_retry_export)
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
                    val copied = dir != null && copyFileInto(dir, input, "input.wav") && copyFileInto(dir, output, "output.wav")
                    // Now safely in the user's chosen location -- the cache copies would
                    // otherwise sit there until this fragment instance sweeps them on some
                    // future, unrelated visit (see deleteStaleCaptureFiles in onCreateView).
                    if (copied) {
                        input.delete()
                        output.delete()
                    }
                    copied
                }
                context.toast(getString(if (ok) R.string.measurement_export_succeeded else R.string.measurement_export_failed))
                // Only re-offer Export on failure -- input/output are already deleted on
                // success, so a retry tap would just fail copying files that no longer exist.
                captureBinding.measurementExportButton.isEnabled = !ok
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Timber.e(ex, "Capture file copy failed")
                context.toast(getString(R.string.measurement_export_failed))
                captureBinding.measurementExportButton.isEnabled = true
            } finally {
                captureBinding.measurementCaptureButton.isEnabled = true
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
        private const val KEY_CAPTURE_ID = "capture_id"
        private const val KEY_PENDING_RETRY = "pending_export_retry"
    }
}
