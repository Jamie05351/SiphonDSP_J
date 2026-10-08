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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The front page, as a faceplate (the page background) holding two screens set into it (Figma
 * "Front plate · Home · PROPOSED"):
 * - The chain screen, [ChainSide] in from each side: the power node, the five DSP tiles and OUT,
 *   drawn at [ChainScale] of their design size with the six stretches of line between them all the
 *   same length (see [ChainLayout]). Its edge is the grey hairline with, while on, a purple ring
 *   inside it and a purple glow that stays inside the screen.
 * - The top screen, [TopMargin] from the top, from the chain screen's left edge to the last tile's
 *   right edge: the global stages as live-data cells (TILT, MBC, LIMITER, ALLPASS), then the
 *   headroom and the L / R output meters, all at [TopContentScale]. More and Settings are raised
 *   squircle buttons on the faceplate to its right, centred over OUT.
 *
 * Everything that looks on or off is driven by one animated global fraction from [powered] (see
 * [animateActive]) times, for the live-data cells, each one's own fraction. The tiles are menu
 * buttons and carry no state of their own; the live-data cells show which stages are on.
 *
 * Powering on also sends the signal down the chain ([ChainSweep], 3 s): a spark leaves the power
 * node, the five lines into the tiles each carry their own spark at once, every tile's border
 * lights round both sides together, then the last tile's spark runs on to light OUT.
 *
 * Laid out in dp at 1280 x 480 and scaled down as a whole on a smaller screen (see [HomeScale]);
 * spare height on a taller screen goes partly to the top screen, the rest to faceplate round the
 * chain screen.
 *
 * [onOpenStage] and [onMore] get the tapped element's bounds in this composable's root (the
 * hosting view's coordinates), for the screen's zoom-open and the overflow menu's anchor.
 * [openingStage] is the tile whose screen is opening; its glow is lit, and the front page then
 * turns into the workspace ([SidebarMorph]) as [morphProgress] runs 0..1, landing on a sidebar
 * column [sidebarWidth] wide.
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
    morphProgress: () -> Float = { 0f },
    sidebarWidth: Dp = 124.dp,
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

            // The chain's geometry, in chain units, for the chain screen's actual width; the top row
            // lines up with it.
            val layout = ChainLayout((maxWidth.value / k - 2 * ChainSide) / ChainScale)
            val topRight = ChainSide + layout.tileRight(HomeStage.entries.size - 1) * ChainScale
            val outCentre = ChainSide + layout.outX * ChainScale

            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .padding(top = s.dp(TopMargin))
                        .fillMaxWidth()
                        .height(s.dp(TopHeight) + (spare * TopShare).dp),
                ) {
                    TopScreen(
                        engine, readout, g, onOpenGlobal,
                        Modifier.offset(x = s.dp(ChainSide)).width(s.dp(topRight - ChainSide)).fillMaxHeight(),
                    )
                    Column(
                        Modifier.align(Alignment.CenterStart).offset(x = s.dp(outCentre - ButtonSize / 2)),
                        verticalArrangement = Arrangement.spacedBy(s.dp(ButtonGap)),
                    ) {
                        FaciaButton(
                            stringResource(R.string.home_tile_more), { onMore(bounds[MoreKey] ?: Rect.Zero) },
                            Modifier.tracked(MoreKey),
                        ) { drawMoreGlyph() }
                        FaciaButton(stringResource(R.string.title_activity_settings), onSettings) { drawSettingsGlyph() }
                    }
                }
                Box(
                    Modifier
                        .padding(top = s.dp(ChainTopGap), bottom = s.dp(BottomFacia))
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    ChainPanel(
                        engine, powered, g, { sweep.value }, openingStage, onTogglePower, layout,
                        onOpenStage = { stage -> onOpenStage(stage, bounds[stage] ?: Rect.Zero) },
                        tileModifier = { stage -> Modifier.tracked(stage) },
                        modifier = Modifier
                            .padding(horizontal = s.dp(ChainSide))
                            .fillMaxWidth()
                            .height(s.dp(ChainHeight * ChainScale))
                            .tracked(ChainKey),
                    )
                }
            }
            val chain = bounds[ChainKey]
            val tiles = HomeStage.entries.mapNotNull { bounds[it] }
            if (openingStage != null && chain != null && tiles.size == HomeStage.entries.size) {
                SidebarMorph(morphProgress, openingStage.ordinal, tiles, chain, sidebarWidth)
            }
        }
    }
}

/**
 * The top screen: the live-data cells, a divider, then the headroom and the meters, all drawn at
 * [TopContentScale] so they fit the screen's height.
 */
@Composable
private fun TopScreen(
    engine: HomeEngineState,
    readout: LevelReadout,
    g: Float,
    onOpenGlobal: (GlobalStage) -> Unit,
    modifier: Modifier,
) {
    val off = stringResource(R.string.home_chip_off)
    Screen(LocalHomeScale.current.dp(14f), modifier) {
        CompositionLocalProvider(LocalHomeScale provides HomeScale(LocalHomeScale.current.k * TopContentScale)) {
            TopScreenContent(engine, readout, g, onOpenGlobal, off)
        }
    }
}

@Composable
private fun TopScreenContent(
    engine: HomeEngineState,
    readout: LevelReadout,
    g: Float,
    onOpenGlobal: (GlobalStage) -> Unit,
    off: String,
) {
    val s = LocalHomeScale.current
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

/**
 * The chain screen: power, the five tiles and OUT, placed by [layout] and drawn at [ChainScale].
 * While the DSP is on, a purple ring sits just inside the grey edge and glows inwards; nothing is
 * drawn outside the screen.
 */
@Composable
private fun ChainPanel(
    engine: HomeEngineState,
    powered: Boolean,
    g: Float,
    sweep: () -> Float,
    openingStage: HomeStage?,
    onTogglePower: () -> Unit,
    layout: ChainLayout,
    onOpenStage: (HomeStage) -> Unit,
    tileModifier: (HomeStage) -> Modifier,
    modifier: Modifier,
) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val radius = s.dp(16f * ChainScale)
    Screen(radius, modifier) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (g <= 0f) return@drawBehind
                val edge = s.dp(2f).toPx()
                val r = radius.toPx()
                // The glow: thin bands fading smoothly to nothing inwards from the ring.
                val band = s.dp(1.5f).toPx()
                for (step in 0 until GlowBands) {
                    val inset = 2 * edge + band * (step + 0.5f)
                    val fade = 1 - (step + 1f) / GlowBands
                    drawRoundRect(
                        signal.copy(alpha = 0.2f * g * fade * fade),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
                        style = Stroke(band),
                    )
                }
                // The ring, just inside the grey edge.
                val inset = edge * 1.5f
                drawRoundRect(
                    signal.copy(alpha = 0.7f * g),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(r - inset),
                    style = Stroke(edge),
                )
            },
        )
        CompositionLocalProvider(LocalHomeScale provides HomeScale(s.k * ChainScale)) {
            val c = LocalHomeScale.current
            SignalPath(g, sweep, spark = powered, layout = layout)
            PowerNode(powered, g, onTogglePower, Modifier.offset(x = c.dp(layout.powerX - ColumnWidth / 2)))
            Row(
                Modifier.offset(x = c.dp(layout.tileLeft(0))).fillMaxHeight().padding(vertical = c.dp(TileInset)),
                horizontalArrangement = Arrangement.spacedBy(c.dp(layout.gap)),
            ) {
                for (stage in HomeStage.entries) {
                    val tile = stage.tile()
                    StageCard(
                        title = stringResource(tile.title),
                        subtitle = stringResource(tile.subtitle),
                        accent = tile.accent,
                        art = tile.art,
                        borderLit = { ChainSweep.tiles(sweep()) },
                        spark = powered,
                        selected = openingStage == stage,
                        onClick = { onOpenStage(stage) },
                        modifier = tileModifier(stage),
                    )
                }
            }
            OutputNode({ ChainSweep.arrived(sweep()) }, Modifier.offset(x = c.dp(layout.outX - OutColumnWidth / 2)))
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

/** More / Settings: a raised squircle button on the faceplate, with a glyph over its label. */
@Composable
private fun FaciaButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    Column(
        modifier
            .size(s.dp(ButtonSize))
            .drawBehind {
                // A drop shadow under the button, so it stands off the plate.
                val outline = Squircle.createOutline(size, layoutDirection, this)
                for (step in 1..3) {
                    translate(top = s.dp(1f + step).toPx()) { drawOutline(outline, Color.Black.copy(alpha = 0.22f)) }
                }
            }
            .clip(Squircle)
            .background(Brush.verticalGradient(listOf(HomePalette.ButtonTop, HomePalette.ButtonBottom)))
            .border(s.dp(1.5f), HomePalette.ButtonEdge, Squircle)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(s.dp(26f), s.dp(17f)), onDraw = glyph)
        Text(
            text = label,
            color = HomePalette.CellLabel,
            fontSize = s.sp(10f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(top = s.dp(4f)),
        )
    }
}

/** An even-sided squircle: a superellipse, |x|⁵ + |y|⁵ = 1, scaled to the button. */
private val Squircle = GenericShape { size, _ ->
    val a = size.width / 2
    val b = size.height / 2
    val steps = 96
    for (i in 0 until steps) {
        val t = 2 * PI * i / steps
        val cx = cos(t)
        val cy = sin(t)
        val x = a + a * sign(cx) * abs(cx).pow(2.0 / 5)
        val y = b + b * sign(cy) * abs(cy).pow(2.0 / 5)
        if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
    }
    close()
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

// The layout in design dp (Figma "Front plate · Home · PROPOSED"): the top screen's margin and
// height at 1280 x 480, the chain screen's side margins, scale and the faceplate round it, and the
// buttons. The chain screen is centred in the height left under the top screen.
private const val TopMargin = 40f
private const val TopHeight = 154f
private const val TopShare = 0.45f
private const val ChainTopGap = 22f
private const val BottomFacia = 15f
private const val ChainSide = 48f
private const val ButtonSize = 58f
private const val ButtonGap = 12f

/** The chain screen's contents (and the screen) at this fraction of their design size. */
private const val ChainScale = 0.82f

/** The chain screen's height in chain units: the old full-height chain panel's. */
private const val ChainHeight = 244f

/** The top screen's contents at this fraction of their design size, to fit its shorter height. */
private const val TopContentScale = TopHeight / 179f

// In the scaled units of the screens they sit in.
private const val CellWidth = 200f
private const val CellHeight = 76f
private const val CellGap = 10f
private const val TileInset = 14f
private const val GlowBands = 20
private const val MoreKey = "more"
private const val ChainKey = "chain"
private const val SweepOnMs = 3000
private const val SweepOffMs = 900

// --- Previews: sample data only. The app passes real engine state. ---

private val PreviewEngine = HomeEngineState(
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
