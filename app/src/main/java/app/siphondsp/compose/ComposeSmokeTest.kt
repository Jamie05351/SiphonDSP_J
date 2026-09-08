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

/** DEBUG (isolating a Phase 3 render bug): known-good composable from Phase 1, re-added
 *  temporarily to confirm ComposeView hosting still works in OutputAllPassFragment while
 *  BmwSliderDemo/BmwSlider render as nothing. Remove once the bisection is done. */
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
            text = "Compose smoke test — if you can see this and drag the slider, hosting works",
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
