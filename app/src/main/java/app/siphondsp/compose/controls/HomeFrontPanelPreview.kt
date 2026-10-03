package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.siphondsp.view.HomeArt
import app.siphondsp.view.HomeStageStatus
import app.siphondsp.view.LevelHistory
import app.siphondsp.view.LevelReadout
import kotlin.math.sin

/**
 * Previews of the front page, so its layout can be checked in Android Studio before it goes on a
 * device. The Compose parts are placed at their [HomeArt] rects the way
 * [app.siphondsp.view.HomeArtLayout] places their host views; the power button and the GLOBAL
 * STAGES strip are Views and are left out. [HomeFaceplate] picks the head-unit or phone rects from
 * the preview's width, so each preview's size selects its layout.
 */
@Composable
private fun FrontPanel() {
    val history = LevelHistory().apply {
        for (i in 0 until capacity) {
            val swing = 9f * sin(i * 0.35f)
            push(-20f + swing, -11f + swing, -23f - swing, -13f - swing)
        }
    }
    val readout = LevelReadout(-19f, -7.4f, -22f, -9.8f)
    val stages = HomeStageStatus(
        mbcBands = listOf(true, true, false, true),
        // MID L and MID R on (one section each), the rest off.
        allPass = HomeStageStatus.OUTPUTS.mapIndexed { i, (_, label) ->
            HomeStageStatus.AllPassOutput(label, if (i == 2 || i == 3) listOf(120f) else emptyList())
        },
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val phone = maxWidth < 1100.dp
        val w = with(density) { maxWidth.roundToPx() }
        val h = with(density) { maxHeight.roundToPx() }

        @Composable
        fun At(key: String, content: @Composable () -> Unit) {
            val frac = HomeArt.frac(key, phone)!!
            val px = if (phone) {
                HomeArt.map(frac, w, h, HomeArt.PHONE_IMAGE_WIDTH, HomeArt.PHONE_IMAGE_HEIGHT)
            } else {
                HomeArt.map(frac, w, h)
            }
            with(density) {
                Box(
                    Modifier
                        .offset(px.left.toDp(), px.top.toDp())
                        .size((px.right - px.left).toDp(), (px.bottom - px.top).toDp()),
                ) { content() }
            }
        }

        HomeFaceplate()
        HomeTileKind.entries.zip(HomeArt.TILE_KEYS).forEach { (kind, key) -> At(key) { HomeTile(kind) } }
        At(HomeArt.SCOPE_KEY) { HomeOutputScope(history, { 0 }, readout) }
        At("live_centre") { HomeCentreScreen(readout, limiterDb = null, stages = stages) }
    }
}

@Preview(name = "Head unit", widthDp = 1280, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun FrontPanelHeadUnitPreview() = FrontPanel()

@Preview(name = "Phone", widthDp = 891, heightDp = 411, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun FrontPanelPhonePreview() = FrontPanel()

/** A card with a title too long for it, to check that it shrinks and then ends in an ellipsis. */
@Preview(name = "Long titles", widthDp = 420, heightDp = 180, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun ChainCardLongTitlePreview() {
    Row(Modifier.padding(12.dp)) {
        HomeChainCard("Gains / Delay", "Time alignment", DspColors.Delay, DspColors.Peq, selected = false, Modifier.size(120.dp, 147.dp)) {}
        HomeChainCard(
            "Verstärkung / Verzögerung", "Laufzeitkorrektur", DspColors.Delay, DspColors.Peq, selected = true,
            Modifier.padding(start = 24.dp).size(120.dp, 147.dp),
        ) {}
    }
}
