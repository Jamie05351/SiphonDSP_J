package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.BmwPeqState

/**
 * Read-only crossover response for the left display on the home page.
 * This is deliberately static: no live spectrum trace.
 */
@Composable
fun HomeCrossoverGraph(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dsp = rememberBmwDspState()
    var peqState by remember { mutableStateOf(BmwPeqState.load(context)) }

    LifecycleResumeEffect(Unit) {
        peqState = BmwPeqState.load(context)
        onPauseOrDispose { }
    }

    BmwDspTheme {
        CrossoverResponseGraph(
            mode = CrossoverGraphMode.MAGNITUDE,
            systemValues = dsp.values,
            peqState = peqState,
            modifier = modifier.fillMaxSize(),
            showSpectrum = false,
        )
    }
}
