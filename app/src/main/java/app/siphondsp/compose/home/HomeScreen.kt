package app.siphondsp.compose.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * The front page, as a faceplate (the page background) holding one top screen and the signal
 * chain (Figma "v3 · Head unit 1280×480"):
 * - 15 dp of faceplate above and beside the top screen, a 30 dp strip under it.
 * - The top screen: the global stages as live-data cells (TILT, MBC, LIMITER, ALLPASS), then the
 *   headroom and the L / R output meters. More and Settings are raised buttons on the faceplate
 *   to its right.
 * - The signal chain: the power node, the five DSP tiles with the chain's line showing in the gaps
 *   between them, and OUT.
 *
 * Everything that looks on or off is driven by one animated global fraction from [powered] (see
 * [animateActive]) times, for the tile badges and live-data cells, each one's own fraction. The
 * tiles are menu buttons and stay in colour whatever the state.
 *
 * Powering on also sends the signal down the chain ([ChainSweep]): the line lights from the power
 * node to the first tile, that tile's border lights round both sides, the next stretch of line,
 * and so on to OUT. While on, the chain panel glows faintly purple.
 *
 * Laid out in dp at 1280 x 480 and scaled down as a whole on a smaller screen (see [HomeScale]);
 * spare height on a taller screen goes to the top screen and the chain in proportion.
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
        val k = minOf(1f, maxWidth.value / DesignWidth, maxHeight.value / DesignHeight)
        // Height the design doesn't use at this scale, shared between the top screen and the chain.
        val spare = (maxHeight.value - DesignHeight * k).coerceAtLeast(0f)
        CompositionLocalProvider(LocalHomeScale provides HomeScale(k)) {
            val s = LocalHomeScale.current
            val g = animateActive(powered, "dsp power")
            // The signal travelling through the chain as the DSP powers on (see ChainSweep); it
            // runs back out, faster, as it powers off. Read only while drawing.
            val sweep = animateFloatAsState(
                if (powered) 1f else 0f,
                tween(if (powered) SweepOnMs else SweepOffMs, easing = LinearEasing),
                label = "chain sweep",
            )
            val bounds = remember { mutableMapOf<Any, Rect>() }
            fun Modifier.tracked(key: Any) = onGloballyPositioned { bounds[key] = it.boundsInRoot() }

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .padding(start = s.dp(Facia), end = s.dp(Facia), top = s.dp(Facia))
                        .fillMaxWidth()
                        .height(s.dp(TopHeight) + (spare * TopShare).dp),
                    horizontalArrangement = Arrangement.spacedBy(s.dp(Facia)),
                ) {
                    TopScreen(engine, readout, g, onOpenGlobal, Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.width(s.dp(ButtonWidth)).fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        FaciaButton(
                            stringResource(R.string.home_tile_more), { onMore(bounds[MoreKey] ?: Rect.Zero) },
                            Modifier.tracked(MoreKey),
                        ) { drawMoreGlyph() }
                        FaciaButton(stringResource(R.string.title_activity_settings), onSettings) { drawSettingsGlyph() }
                    }
                }
                ChainPanel(
                    engine, powered, g, { sweep.value }, openingStage, onTogglePower,
                    onOpenStage = { stage -> onOpenStage(stage, bounds[stage] ?: Rect.Zero) },
                    tileModifier = { stage -> Modifier.tracked(stage) },
                    modifier = Modifier
                        .padding(start = s.dp(Facia), end = s.dp(Facia), top = s.dp(FaciaStrip), bottom = s.dp(BottomFacia))
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/** The top screen: the live-data cells, a divider, then the headroom and the meters. */
@Composable
private fun TopScreen(
    engine: HomeEngineState,
    readout: LevelReadout,
    g: Float,
    onOpenGlobal: (GlobalStage) -> Unit,
    modifier: Modifier,
) {
    val s = LocalHomeScale.current
    val off = stringResource(R.string.home_chip_off)
    Screen(s.dp(14f), modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = s.dp(14f)), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(s.dp(CellGap))) {
                Row(horizontalArrangement = Arrangement.spacedBy(s.dp(CellGap))) {
                    Cell(
                        GlobalStage.TILT, stringResource(R.string.home_chip_tilt),
                        engine.tiltDb?.let { "${formatDb(it, signed = true)} dB" } ?: off,
                        BmwTheme.colors.sliderTilt, engine.tiltDb != null, g, onOpenGlobal,
                    )
                    Cell(
                        GlobalStage.MBC, stringResource(R.string.home_chip_mbc),
                        if (engine.mbcBands > 0) stringResource(R.string.home_chip_bands, engine.mbcBands) else off,
                        DspColors.Comp, engine.mbcBands > 0, g, onOpenGlobal,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(s.dp(CellGap))) {
                    Cell(
                        GlobalStage.LIMITER, stringResource(R.string.home_chip_limiter),
                        engine.limiterDb?.let { "${formatDb(it)} dB" } ?: off,
                        HomePalette.LimiterChip, engine.limiterDb != null, g, onOpenGlobal,
                    )
                    Cell(
                        GlobalStage.ALLPASS, stringResource(R.string.home_chip_allpass),
                        if (engine.allPassOn) {
                            pluralStringResource(R.plurals.home_chip_allpass_outputs, engine.allPassOutputs, engine.allPassOutputs)
                        } else {
                            off
                        },
                        DspColors.Allpass, engine.allPassOn, g, onOpenGlobal,
                    )
                }
            }
            Box(
                Modifier
                    .padding(horizontal = s.dp(16f))
                    .width(s.dp(2f))
                    .fillMaxHeight()
                    .padding(vertical = s.dp(16f))
                    .background(HomePalette.PanelEdge),
            )
            OutputMeter(readout, engine.ceilingDb, g, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Cell(
    stage: GlobalStage,
    label: String,
    value: String,
    accent: Color,
    on: Boolean,
    g: Float,
    onOpenGlobal: (GlobalStage) -> Unit,
) {
    val s = LocalHomeScale.current
    GlobalChip(label, value, accent, on, g, { onOpenGlobal(stage) }, Modifier.width(s.dp(CellWidth)).heightIn(min = s.dp(CellHeight)))
}

/** The signal chain: power, the five tiles spaced so the line shows between them, and OUT. */
@Composable
private fun ChainPanel(
    engine: HomeEngineState,
    powered: Boolean,
    g: Float,
    sweep: () -> Float,
    openingStage: HomeStage?,
    onTogglePower: () -> Unit,
    onOpenStage: (HomeStage) -> Unit,
    tileModifier: (HomeStage) -> Modifier,
    modifier: Modifier,
) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    // While the DSP is on the panel glows faintly purple: a soft halo onto the faceplate round it
    // and a purple tint to its edge.
    val glowing = modifier.drawBehind {
        val radius = s.dp(16f).toPx()
        for (step in 1..4) {
            val grow = s.dp(4f).toPx() * step
            drawRoundRect(
                signal.copy(alpha = 0.05f * g),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = CornerRadius(radius + grow),
            )
        }
    }
    Screen(s.dp(16f), glowing, edge = lerp(HomePalette.PanelEdge, signal.copy(alpha = 0.55f), g)) {
        SignalPath(g, sweep)
        Row(Modifier.fillMaxSize()) {
            PowerNode(powered, g, onTogglePower)
            Row(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = s.dp(TileInset)),
                horizontalArrangement = Arrangement.spacedBy(s.dp(TileGap), Alignment.CenterHorizontally),
            ) {
                for ((index, stage) in HomeStage.entries.withIndex()) {
                    val tile = stage.tile()
                    StageCard(
                        title = stringResource(tile.title),
                        subtitle = stringResource(tile.subtitle),
                        accent = tile.accent,
                        art = tile.art,
                        stageOn = engine.stageOn(stage),
                        globalActive = g,
                        borderLit = { ChainSweep.tile(index, sweep()) },
                        selected = openingStage == stage,
                        onClick = { onOpenStage(stage) },
                        modifier = tileModifier(stage),
                    )
                }
            }
            OutputNode(g)
        }
    }
}

/** A dark rounded screen set into the faceplate, with its hairline edge. */
@Composable
private fun Screen(
    radius: Dp,
    modifier: Modifier,
    edge: Color = HomePalette.PanelEdge,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(HomePalette.Panel)
            .border(LocalHomeScale.current.dp(2f), edge, shape),
    ) { content() }
}

/** More / Settings: a raised button on the faceplate, with a glyph over its label. */
@Composable
private fun FaciaButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(16f))
    Column(
        modifier
            .size(s.dp(ButtonWidth), s.dp(ButtonHeight))
            .drawBehind {
                // A drop shadow under the button, so it stands off the plate.
                val drop = s.dp(3f).toPx()
                for (step in 1..3) {
                    val grow = s.dp(2f).toPx() * step
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.22f),
                        topLeft = Offset(-grow / 2, drop - grow / 2),
                        size = Size(size.width + grow, size.height + grow),
                        cornerRadius = CornerRadius(s.dp(16f).toPx() + grow),
                    )
                }
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(HomePalette.ButtonTop, HomePalette.ButtonBottom)))
            .border(s.dp(1.5f), HomePalette.ButtonEdge, shape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(s.dp(36f), s.dp(24f)), onDraw = glyph)
        Text(
            text = label,
            color = HomePalette.CellLabel,
            fontSize = s.sp(14f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(top = s.dp(6f)),
        )
    }
}

/** Three dots across the middle. */
private fun DrawScope.drawMoreGlyph() {
    val r = size.height * 0.17f
    for (fx in listOf(0.22f, 0.5f, 0.78f)) drawCircle(HomePalette.Button, r, Offset(size.width * fx, size.height / 2))
}

/** Three slider rails, each with its knob at a different place. */
private fun DrawScope.drawSettingsGlyph() {
    val stroke = size.height * 0.12f
    val knob = size.height * 0.17f
    for ((fy, fx) in listOf(0.15f to 0.72f, 0.5f to 0.28f, 0.85f to 0.58f)) {
        val y = size.height * fy
        drawLine(HomePalette.Button, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), stroke, StrokeCap.Round)
        drawCircle(HomePalette.ButtonBottom, knob, Offset(size.width * fx, y))
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

// The layout in design dp (Figma "v3"): faceplate margins, the top screen's height at 1280 x 480,
// and the buttons, cells and tile spacing. The chain panel takes the rest of the height.
private const val Facia = 15f
private const val FaciaStrip = 30f
private const val BottomFacia = 12f
private const val TopHeight = 179f
private const val TopShare = 0.45f
private const val ButtonWidth = 88f
private const val ButtonHeight = 80f
private const val CellWidth = 200f
private const val CellHeight = 76f
private const val CellGap = 10f
internal const val TileGap = 27.5f
private const val TileInset = 14f
private const val MoreKey = "more"
private const val SweepOnMs = 1800
private const val SweepOffMs = 900

// --- Previews: sample data only. The app passes real engine state. ---

private val PreviewEngine = HomeEngineState(
    compressorOn = true,
    allPassOutputs = 0,
    tiltDb = 1f,
    mbcBands = 4,
    limiterDb = -1f,
)

@Preview(name = "Home · DSP ON", widthDp = 1280, heightDp = 480)
@Composable
private fun HomeScreenOnPreview() = BmwDspTheme {
    HomeScreen(
        engine = PreviewEngine,
        powered = true,
        readout = LevelReadout(leftRmsDb = -9.6f, leftPeakDb = -7.2f, rightRmsDb = -10.6f, rightPeakDb = -7.8f),
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
        readout = LevelReadout(leftRmsDb = -20f, leftPeakDb = -12.5f, rightRmsDb = -21f, rightPeakDb = -13.2f),
        openingStage = null,
        onTogglePower = {}, onOpenStage = { _, _ -> }, onOpenGlobal = {}, onSettings = {}, onMore = {},
    )
}

@Preview(name = "Home · phone", widthDp = 891, heightDp = 411)
@Composable
private fun HomeScreenPhonePreview() = BmwDspTheme {
    HomeScreen(
        engine = PreviewEngine,
        powered = true,
        readout = LevelReadout(leftRmsDb = -9.6f, leftPeakDb = -7.2f, rightRmsDb = -10.6f, rightPeakDb = -7.8f),
        openingStage = null,
        onTogglePower = {}, onOpenStage = { _, _ -> }, onOpenGlobal = {}, onSettings = {}, onMore = {},
    )
}
