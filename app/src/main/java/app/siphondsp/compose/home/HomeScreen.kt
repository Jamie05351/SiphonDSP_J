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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.R
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
 * "Front faceplate · redesign", frame "Head unit · 8 · ON · Master volume"):
 * - The chain screen, [ScreenLeft] / [ChainRight] in from the sides: the power node, the five DSP tiles and OUT,
 *   drawn at [ChainScale] of their design size with the six stretches of line between them all the
 *   same length (see [ChainLayout]). Its edge is the grey hairline with, while on, a purple ring
 *   inside it and a purple glow that stays inside the screen.
 * - The top screen, [TopMargin] from the top, the same width as the chain screen: the global
 *   stages as live-data cells (TILT, MBC, LIMITER, ALLPASS), each in its menu's colour, then the
 *   headroom and the L / R output meters, then the live spectrum ([SpectrumStrip]), all at
 *   [TopContentScale]; then, past a dividing line, Settings and More as bare glyphs ([ScreenButton]).
 * - Down the faceplate's left side, the DSP master volume ([VolumeColumn]) spanning the top
 *   screen's top edge to the chain screen's bottom edge: tall + and − buttons round the level on
 *   its own small screen.
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
    readout: () -> LevelReadout,
    meterLevels: () -> LevelReadout,
    openingStage: HomeStage?,
    onTogglePower: () -> Unit,
    onOpenStage: (HomeStage, Rect) -> Unit,
    onOpenGlobal: (GlobalStage) -> Unit,
    onSettings: () -> Unit,
    onMore: (Rect) -> Unit,
    onVolumeStep: (Int) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
    morphProgress: () -> Float = { 0f },
    sidebarWidth: Dp = 124.dp,
    animateTiles: Boolean = true,
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
            /** Records this element's bounds in the root under [key], for taps and the morph. */
            fun Modifier.tracked(key: Any) = onGloballyPositioned { bounds[key] = it.boundsInRoot() }

            // The chain's geometry, in chain units, for the chain screen's actual width; the top
            // screen is the same width.
            val layout = ChainLayout((maxWidth.value / k - ChainRight - ScreenLeft) / ChainScale)

            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .padding(top = s.dp(TopMargin))
                        .fillMaxWidth()
                        .height(s.dp(TopHeight) + (spare * TopShare).dp),
                ) {
                    TopScreen(
                        engine, readout, meterLevels, g, onOpenGlobal, animateTiles,
                        Modifier.padding(start = s.dp(ScreenLeft), end = s.dp(ChainRight)).fillMaxSize(),
                    ) {
                        ScreenButton(stringResource(R.string.title_activity_settings), onSettings) { drawSettingsGlyph() }
                        ScreenButton(
                            stringResource(R.string.home_tile_more), { onMore(bounds[MoreKey] ?: Rect.Zero) },
                            Modifier.tracked(MoreKey),
                        ) { drawMoreGlyph() }
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
                        engine, powered, g, { sweep.value }, openingStage, onTogglePower, layout, animateTiles,
                        onOpenStage = { stage -> onOpenStage(stage, bounds[stage] ?: Rect.Zero) },
                        tileModifier = { stage -> Modifier.tracked(stage) },
                        modifier = Modifier
                            .padding(start = s.dp(ScreenLeft), end = s.dp(ChainRight))
                            .fillMaxWidth()
                            .height(s.dp(ChainHeight * ChainScale))
                            .tracked(ChainKey),
                    )
                }
            }
            // The volume column runs from the top screen's top edge to the chain screen's bottom
            // edge, which sits centred in the height left under the top screen (as laid out above).
            val volumeTop = TopMargin * k
            val chainBoxTop = volumeTop + TopHeight * k + spare * TopShare + ChainTopGap * k
            val chainBoxBottom = maxHeight.value - BottomFacia * k
            val chainBottom = (chainBoxTop + chainBoxBottom + ChainHeight * ChainScale * k) / 2
            VolumeColumn(
                engine.masterVolumeDb, engine.masterMuted, g, onVolumeStep, onToggleMute,
                Modifier
                    .offset(x = s.dp((ScreenLeft - VolumeWidth) / 2), y = volumeTop.dp)
                    .height((chainBottom - volumeTop).coerceAtLeast(0f).dp),
            )
            val chain = bounds[ChainKey]
            val tiles = HomeStage.entries.mapNotNull { bounds[it] }
            if (openingStage != null && chain != null && tiles.size == HomeStage.entries.size) {
                SidebarMorph(morphProgress, openingStage.ordinal, tiles, chain, sidebarWidth)
            }
        }
    }
}

/**
 * The top screen: its content drawn at [TopContentScale] so it fits the screen's height, then a
 * dividing line and the [buttons] (Settings and More) stacked at the right-hand end.
 */
@Composable
private fun TopScreen(
    engine: HomeEngineState,
    readout: () -> LevelReadout,
    meterLevels: () -> LevelReadout,
    g: Float,
    onOpenGlobal: (GlobalStage) -> Unit,
    live: Boolean,
    modifier: Modifier,
    buttons: @Composable () -> Unit,
) {
    val off = stringResource(R.string.home_chip_off)
    val s = LocalHomeScale.current
    Screen(s.dp(14f), modifier) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalHomeScale provides HomeScale(s.k * TopContentScale)) {
                    TopScreenContent(engine, readout, meterLevels, g, onOpenGlobal, off, live)
                }
            }
            Box(
                Modifier
                    .padding(vertical = s.dp(DividerInset))
                    .fillMaxHeight()
                    .width(s.dp(2f))
                    .background(HomePalette.PanelEdge),
            )
            Column(
                Modifier.padding(horizontal = s.dp(ButtonInset)),
                verticalArrangement = Arrangement.spacedBy(s.dp(ButtonGap)),
            ) { buttons() }
        }
    }
}

/**
 * The top screen's content, at [TopContentScale]: the four live-data cells in a 2 x 2 grid, then
 * the output meter. [off] is the cells' text for a stage that is off.
 */
@Composable
private fun TopScreenContent(
    engine: HomeEngineState,
    readout: () -> LevelReadout,
    meterLevels: () -> LevelReadout,
    g: Float,
    onOpenGlobal: (GlobalStage) -> Unit,
    off: String,
    live: Boolean,
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
                    // The limiter lives on the Output screen, so it takes Output's colour.
                    DspColors.Output, engine.limiterDb != null, g, onOpenGlobal,
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
        Spacer(Modifier.width(s.dp(MeterGap)))
        OutputMeter(readout, meterLevels, engine.ceilingDb, g, Modifier.weight(MeterShare).fillMaxHeight())
        Spacer(Modifier.width(s.dp(MeterGap)))
        SpectrumStrip(meterLevels, g, live, Modifier.weight(1f).fillMaxHeight())
    }
}

/** One live-data cell: [stage]'s [label] and [value] in [accent], lit by [on] times [g]; opens [stage]. */
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
    animateTiles: Boolean,
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
                        width = layout.cardWidth,
                        animateArt = animateTiles,
                    )
                }
            }
            OutputNode({ ChainSweep.arrived(sweep()) }, Modifier.offset(x = c.dp(layout.outX - OutColumnWidth / 2)))
        }
    }
}

/** A dark rounded screen set into the faceplate, with its hairline edge. */
@Composable
internal fun Screen(
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

/**
 * Settings / More, at the top screen's right-hand end: just the glyph, in a [ButtonSize] touch
 * target. The label isn't shown, only announced.
 */
@Composable
private fun ScreenButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    Box(
        modifier
            .size(s.dp(ButtonSize))
            .clip(Squircle)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(s.dp(GlyphSize)), onDraw = glyph)
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
    val r = size.width * 0.085f
    for (fx in listOf(0.2f, 0.5f, 0.8f)) drawCircle(HomePalette.Button, r, Offset(size.width * fx, size.height / 2))
}

/** A gear: [GearTeeth] square-ish teeth round a ring with a hole through the middle. */
private fun DrawScope.drawSettingsGlyph() {
    val outer = size.minDimension / 2
    val root = outer * 0.79f
    val tooth = (PI / GearTeeth).toFloat()
    val gear = Path().apply {
        for (k in 0 until GearTeeth) {
            val a = k * 2 * tooth
            val points = listOf(
                a - tooth * 0.55f to root, a - tooth * 0.35f to outer, a + tooth * 0.35f to outer,
                a + tooth * 0.55f to root, a + tooth * 1.45f to root,
            )
            for ((i, point) in points.withIndex()) {
                val (angle, r) = point
                val x = center.x + r * cos(angle)
                val y = center.y + r * sin(angle)
                if (k == 0 && i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        close()
        addOval(Rect(center, outer * 0.33f))
        fillType = PathFillType.EvenOdd
    }
    drawPath(gear, HomePalette.Button)
}

/** A tile's fixed content: its name, what it does, its colour and its artwork. */
private class TileSpec(val title: Int, val subtitle: Int, val accent: Color, val art: TileArt)

/** Each front-page tile's title, subtitle, colour and artwork. */
private fun HomeStage.tile(): TileSpec = when (this) {
    HomeStage.PEQ -> TileSpec(R.string.home_card_peq, R.string.home_card_peq_sub, DspColors.Peq, TileArt.PEQ)
    HomeStage.ALIGNMENT -> TileSpec(R.string.home_card_gains, R.string.home_card_gains_sub, DspColors.Delay, TileArt.DELAY)
    HomeStage.XOVERS -> TileSpec(R.string.home_card_xovers, R.string.home_card_xovers_sub, DspColors.Xover, TileArt.XOVERS)
    HomeStage.DYNAMICS -> TileSpec(R.string.home_card_compressor, R.string.home_card_compressor_sub, DspColors.Comp, TileArt.COMPRESSOR)
    HomeStage.OUTPUT -> TileSpec(R.string.home_card_output, R.string.home_card_output_sub, DspColors.Output, TileArt.OUTPUT)
}

// The layout in design dp (Figma "Head unit · 8 · ON · Master volume", then the volume put on its
// own smaller screen, the top screen run out to the chain screen's width and dropped further from
// the top edge): the top screen's margin and height at 1280 x 480, the screens' side margins (the
// volume panel on the left), the chain's scale and the faceplate round it, and the buttons. The
// chain screen is centred in the height left under the top screen.
private const val TopMargin = 48f
private const val TopHeight = 136f
private const val TopShare = 0.45f
private const val ChainTopGap = 32f
private const val BottomFacia = 32f
// The screens' right margin: the same facia as between and below them.
private const val ChainRight = 32f
// The volume column with that same facia either side of it.
private const val ScreenLeft = VolumeWidth + 2 * ChainRight
private const val ButtonSize = 58f
// The glyphs fill most of their touch targets, so these sit close to fit the top screen.
private const val ButtonGap = 4f
// Either side of Settings and More inside the top screen, and the dividing line's gap top and bottom.
private const val ButtonInset = 20f
private const val DividerInset = 20f
private const val GlyphSize = 42f
private const val GearTeeth = 8

/** The chain screen's contents at this fraction of their design size. */
private const val ChainScale = 0.76f

/** The chain screen's height in chain units: 232 dp at [ChainScale], room round the tiles. */
private const val ChainHeight = 305f

/** The top screen's contents at this fraction of their design size, to fit its shorter height. */
private const val TopContentScale = TopHeight / 179f

// In the scaled units of the screens they sit in.
private const val CellWidth = 168f
private const val CellHeight = 64f
private const val CellGap = 10f
// Above and below the tiles: they stand 225 units tall in the chain screen, as in Figma.
private const val TileInset = 40f
// Between the cells and the output meter, which takes the rest of the top screen.
private const val MeterGap = 40f
// The output meters' share of the top screen's width next to the spectrum's 1.
private const val MeterShare = 2f
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
    masterVolumeDb = -24f,
)

private val PreviewLevelsOn = LevelReadout(leftRmsDb = -9.6f, leftPeakDb = -7.2f, rightRmsDb = -10.6f, rightPeakDb = -7.8f)
private val PreviewLevelsOff = LevelReadout(leftRmsDb = -20f, leftPeakDb = -12.5f, rightRmsDb = -21f, rightPeakDb = -13.2f)

@Preview(name = "Home · DSP ON", widthDp = 1280, heightDp = 480)
@Composable
private fun HomeScreenOnPreview() = BmwDspTheme {
    HomeScreen(
        engine = PreviewEngine,
        powered = true,
        readout = { PreviewLevelsOn },
        meterLevels = { PreviewLevelsOn },
        openingStage = null,
        onTogglePower = {}, onOpenStage = { _, _ -> }, onOpenGlobal = {}, onSettings = {}, onMore = {},
        onVolumeStep = {}, onToggleMute = {},
    )
}

@Preview(name = "Home · DSP OFF", widthDp = 1280, heightDp = 480)
@Composable
private fun HomeScreenOffPreview() = BmwDspTheme {
    HomeScreen(
        engine = PreviewEngine,
        powered = false,
        readout = { PreviewLevelsOff },
        meterLevels = { PreviewLevelsOff },
        openingStage = null,
        onTogglePower = {}, onOpenStage = { _, _ -> }, onOpenGlobal = {}, onSettings = {}, onMore = {},
        onVolumeStep = {}, onToggleMute = {},
    )
}

@Preview(name = "Home · wide", widthDp = 1920, heightDp = 540)
@Composable
private fun HomeScreenWidePreview() = BmwDspTheme {
    HomeScreen(
        engine = PreviewEngine,
        powered = true,
        readout = { PreviewLevelsOn },
        meterLevels = { PreviewLevelsOn },
        openingStage = null,
        onTogglePower = {}, onOpenStage = { _, _ -> }, onOpenGlobal = {}, onSettings = {}, onMore = {},
        onVolumeStep = {}, onToggleMute = {},
    )
}

@Preview(name = "Home · phone", widthDp = 891, heightDp = 411)
@Composable
private fun HomeScreenPhonePreview() = BmwDspTheme {
    HomeScreen(
        engine = PreviewEngine,
        powered = true,
        readout = { PreviewLevelsOn },
        meterLevels = { PreviewLevelsOn },
        openingStage = null,
        onTogglePower = {}, onOpenStage = { _, _ -> }, onOpenGlobal = {}, onSettings = {}, onMore = {},
        onVolumeStep = {}, onToggleMute = {},
    )
}
