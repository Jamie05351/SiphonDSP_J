package app.siphondsp.compose.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** TEMPORARY (Phase 2 verification): renders every BmwTheme color as a labeled swatch, so a
 *  broken/missing color mapping is visible immediately rather than surfacing subtly later as
 *  "this one slider looks wrong" on a real screen. Wire this into any fragment via ComposeView
 *  the same way ComposeSmokeTest was in Phase 1, confirm on-device, then delete this file --
 *  same lifecycle as that Phase 1 file, not meant to ship. */
@Composable
fun ThemeSwatchCheck() {
    BmwDspTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            Text("Material role colors", color = MaterialTheme.colorScheme.onBackground)
            swatchRow("primary", MaterialTheme.colorScheme.primary)
            swatchRow("secondary", MaterialTheme.colorScheme.secondary)
            swatchRow("error", MaterialTheme.colorScheme.error)
            swatchRow("surface", MaterialTheme.colorScheme.surface)

            Text("BmwTheme.colors (band/section accents)", color = MaterialTheme.colorScheme.onBackground)
            swatchRow("lightBlue", BmwTheme.colors.lightBlue)
            swatchRow("lightBlueBright", BmwTheme.colors.lightBlueBright)
            swatchRow("mBlue", BmwTheme.colors.mBlue)
            swatchRow("mRed", BmwTheme.colors.mRed)
            swatchRow("mGreen", BmwTheme.colors.mGreen)
            swatchRow("midBandYellow", BmwTheme.colors.midBandYellow)
            swatchRow("sliderLowBand", BmwTheme.colors.sliderLowBand)
            swatchRow("sliderMidBand", BmwTheme.colors.sliderMidBand)
            swatchRow("sliderHeadroom", BmwTheme.colors.sliderHeadroom)
            swatchRow("sliderTilt", BmwTheme.colors.sliderTilt)
            swatchRow("sliderDefault", BmwTheme.colors.sliderDefault)
            swatchRow("toggleOnGreen", BmwTheme.colors.toggleOnGreen)
        }
    }
}

@Composable
private fun swatchRow(label: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(color),
        )
        Text(
            text = "  $label",
            color = Color.White,
        )
    }
}

@Preview
@Composable
private fun ThemeSwatchCheckPreview() {
    ThemeSwatchCheck()
}
