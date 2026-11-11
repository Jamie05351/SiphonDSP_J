package app.siphondsp.compose.screens

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.ConvolverResponse
import app.siphondsp.fragment.PeqGraphPreferences
import app.siphondsp.service.RootlessAudioProcessorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The [pointCount]-point grid a [BmwResponseCalculator] evaluates on for this axis. */
fun responseGrid(pointCount: Int, sampleRate: Double, minFrequency: Double, maxFrequency: Double): DoubleArray =
    DoubleArray(pointCount) { BmwResponseCalculator.axisFrequency(it, pointCount, sampleRate, minFrequency, maxFrequency) }

/** [PeqGraphPreferences.showConvolverInGraphs], kept current while composed. */
@Composable
fun rememberShowConvolverInGraphs(): Boolean {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { PeqGraphPreferences(context) }
    var show by remember { mutableStateOf(prefs.showConvolverInGraphs) }
    DisposableEffect(context) {
        val shared = context.getSharedPreferences(PeqGraphPreferences.PREFS, android.content.Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PeqGraphPreferences.KEY_SHOW_CONVOLVER) show = prefs.showConvolverInGraphs
        }
        show = prefs.showConvolverInGraphs
        shared.registerOnSharedPreferenceChangeListener(listener)
        onDispose { shared.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return show
}

/**
 * The running engine's convolver response on [grid] while [show] is on, else null. Polls the
 * engine's convolver generation (a cheap read) and refetches the response -- a DTFT of the whole
 * IR, on the IO dispatcher -- only when it moves (a load, a sample-rate rebuild or a clear) or the
 * grid changes. Null with no service or no IR running.
 */
@Composable
fun rememberConvolverResponse(show: Boolean, grid: DoubleArray): ConvolverResponse? {
    var response by remember { mutableStateOf<ConvolverResponse?>(null) }
    val gridKey = grid.contentHashCode()
    LaunchedEffect(show, gridKey) {
        if (!show) {
            response = null
            return@LaunchedEffect
        }
        var fetched: Long? = Long.MIN_VALUE
        while (true) {
            val generation = withContext(Dispatchers.IO) { RootlessAudioProcessorService.convolverGeneration() }
            if (generation != fetched) {
                val next = if (generation == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) { RootlessAudioProcessorService.convolverResponse(grid) }
                }
                response = next
                // A null response is final only when there's nothing to fetch (no service, or no
                // IR running); otherwise it failed (handle swap, allocation) and the next poll
                // retries rather than leaving the graph without the IR until the generation moves.
                val settled = next != null || generation == null ||
                    withContext(Dispatchers.IO) { RootlessAudioProcessorService.convolverStatus() }
                        ?.let { it.streamFrames == 0 } ?: true
                if (settled) fetched = next?.generation ?: generation
            }
            delay(CONVOLVER_POLL_MS)
        }
    }
    return response
}

private const val CONVOLVER_POLL_MS = 1000L
