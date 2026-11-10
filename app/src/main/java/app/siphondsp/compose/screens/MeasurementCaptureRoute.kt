package app.siphondsp.compose.screens

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.siphondsp.R
import app.siphondsp.audio.MeasurementSpectrumAnalyzer
import app.siphondsp.compose.nav.LocalAppNavigator
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
 * as WAV files. Lives as long as the route, so a recreation mid-capture or mid-export carries on.
 */
class MeasurementCaptureViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()

    var ui by mutableStateOf(MeasurementUi(status = "", captureLabel = ""))
        private set
    private var capturing = false
    private var progressJob: Job? = null

    // Set when the WAV export fails (e.g. a transient cache write error): the engine keeps the
    // finished capture across that failure so it can be exported again without recording
    // (see DspEngine.exportNativeBmwCaptureWav). Cleared by a successful retry or a new capture.
    private var pendingExportRetry: Boolean
        get() = saved[KEY_PENDING_RETRY] ?: false
        set(value) { saved[KEY_PENDING_RETRY] = value }

    // Kept across process death too, so a finished capture is still found under the same names.
    private val captureId: String = saved[KEY_CAPTURE_ID] ?: UUID.randomUUID().toString().also { saved[KEY_CAPTURE_ID] = it }
    private val rawInFile = File(app.cacheDir, "capture_${captureId}_raw_in.wav")
    private val outFile = File(app.cacheDir, "capture_${captureId}_output.wav")

    init {
        // Every visit has its own file pair; sweep the ones earlier visits left behind.
        app.cacheDir.listFiles { file ->
            file.name.startsWith("capture_") && file.name.endsWith(".wav") && file != rawInFile && file != outFile
        }?.forEach { it.delete() }
        ui = MeasurementUi(
            status = app.getString(R.string.measurement_status_idle),
            captureLabel = app.getString(if (pendingExportRetry) R.string.measurement_retry_export else R.string.measurement_start_capture),
        )
    }

    fun onCapture() {
        when {
            capturing -> stopCapture()
            pendingExportRetry -> exportCaptureAndAnalyze()
            else -> startCapture()
        }
    }

    /** The screen went to the background: a capture still running stops there. */
    fun onStop() {
        if (capturing) stopCapture()
    }

    private fun currentSampleRate(): Float =
        RootlessAudioProcessorService.nativeBmwPeqSampleRate()?.takeIf { it > 0f } ?: 48000f

    private fun capturedSeconds(): Float =
        (RootlessAudioProcessorService.nativeBmwCaptureFrameCount() ?: 0L) / currentSampleRate()

    private fun startCapture() {
        if (!ui.captureEnabled) return
        if (!RootlessAudioProcessorService.startNativeBmwCapture()) {
            app.toast(R.string.measurement_engine_not_running, false)
            return
        }
        // Recording replaces whatever a pending retry would have exported.
        pendingExportRetry = false
        capturing = true
        ui = ui.copy(readout = null, spectrum = null, exportEnabled = false, captureLabel = app.getString(R.string.measurement_stop_capture))
        progressJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = capturedSeconds()
                if (elapsed >= CAPTURE_MAX_SECONDS) {
                    stopCapture()
                    break
                }
                ui = ui.copy(status = app.getString(R.string.measurement_status_capturing, elapsed, CAPTURE_MAX_SECONDS))
                delay(200L)
            }
        }
    }

    private fun stopCapture() {
        progressJob?.cancel()
        progressJob = null
        RootlessAudioProcessorService.stopNativeBmwCapture()
        capturing = false
        ui = ui.copy(status = app.getString(R.string.measurement_status_captured, capturedSeconds()))
        exportCaptureAndAnalyze()
    }

    private fun exportCaptureAndAnalyze() {
        ui = ui.copy(captureEnabled = false, exportEnabled = false)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RootlessAudioProcessorService.exportNativeBmwCaptureWav(rawInFile.absolutePath, outFile.absolutePath)
                }
                if (result == null || result.size < 3) {
                    // The capture survives this failure: offer a retry instead of a new recording.
                    exportFailed()
                    return@launch
                }
                pendingExportRetry = false
                ui = ui.copy(
                    captureLabel = app.getString(R.string.measurement_start_capture),
                    readout = app.getString(R.string.measurement_readout, result[0], result[1], result[2]),
                )
                val spectrum = withContext(Dispatchers.Default) {
                    try {
                        MeasurementSpectrumAnalyzer.analyze(rawInFile, outFile)
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
        ui = ui.copy(captureLabel = app.getString(R.string.measurement_retry_export))
        app.toast(R.string.measurement_export_failed, false)
    }

    fun onExportLocationSelected(treeUri: Uri?) {
        treeUri ?: return
        ui = ui.copy(captureEnabled = false, exportEnabled = false)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    app.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val dir = UniFile.fromUri(app, treeUri)?.createDirectory("measurement_$timestamp")
                    val copied = dir != null && copyFileInto(dir, rawInFile, "input.wav") && copyFileInto(dir, outFile, "output.wav")
                    // Safe in the chosen folder now; don't leave the cache copies behind.
                    if (copied) {
                        rawInFile.delete()
                        outFile.delete()
                    }
                    copied
                }
                app.toast(if (ok) R.string.measurement_export_succeeded else R.string.measurement_export_failed, false)
                // Only offer Export again on failure: on success the files are gone from the cache.
                ui = ui.copy(exportEnabled = !ok)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Timber.e(ex, "Capture file copy failed")
                app.toast(R.string.measurement_export_failed, false)
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

@Composable
fun MeasurementCaptureRoute(vm: MeasurementCaptureViewModel = viewModel()) {
    val navigator = LocalAppNavigator.current
    val activity = LocalContext.current as? Activity
    val exportLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), vm::onExportLocationSelected)
    // Leaving the app stops a capture; a recreation (no stop of the ViewModel) doesn't.
    LifecycleStartEffect(vm) {
        onStopOrDispose { if (activity?.isChangingConfigurations != true) vm.onStop() }
    }
    MeasurementCaptureScreen(
        ui = vm.ui,
        onCapture = vm::onCapture,
        onExport = { exportLocationPicker.launch(null) },
        onBack = navigator::back,
    )
}
