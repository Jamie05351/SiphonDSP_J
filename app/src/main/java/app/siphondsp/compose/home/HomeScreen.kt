package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import app.siphondsp.R
import app.siphondsp.compose.assets.AllpassGraphic
import app.siphondsp.compose.assets.CompressorGraphic
import app.siphondsp.compose.assets.GainsDelayGraphic
import app.siphondsp.compose.assets.PeqGraphic
import app.siphondsp.compose.assets.XoversGraphic
import app.siphondsp.compose.controls.DspColors
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.view.LevelReadout

/**
 * The front page: the global stages, the output meter and More / Settings across the top, and the
 * signal chain below it, from the power node through the five DSP tiles to OUT.
 *
 * Everything that looks on or off is driven by one animated global fraction from [powered] (see
 * [animateActive]) times, for the tile pills and chips, each one's own fraction. The tiles are
 * menu buttons and stay in colour whatever the state.
 *
 * [onOpenStage] and [onMore] get the tapped element's bounds in this composable's root (the
 * hosting view's coordinates), for the screen's zoom-open and the overflow menu's anchor.
 * [openingStage] is the tile whose screen is opening; its glow is lit.
 */
@Composable
fun HomeScreen(
    engine: HomeEngineState,
    powered: Boolean,
    readout: LevelReadout,
    openingStage: HomeStage?,
    onTogglePower: () -> Unit,
    onOpenStage: (HomeStage, Rect) -> Unit,
    onOpenGlobal: (GlobalStage) -> Unit,
    onSettings: () -> Unit,
    onMore: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(HomePalette.Page)) {
        val k = minOf(maxWidth.value / DesignWidth, maxHeight.value / DesignHeight)
        CompositionLocalProvider(LocalHomeScale provides HomeScale(k)) {
            val s = LocalHomeScale.current
            val g = animateActive(powered, "dsp power")
            val bounds = remember { mutableMapOf<Any, Rect>() }
            fun Modifier.tracked(key: Any) = onGloballyPositioned { bounds[key] = it.boundsInRoot() }

            Column(
                Modifier.fillMaxSize().padding(s.dp(30f)),
                verticalArrangement = Arrangement.spacedBy(s.dp(32f)),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(s.dp(210f)),
                    horizontalArrangement = Arrangement.spacedBy(s.dp(20f)),
                ) {
                    Panel(s.dp(18f), Modifier.width(s.dp(560f)).fillMaxHeight()) {
                        StatusPanel(engine, g, onOpenGlobal)
                    }
                    Panel(s.dp(18f), Modifier.weight(1f).fillMaxHeight()) {
                        OutputMeter(readout, engine.ceilingDb, g, Modifier.fillMaxSize())
                    }
                    Column(Modifier.width(s.dp(120f)), verticalArrangement = Arrangement.spacedBy(s.dp(16f))) {
                        HomeButton(
                            stringResource(R.string.home_tile_more), { onMore(bounds[MoreKey] ?: Rect.Zero) },
                            Modifier.weight(1f).tracked(MoreKey),
                        ) { drawMoreGlyph() }
                        HomeButton(stringResource(R.string.title_activity_settings), onSettings, Modifier.weight(1f)) {
                            drawSettingsGlyph()
                        }
                    }
                }
                Panel(s.dp(22f), Modifier.weight(1f).fillMaxWidth()) {
                    SignalPath(g)
                    Row(Modifier.fillMaxSize().padding(start = s.dp(ChainInset), end = s.dp(ChainInset), top = s.dp(ChainTop))) {
                        PowerNode(powered, g, onTogglePower)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (stage in HomeStage.entries) {
                                val tile = stage.tile()
                                StageCard(
                                    title = stringResource(tile.title),
                                    subtitle = stringResource(tile.subtitle),
                                    accent = tile.accent,
                                    art = tile.art,
                                    stageOn = engine.stageOn(stage),
                                    globalActive = g,
                                    selected = openingStage == stage,
                                    onClick = { onOpenStage(stage, bounds[stage] ?: Rect.Zero) },
                                    modifier = Modifier.tracked(stage),
                                )
                            }
                        }
                        OutputNode(g)
                    }
                }
            }
        }
    }
}

/** The status panel: GLOBAL (BYPASSED while the DSP is off) over the global stages' chips. */
@Composable
private fun StatusPanel(engine: HomeEngineState, g: Float, onOpenGlobal: (GlobalStage) -> Unit) {
    val s = LocalHomeScale.current
    val off = stringResource(R.string.home_chip_off)
    Column(Modifier.padding(start = s.dp(26f), end = s.dp(20f), top = s.dp(24f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_global),
                color = HomePalette.Label,
                fontSize = s.sp(13f),
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(letterSpacing = 0.14.em),
                maxLines = 1,
            )
            val tag = RoundedCornerShape(s.dp(6f))
            Text(
                text = stringResource(R.string.home_bypassed),
                color = HomePalette.Button,
                fontSize = s.sp(11f),
                fontWeight = FontWeight.Bold,
                style = TextStyle(letterSpacing = 0.12.em),
                maxLines = 1,
                modifier = Modifier
                    .padding(start = s.dp(12f))
                    .alpha(1 - g)
                    .background(Color.White.copy(alpha = 0.08f), tag)
                    .border(s.dp(1.5f), Color.White.copy(alpha = 0.25f), tag)
                    .padding(horizontal = s.dp(10f), vertical = s.dp(4f)),
            )
        }
        Row(
            Modifier.padding(top = s.dp(28f)),
            horizontalArrangement = Arrangement.spacedBy(s.dp(10f)),
        ) {
            GlobalChip(
                stringResource(R.string.home_chip_tilt),
                engine.tiltDb?.let { "${formatDb(it, signed = true)} dB" } ?: off,
                BmwTheme.colors.sliderTilt, engine.tiltDb != null, g, { onOpenGlobal(GlobalStage.TILT) },
            )
            GlobalChip(
                stringResource(R.string.home_chip_mbc),
                if (engine.mbcBands > 0) stringResource(R.string.home_chip_bands, engine.mbcBands) else off,
                DspColors.Comp, engine.mbcBands > 0, g, { onOpenGlobal(GlobalStage.MBC) },
            )
            GlobalChip(
                stringResource(R.string.home_chip_limiter),
                engine.limiterDb?.let { "${formatDb(it)} dB" } ?: off,
                HomePalette.LimiterChip, engine.limiterDb != null, g, { onOpenGlobal(GlobalStage.LIMITER) },
            )
        }
    }
}

/** A dark rounded panel with the page's hairline edge. */
@Composable
private fun Panel(radius: Dp, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(HomePalette.Panel)
            .border(LocalHomeScale.current.dp(2f), HomePalette.PanelEdge, shape),
        content = content,
    )
}

/** More / Settings: a small panel with a glyph over its label. */
@Composable
private fun HomeButton(label: String, onClick: () -> Unit, modifier: Modifier, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(16f))
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HomePalette.Panel)
            .border(s.dp(2f), HomePalette.PanelEdge, shape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(s.dp(36f), s.dp(28f)), onDraw = glyph)
        Text(
            text = label,
            color = HomePalette.Button,
            fontSize = s.sp(14f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(top = s.dp(10f)),
        )
    }
}

/** Three dots across the middle. */
private fun DrawScope.drawMoreGlyph() {
    val r = size.height * 0.12f
    for (fx in listOf(0.22f, 0.5f, 0.78f)) drawCircle(HomePalette.Button, r, Offset(size.width * fx, size.height / 2))
}

/** Three slider rails, each with its knob at a different place. */
private fun DrawScope.drawSettingsGlyph() {
    val stroke = size.height * 0.08f
    val knob = size.height * 0.13f
    for ((fy, fx) in listOf(0.2f to 0.68f, 0.5f to 0.32f, 0.8f to 0.58f)) {
        val y = size.height * fy
        drawLine(HomePalette.Button, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), stroke, StrokeCap.Round)
        drawCircle(HomePalette.Page, knob, Offset(size.width * fx, y))
        drawCircle(HomePalette.Button, knob, Offset(size.width * fx, y), style = Stroke(stroke))
    }
}

/** A tile's fixed content: its name, what it does, its colour and its artwork. */
private class TileSpec(val title: Int, val subtitle: Int, val accent: Color, val art: ImageVector)

private fun HomeStage.tile(): TileSpec = when (this) {
    HomeStage.PEQ -> TileSpec(R.string.home_card_peq, R.string.home_card_peq_sub, DspColors.Peq, PeqGraphic)
    HomeStage.GAINS -> TileSpec(R.string.home_card_gains, R.string.home_card_gains_sub, DspColors.Delay, GainsDelayGraphic)
    HomeStage.XOVERS -> TileSpec(R.string.home_card_xovers, R.string.home_card_xovers_sub, DspColors.Xover, XoversGraphic)
    HomeStage.COMPRESSOR -> TileSpec(R.string.home_card_compressor, R.string.home_card_compressor_sub, DspColors.Comp, CompressorGraphic)
    HomeStage.ALLPASS -> TileSpec(R.string.home_card_allpass, R.string.home_card_allpass_sub, DspColors.Allpass, AllpassGraphic)
}

private const val DesignWidth = 1920f
private const val DesignHeight = 830f
private const val MoreKey = "more"

// --- Previews: sample data only, matching the Figma frames. The app passes real engine state. ---

private val PreviewEngine = HomeEngineState(
    compressorOn = true,
    allPassOn = false,
    tiltDb = 1.5f,
    mbcBands = 4,
    limiterDb = -1f,
)

@Preview(name = "Home · DSP ON", widthDp = 1280, heightDp = 480)
@Composable
private fun HomeScreenOnPreview() = BmwDspTheme {
    HomeScreen(
        engine = PreviewEngine,
        powered = true,
        readout = LevelReadout(leftRmsDb = -9.4f, leftPeakDb = -7.2f, rightRmsDb = -10.1f, rightPeakDb = -7.8f),
        openingStage = null,
        onTogglePower = {}, onOpenStage = { _, _ -> }, onOpenGlobal = {}, onSettings = {}, onMore = {},
    )
}

@Preview(name = "Home · DSP OFF", widthDp = 1280, heightDp = 480)
@Composable
private fun HomeScreenOffPreview() = BmwDspTheme {
    HomeScreen(
        engine = PreviewEngine,
        powered = false,
        readout = LevelReadout(leftRmsDb = -16f, leftPeakDb = -12.5f, rightRmsDb = -16.8f, rightPeakDb = -13.2f),
        openingStage = null,
        onTogglePower = {}, onOpenStage = { _, _ -> }, onOpenGlobal = {}, onSettings = {}, onMore = {},
    )
}
