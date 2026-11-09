package app.siphondsp.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.siphondsp.R
import app.siphondsp.audio.MeasurementSpectrumAnalyzer
import app.siphondsp.compose.screens.MeasurementCaptureScreen
import app.siphondsp.compose.screens.MeasurementUi
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.extensions.ContextExtensions.toast
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Records what enters the DSP chain and what leaves it, shows the two spectra, and exports both
 * as WAV files ([MeasurementCaptureScreen]).
 */
class MeasurementCaptureActivity : BaseActivity() {

    private var ui by mutableStateOf(MeasurementUi(status = "", captureLabel = ""))
    private var capturing = false
    private var progressJob: Job? = null

    // Set when the WAV export fails (e.g. a transient cache write error): the engine keeps the
    // finished capture across that failure so it can be exported again without recording
    // (see DspEngine.exportNativeBmwCaptureWav). Cleared by a successful retry or a new capture.
    private var pendingExportRetry = false

    // Kept across recreation, so a capture that finished before a rotation is still found
    // under the same file names afterwards.
    private var captureId: String = UUID.randomUUID().toString()
    private lateinit var rawInFile: File
    private lateinit var outFile: File

    private val exportLocationPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        onExportLocationSelected(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureId = savedInstanceState?.getString(KEY_CAPTURE_ID) ?: captureId
        pendingExportRetry = savedInstanceState?.getBoolean(KEY_PENDING_RETRY) ?: false
        rawInFile = File(cacheDir, "capture_${captureId}_raw_in.wav")
        outFile = File(cacheDir, "capture_${captureId}_output.wav")
        // Every visit has its own file pair; sweep the ones earlier visits left behind.
        deleteStaleCaptureFiles(keep = setOf(rawInFile, outFile))

        ui = MeasurementUi(
            status = getString(R.string.measurement_status_idle),
            captureLabel = getString(if (pendingExportRetry) R.string.measurement_retry_export else R.string.measurement_start_capture),
        )

        setContent {
            BmwDspTheme {
                MeasurementCaptureScreen(
                    ui = ui,
                    onCapture = {
                        when {
                            capturing -> stopCapture()
                            pendingExportRetry -> exportCaptureAndAnalyze()
                            else -> startCapture()
                        }
                    },
                    onExport = { exportLocationPicker.launch(null) },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CAPTURE_ID, captureId)
        outState.putBoolean(KEY_PENDING_RETRY, pendingExportRetry)
    }

    override fun onStop() {
        if (capturing) {
            if (isChangingConfigurations) {
                // Recreated, not left: an export started now would be cancelled with this
                // instance. Stop recording and let the new one offer the export (the engine keeps
                // the finished capture until it is exported).
                progressJob?.cancel()
                RootlessAudioProcessorService.stopNativeBmwCapture()
                capturing = false
                pendingExportRetry = true
            } else {
                stopCapture()
            }
        }
        super.onStop()
    }

    private fun deleteStaleCaptureFiles(keep: Set<File>) {
        cacheDir.listFiles { file ->
            file.name.startsWith("capture_") && file.name.endsWith(".wav") && file !in keep
        }?.forEach { it.delete() }
    }

    private fun currentSampleRate(): Float =
        RootlessAudioProcessorService.nativeBmwPeqSampleRate()?.takeIf { it > 0f } ?: 48000f

    private fun capturedSeconds(): Float =
        (RootlessAudioProcessorService.nativeBmwCaptureFrameCount() ?: 0L) / currentSampleRate()

    private fun startCapture() {
        if (!ui.captureEnabled) return
        if (!RootlessAudioProcessorService.startNativeBmwCapture()) {
            toast(R.string.measurement_engine_not_running)
            return
        }
        // Recording replaces whatever a pending retry would have exported.
        pendingExportRetry = false
        capturing = true
        ui = ui.copy(readout = null, spectrum = null, exportEnabled = false, captureLabel = getString(R.string.measurement_stop_capture))
        progressJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = capturedSeconds()
                if (elapsed >= CAPTURE_MAX_SECONDS) {
                    stopCapture()
                    break
                }
                ui = ui.copy(status = getString(R.string.measurement_status_capturing, elapsed, CAPTURE_MAX_SECONDS))
                delay(200L)
            }
        }
    }

    private fun stopCapture() {
        progressJob?.cancel()
        progressJob = null
        RootlessAudioProcessorService.stopNativeBmwCapture()
        capturing = false
        ui = ui.copy(status = getString(R.string.measurement_status_captured, capturedSeconds()))
        exportCaptureAndAnalyze()
    }

    private fun exportCaptureAndAnalyze() {
        val input = rawInFile
        val output = outFile
        ui = ui.copy(captureEnabled = false, exportEnabled = false)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RootlessAudioProcessorService.exportNativeBmwCaptureWav(input.absolutePath, output.absolutePath)
                }
                if (result == null || result.size < 3) {
                    // The capture survives this failure: offer a retry instead of a new recording.
                    exportFailed()
                    return@launch
                }
                pendingExportRetry = false
                ui = ui.copy(
                    captureLabel = getString(R.string.measurement_start_capture),
                    readout = getString(R.string.measurement_readout, result[0], result[1], result[2]),
                )
                val spectrum = withContext(Dispatchers.Default) {
                    try {
                        MeasurementSpectrumAnalyzer.analyze(input, output)
                    } catch (ex: Exception) {
                        if (ex is CancellationException) throw ex
                        Timber.e(ex, "Spectrum analysis failed")
                        null
                    }
                }
                ui = ui.copy(spectrum = spectrum, exportEnabled = true)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Timber.e(ex, "Capture export failed")
                exportFailed()
            } finally {
                ui = ui.copy(captureEnabled = true)
            }
        }
    }

    private fun exportFailed() {
        pendingExportRetry = true
        ui = ui.copy(captureLabel = getString(R.string.measurement_retry_export))
        toast(R.string.measurement_export_failed)
    }

    private fun onExportLocationSelected(treeUri: Uri?) {
        treeUri ?: return
        val input = rawInFile
        val output = outFile
        ui = ui.copy(captureEnabled = false, exportEnabled = false)
        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val dir = UniFile.fromUri(this@MeasurementCaptureActivity, treeUri)?.createDirectory("measurement_$timestamp")
                    val copied = dir != null && copyFileInto(dir, input, "input.wav") && copyFileInto(dir, output, "output.wav")
                    // Safe in the chosen folder now; don't leave the cache copies behind.
                    if (copied) {
                        input.delete()
                        output.delete()
                    }
                    copied
                }
                toast(if (ok) R.string.measurement_export_succeeded else R.string.measurement_export_failed)
                // Only offer Export again on failure: on success the files are gone from the cache.
                ui = ui.copy(exportEnabled = !ok)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Timber.e(ex, "Capture file copy failed")
                toast(R.string.measurement_export_failed)
                ui = ui.copy(exportEnabled = true)
            } finally {
                ui = ui.copy(captureEnabled = true)
            }
        }
    }

    private fun copyFileInto(dir: UniFile, source: File, name: String): Boolean {
        if (!source.exists()) return false
        val dest = dir.createFile(name) ?: return false
        return try {
            source.inputStream().use { input -> dest.openOutputStream().use { input.copyTo(it) } }
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        // NativeBmwDspProcessor::kCaptureMaxSeconds, the fixed capture buffer length.
        const val CAPTURE_MAX_SECONDS = 30f
        const val KEY_CAPTURE_ID = "capture_id"
        const val KEY_PENDING_RETRY = "pending_export_retry"
    }
}
