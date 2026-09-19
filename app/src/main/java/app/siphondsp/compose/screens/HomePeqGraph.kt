package app.siphondsp.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.dsp.BmwPeqBank
import app.siphondsp.model.BmwPeqState
import app.siphondsp.service.RootlessAudioProcessorService

/**
 * Read-only PEQ curve for the centre display on the front page: the current EQ response, with no
 * live spectrum, no meters, no tilt handles and no per-filter overlays, so it costs almost nothing
 * to keep on screen. Follows DSP edits through [rememberBmwDspState] and re-reads the band list
 * whenever the front page resumes (returning from the PEQ screen).
 */
@Composable
fun HomePeqGraph(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dsp = rememberBmwDspState()
    var peqState by remember { mutableStateOf(BmwPeqState.load(context)) }
    LifecycleResumeEffect(Unit) {
        peqState = BmwPeqState.load(context)
        onPauseOrDispose { }
    }

    BmwDspTheme {
        PeqGraph(
            systemValues = dsp.values,
            peqState = peqState,
            activeBank = BmwPeqBank.FULL,
            selectedBandId = null,
            modifier = modifier.fillMaxSize(),
            showIndividualFilters = false,
            showSpectrum = false,
            showTiltHandles = false,
            showGainMeters = false,
            sampleRate = (RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: 48_000f).toDouble(),
        )
    }
}
