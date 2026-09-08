package app.siphondsp.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin

/** Temporary Phase-1 interop proof-of-concept: confirms the Compose compiler plugin, BOM,
 *  and ComposeView bridge all wire up correctly in this project before any real DSP screen
 *  gets ported. Not part of any real UI flow -- remove this file (and its call site in
 *  OutputAllPassFragment) once the render/interaction is confirmed on-device. */
@Composable
fun ComposeSmokeTest() {
    var sliderValue by remember { mutableFloatStateOf(0.5f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(16.dp),
    ) {
        Text(
            text = "Compose smoke test — if you can see this and drag the slider, the pipeline works",
            color = Color(BmwDashboardSkin.LIGHT_BLUE),
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Value: ${"%.2f".format(sliderValue)}",
            color = Color.White,
        )
    }
}

@Preview
@Composable
private fun ComposeSmokeTestPreview() {
    MaterialTheme {
        ComposeSmokeTest()
    }
}
