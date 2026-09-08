package app.siphondsp.compose.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.compose.theme.BmwTheme

/** TEMPORARY (Phase 3 slider verification): four BmwSliders across the app's real band accents
 *  (low/mid/headroom/tilt), each independently draggable, to confirm the custom capsule/groove/
 *  thumb chrome renders and responds to touch correctly on-device before it's built on further.
 *  Same lifecycle as ComposeSmokeTest/ThemeSwatchCheck -- delete once confirmed. */
@Composable
fun BmwSliderDemo() {
    BmwDspTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(16.dp),
        ) {
            var lowValue by remember { mutableFloatStateOf(0.3f) }
            Text("Low band  (${"%.2f".format(lowValue)})", color = Color.White)
            BmwSlider(
                value = lowValue,
                onValueChange = { lowValue = it },
                valueRange = 0f..1f,
                accentColor = BmwTheme.colors.sliderLowBand,
            )

            var midValue by remember { mutableFloatStateOf(0.5f) }
            Text("Mid band  (${"%.2f".format(midValue)})", color = Color.White)
            BmwSlider(
                value = midValue,
                onValueChange = { midValue = it },
                valueRange = 0f..1f,
                accentColor = BmwTheme.colors.sliderMidBand,
            )

            var headroomValue by remember { mutableFloatStateOf(0.7f) }
            Text("Headroom  (${"%.2f".format(headroomValue)})", color = Color.White)
            BmwSlider(
                value = headroomValue,
                onValueChange = { headroomValue = it },
                valueRange = 0f..1f,
                accentColor = BmwTheme.colors.sliderHeadroom,
            )

            var tiltValue by remember { mutableFloatStateOf(0.2f) }
            Text("Tilt  (${"%.2f".format(tiltValue)})", color = Color.White)
            BmwSlider(
                value = tiltValue,
                onValueChange = { tiltValue = it },
                valueRange = 0f..1f,
                accentColor = BmwTheme.colors.sliderTilt,
            )
        }
    }
}
