package app.siphondsp.compose.launcher

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.compose.controls.DspColors
import app.siphondsp.compose.home.ChainLayout
import app.siphondsp.compose.home.ChainSweep
import app.siphondsp.compose.home.ColumnWidth
import app.siphondsp.compose.home.GlobalChip
import app.siphondsp.compose.home.GlobalStage
import app.siphondsp.compose.home.HomeEngineState
import app.siphondsp.compose.home.HomePalette
import app.siphondsp.compose.home.HomeScale
import app.siphondsp.compose.home.HomeStage
import app.siphondsp.compose.home.LocalHomeScale
import app.siphondsp.compose.home.OutColumnWidth
import app.siphondsp.compose.home.OutputMeter
import app.siphondsp.compose.home.OutputNode
import app.siphondsp.compose.home.PowerNode
import app.siphondsp.compose.home.SignalPath
import app.siphondsp.compose.home.StageCard
import app.siphondsp.compose.home.VolumeColumn
import app.siphondsp.compose.home.animateActive
import app.siphondsp.compose.home.drawSettingsGlyph
import app.siphondsp.compose.home.drawSpark
import app.siphondsp.compose.home.formatDb
import app.siphondsp.compose.home.tile
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.launcher.AppEntry
import app.siphondsp.launcher.CarFunction
import app.siphondsp.launcher.HomeSlots
import app.siphondsp.launcher.SlotTarget
import app.siphondsp.view.LevelReadout

/** What is open over the home screen: nothing, the app drawer, or the car menu. */
sealed interface LauncherOverlay {
    data object None : LauncherOverlay
    /** The app list; with [pickFor], tapping an app puts it in that home slot instead of opening it. */
    data class Drawer(val pickFor: Int? = null) : LauncherOverlay
    /** The head unit's own screens and switches; with [pickFor], tapping one fills that slot. */
    data class CarMenu(val pickFor: Int? = null) : LauncherOverlay
}

/** Everything the home screen can ask its host to do. */
interface LauncherActions {
    fun togglePower()
    fun openStage(stage: HomeStage)
    fun openGlobal(stage: GlobalStage)
    fun dspSettings()
    fun more(bounds: Rect)
    fun volumeStep(steps: Int)
    fun toggleMute()
    fun launchApp(app: AppEntry)
    fun runCar(function: CarFunction)
    fun assignSlot(index: Int, target: SlotTarget)
    fun swapSlots(a: Int, b: Int)
    fun appInfo(app: AppEntry)
    fun androidSettings()
    fun homeAppSettings()
    /** Add to home found no empty slot. */
    fun homeFull()
}

/**
 * The front page as the head unit's home screen, laid out in dp at 1280 x 480 like [HomeScreen]
 * (scaled down to fit a smaller screen): the car wallpaper with the car as the hero on the right,
 * the DSP master volume down the far left, nine app slots in an even 3 x 3 grid in the space left
 * of the car (no card behind them), the app drawer and car menu at the top right, and the DSP bar
 * along the bottom: power, the four global stages and the output meters.
 *
 * Tapping the bar ([expanded]) raises the signal chain out of it as floating screens over the
 * blurred, dimmed home screen: More and Settings, then power, the five DSP tiles and OUT,
 * centred. The master volume stays in place and in focus throughout. There is no facia; each piece is its own opaque screen.
 *
 * [apps] is null until the installed apps first load. [carFunctions] are the car functions this head
 * unit has. [overlay] is the drawer or car menu when one is open.
 */
@Composable
fun LauncherHome(
    engine: HomeEngineState,
    powered: Boolean,
    readout: () -> LevelReadout,
    meterLevels: () -> LevelReadout,
    apps: List<AppEntry>?,
    slots: List<SlotTarget>,
    carFunctions: List<CarFunction>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    overlay: LauncherOverlay,
    onOverlayChange: (LauncherOverlay) -> Unit,
    openingStage: HomeStage?,
    animateTiles: Boolean,
    actions: LauncherActions,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val k = minOf(1f, maxWidth.value / DesignW, maxHeight.value / DesignH)
        val scale = HomeScale(k)
        val g = animateActive(powered, "launcher power")
        val covered = expanded || overlay != LauncherOverlay.None
        val blur by animateDpAsState(if (covered) 16.dp else 0.dp, tween(PanelMs), label = "home blur")
        // Without blur (before Android 12) the home screen is dimmed harder so what's over it reads.
        val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dimTo = when {
            expanded -> if (blurs) 0.5f else 0.75f
            covered -> if (blurs) 0.6f else 0.85f
            else -> 0f
        }
        val dim by animateFloatAsState(dimTo, tween(PanelMs), label = "home dim")
        val homeAlpha by animateFloatAsState(if (covered) 0f else 1f, tween(PanelMs / 2), label = "home fade")

        CompositionLocalProvider(LocalHomeScale provides scale) {
            // The home screen itself, blurred while something is over it.
            Box(Modifier.fillMaxSize().blur(blur)) {
                Image(
                    painterResource(R.drawable.launcher_wallpaper), contentDescription = null,
                    contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
                    modifier = Modifier.fillMaxSize(),
                )
                // The app grid and corner buttons fade away while the chain or a drawer is up, so
                // what's over the blurred background isn't competing with them.
                if (homeAlpha > 0f) {
                    Design(Modifier.align(Alignment.Center).graphicsLayer { alpha = homeAlpha }) {
                        AppGrid(apps, slots, onOverlayChange, actions)
                        CornerButton(DrawerX, stringResource(R.string.launcher_drawer), { onOverlayChange(LauncherOverlay.Drawer()) }) { drawDrawerGlyph() }
                        CornerButton(CarX, stringResource(R.string.launcher_car_menu), { onOverlayChange(LauncherOverlay.CarMenu()) }) { drawSettingsGlyph() }
                    }
                }
            }
            if (dim > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dim))
                        .then(
                            // Tapping the dimmed space above the chain closes it.
                            if (expanded) Modifier.clickable(remember { MutableInteractionSource() }, null) { onExpandedChange(false) } else Modifier,
                        ),
                )
            }
            Design(Modifier.align(Alignment.Center)) {
                // The chain rises out of the bar, so it's clipped at the bar's top edge.
                Box(Modifier.size(scale.dp(DesignW), scale.dp(BarTop)).clipToBounds()) {
                    AnimatedVisibility(
                        visible = expanded,
                        enter = slideInVertically(tween(PanelMs)) { it } + fadeIn(tween(PanelMs / 2)),
                        exit = slideOutVertically(tween(PanelMs)) { it } + fadeOut(tween(PanelMs)),
                    ) {
                        FloatingChain(engine, powered, g, openingStage, animateTiles, actions)
                    }
                }
                DspBar(engine, powered, g, readout, meterLevels, expanded, onExpandedChange, actions)
                // The master volume stays put and in focus whatever is open; the chain and drawers
                // leave its column clear.
                CompositionLocalProvider(LocalHomeScale provides HomeScale(k * VolumeScale)) {
                    VolumeColumn(
                        engine.masterVolumeDb, engine.masterMuted, g, actions::volumeStep, actions::toggleMute,
                        Modifier.offset(scale.dp(Margin), scale.dp(Margin)).height(scale.dp(GridBottom - Margin)),
                    )
                }
            }
        }
        when (overlay) {
            is LauncherOverlay.Drawer -> AppDrawer(apps, slots, overlay.pickFor, scale.dp(GridLeft), onOverlayChange, actions)
            is LauncherOverlay.CarMenu -> CarMenu(carFunctions, overlay.pickFor, scale.dp(GridLeft), onOverlayChange, actions)
            LauncherOverlay.None -> {}
        }
    }
}

/** The 1280 x 480 design area, at the current scale. */
@Composable
private fun Design(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val s = LocalHomeScale.current
    Box(modifier.size(s.dp(DesignW), s.dp(DesignH)), content = content)
}

/** A design-unit rectangle inside [Design]. */
@Composable
private fun Modifier.at(x: Float, y: Float, w: Float, h: Float): Modifier {
    val s = LocalHomeScale.current
    return this.offset(s.dp(x), s.dp(y)).size(s.dp(w), s.dp(h))
}

// --- The 3 x 3 app grid ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGrid(apps: List<AppEntry>?, slots: List<SlotTarget>, onOverlayChange: (LauncherOverlay) -> Unit, actions: LauncherActions) {
    val cellW = (GridRight - GridLeft) / GridCols
    val cellH = (GridBottom - Margin) / GridRows
    for (i in 0 until HomeSlots.SLOTS) {
        val x = GridLeft + (i % GridCols) * cellW + (cellW - IconSize) / 2
        val y = Margin + (i / GridCols) * cellH + (cellH - IconSize) / 2
        var menu by remember { mutableStateOf(false) }
        val target = slots.getOrElse(i) { SlotTarget.Empty }
        Box(Modifier.at(x, y, IconSize, IconSize)) {
            SlotIcon(
                target, apps,
                onClick = {
                    when (target) {
                        SlotTarget.Empty -> onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
                        is SlotTarget.Car -> actions.runCar(target.function)
                        is SlotTarget.App -> apps?.firstOrNull { it.component == target.component }
                            ?.let(actions::launchApp) ?: onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
                    }
                },
                onLongClick = { menu = true },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(R.string.launcher_slot_choose_app) { menu = false; onOverlayChange(LauncherOverlay.Drawer(pickFor = i)) }
                MenuItem(R.string.launcher_slot_choose_car) { menu = false; onOverlayChange(LauncherOverlay.CarMenu(pickFor = i)) }
                if (i > 0) MenuItem(R.string.launcher_slot_move_earlier) { menu = false; actions.swapSlots(i, i - 1) }
                if (i < HomeSlots.SLOTS - 1) MenuItem(R.string.launcher_slot_move_later) { menu = false; actions.swapSlots(i, i + 1) }
                if (target != SlotTarget.Empty) MenuItem(R.string.launcher_slot_clear) { menu = false; actions.assignSlot(i, SlotTarget.Empty) }
            }
        }
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

/** One slot: the app's own icon, a car function's tile, or an empty "+" to fill. No label under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotIcon(target: SlotTarget, apps: List<AppEntry>?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(IconSize * 0.24f))
    val app = (target as? SlotTarget.App)?.let { t -> apps?.firstOrNull { it.component == t.component } }
    val icon = app?.icon
    val description = when (target) {
        SlotTarget.Empty -> stringResource(R.string.launcher_empty_slot)
        is SlotTarget.Car -> stringResource(target.function.label)
        is SlotTarget.App -> app?.label ?: target.component.packageName
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
            target is SlotTarget.Car -> CarTile(target.function, Modifier.fillMaxSize())
            else -> EmptySlot(Modifier.fillMaxSize())
        }
    }
}

/** A car function as an icon: a dark rounded screen with a purple edge and its short name. */
@Composable
private fun CarTile(function: CarFunction, modifier: Modifier) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(IconSize * 0.24f))
    Box(
        modifier
            .clip(shape)
            .background(HomePalette.Panel)
            .border(s.dp(2f), BmwTheme.colors.sliderHeadroom, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(function.short), color = Color.White, fontSize = s.sp(17f), fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(s.dp(6f)),
        )
    }
}

@Composable
private fun EmptySlot(modifier: Modifier) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(IconSize * 0.24f))
    Box(
        modifier
            .clip(shape)
            .background(HomePalette.Panel.copy(alpha = 0.55f))
            .border(s.dp(1.5f), HomePalette.IdleEdge, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = HomePalette.Caption, fontSize = s.sp(36f))
    }
}

/** The drawer and car-menu buttons in the top-right corner. */
@Composable
private fun CornerButton(x: Float, label: String, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(14f))
    Box(
        Modifier
            .at(x, Margin, CornerSize, CornerSize)
            .clip(shape)
            .background(HomePalette.Panel)
            .border(s.dp(2f), HomePalette.PanelEdge, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(s.dp(CornerSize * 0.5f)), onDraw = glyph)
    }
}

/** Nine small rounded squares: the app drawer. */
private fun DrawScope.drawDrawerGlyph() {
    val cell = size.minDimension / 3
    val dot = cell * 0.62f
    for (r in 0 until 3) for (c in 0 until 3) {
        drawRoundRect(
            HomePalette.Button,
            topLeft = Offset(c * cell + (cell - dot) / 2, r * cell + (cell - dot) / 2),
            size = Size(dot, dot),
            cornerRadius = CornerRadius(dot * 0.25f),
        )
    }
}

// --- The DSP bar ---

@Composable
private fun DspBar(
    engine: HomeEngineState,
    powered: Boolean,
    g: Float,
    readout: () -> LevelReadout,
    meterLevels: () -> LevelReadout,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: LauncherActions,
) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val shape = RoundedCornerShape(s.dp(16f))
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(PanelMs), label = "bar chevron")
    val description = stringResource(if (expanded) R.string.launcher_dsp_bar_close else R.string.launcher_dsp_bar)
    // The power-on sweep, as in the chain: the signal leaves the power button and runs along the
    // bar, lighting its edge and each part in turn as it passes; it runs back, faster, on power off.
    val sweep by animateFloatAsState(
        if (powered) 1f else 0f,
        tween(if (powered) SweepOnMs else SweepOffMs, easing = LinearEasing),
        label = "bar sweep",
    )
    /** How lit the part spanning [from]..[to] of the bar is: lit as the sweep crosses it. */
    fun reached(from: Float, to: Float) = minOf(g, ((sweep - from) / (to - from)).coerceIn(0f, 1f))
    val px = s.k * LocalDensity.current.density
    Row(
        Modifier
            .at(BarSide, BarTop, DesignW - 2 * BarSide, BarHeight)
            .drawBehind {
                // A soft purple halo round the bar, growing along it behind the sweep.
                val spread = s.dp(10f).toPx()
                val lit = size.width * sweep
                clipRect(left = -spread, top = -spread, right = lit + spread, bottom = size.height + spread) {
                    for (step in 1..3) {
                        val o = spread * step / 3f
                        drawRoundRect(
                            signal.copy(alpha = 0.10f * g / step),
                            topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
                            cornerRadius = CornerRadius(s.dp(16f).toPx() + o),
                        )
                    }
                }
            }
            .clip(shape)
            .background(HomePalette.Panel)
            .border(s.dp(2f), HomePalette.PanelEdge, shape)
            .drawWithContent {
                drawContent()
                // The lit edge: purple up to the sweep's front, along the top and bottom at once,
                // with a spark at the front of each while powering on.
                val edge = s.dp(2f).toPx()
                val lit = size.width * sweep
                if (lit <= 0f) return@drawWithContent
                clipRect(right = lit) {
                    drawRoundRect(
                        signal.copy(alpha = 0.8f * g),
                        topLeft = Offset(edge / 2, edge / 2), size = Size(size.width - edge, size.height - edge),
                        cornerRadius = CornerRadius(s.dp(16f).toPx() - edge / 2), style = Stroke(edge),
                    )
                }
                if (powered && sweep < 1f) {
                    drawSpark(Offset(lit, edge), signal, px)
                    drawSpark(Offset(lit, size.height - edge), signal, px)
                }
            }
            .clickable(role = Role.Button, onClickLabel = description) { onExpandedChange(!expanded) }
            .semantics { contentDescription = description }
            .padding(horizontal = s.dp(12f), vertical = s.dp(8f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarPower(powered, g, actions::togglePower)
        Column(Modifier.padding(start = s.dp(12f)).width(s.dp(104f))) {
            Text(
                stringResource(if (powered) R.string.home_power_on else R.string.home_power_off),
                color = lerp(HomePalette.Caption, Color(0xFFC084FC), reached(0.02f, SweepChips)), fontSize = s.sp(17f), fontWeight = FontWeight.Bold, maxLines = 1,
            )
            Text(
                stringResource(if (powered) R.string.home_power_on_hint else R.string.home_power_off_hint),
                color = HomePalette.Muted, fontSize = s.sp(12f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        CompositionLocalProvider(LocalHomeScale provides HomeScale(s.k * ChipScale)) {
            GlobalChips(engine, { i -> reached(SweepChips + i * SweepChip, SweepChips + (i + 1) * SweepChip) }, actions, Modifier.padding(start = s.dp(8f)))
        }
        Box(Modifier.padding(horizontal = s.dp(14f)).width(s.dp(2f)).fillMaxHeight().background(HomePalette.PanelEdge))
        CompositionLocalProvider(LocalHomeScale provides HomeScale(s.k * MeterScale)) {
            OutputMeter(readout, meterLevels, engine.ceilingDb, reached(SweepMeter, SweepEnd), Modifier.weight(1f).fillMaxHeight())
        }
        val chevron = reached(SweepEnd, 1f)
        Canvas(Modifier.padding(start = s.dp(10f)).size(s.dp(28f)).rotate(turn)) { drawChevronUp(lerp(HomePalette.Button, signal, chevron)) }
    }
}

/** The bar's power button: the chain's power node in small, with no caption under it. */
@Composable
private fun BarPower(powered: Boolean, g: Float, onToggle: () -> Unit) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val description = stringResource(R.string.home_power_description)
    Canvas(
        Modifier
            .size(s.dp(BarNode))
            .clip(CircleShape)
            .toggleable(value = powered, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
    ) {
        val r = size.minDimension / 2
        val rim = s.dp(2.5f + g).toPx()
        drawCircle(HomePalette.NodeFill, r)
        drawCircle(lerp(HomePalette.NodeEdgeOff, signal, g), r - rim / 2, style = Stroke(rim))
        val glyph = lerp(HomePalette.Caption, Color(0xFFE9D5FF), g)
        val stroke = s.dp(3.5f).toPx()
        val gr = r * 0.36f
        val c = center + Offset(0f, s.dp(1.5f).toPx())
        drawArc(
            glyph, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = c - Offset(gr, gr), size = Size(gr * 2, gr * 2), style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(glyph, c - Offset(0f, gr + s.dp(4f).toPx()), c - Offset(0f, s.dp(3f).toPx()), stroke, StrokeCap.Round)
    }
}

/**
 * TILT, MBC, LIMITER and ALLPASS in a row, as on the old top screen; each opens its stage. [g] is
 * how lit chip 0..3 is, so the power-on sweep can light them one after another.
 */
@Composable
private fun GlobalChips(engine: HomeEngineState, g: (Int) -> Float, actions: LauncherActions, modifier: Modifier) {
    val s = LocalHomeScale.current
    val off = stringResource(R.string.home_chip_off)
    val chip = Modifier.width(s.dp(ChipWidth)).fillMaxHeight()
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(s.dp(10f))) {
        GlobalChip(
            stringResource(R.string.home_chip_tilt), engine.tiltDb?.let { "${formatDb(it, signed = true)} dB" } ?: off,
            BmwTheme.colors.sliderTilt, engine.tiltDb != null, g(0), { actions.openGlobal(GlobalStage.TILT) }, chip,
        )
        GlobalChip(
            stringResource(R.string.home_chip_mbc),
            if (engine.mbcBands > 0) stringResource(R.string.home_chip_bands, engine.mbcBands) else off,
            DspColors.Comp, engine.mbcBands > 0, g(1), { actions.openGlobal(GlobalStage.MBC) }, chip,
        )
        GlobalChip(
            stringResource(R.string.home_chip_limiter), engine.limiterDb?.let { "${formatDb(it)} dB" } ?: off,
            DspColors.Output, engine.limiterDb != null, g(2), { actions.openGlobal(GlobalStage.LIMITER) }, chip,
        )
        GlobalChip(
            stringResource(R.string.home_chip_allpass),
            if (engine.allPassOn) pluralStringResource(R.plurals.home_chip_allpass_outputs, engine.allPassOutputs, engine.allPassOutputs) else off,
            DspColors.Allpass, engine.allPassOn, g(3), { actions.openGlobal(GlobalStage.ALLPASS) }, chip,
        )
    }
}

private fun DrawScope.drawChevronUp(colour: Color) {
    val w = size.width
    val h = size.height
    drawLine(colour, Offset(w * 0.18f, h * 0.66f), Offset(w * 0.5f, h * 0.34f), w * 0.11f, StrokeCap.Round)
    drawLine(colour, Offset(w * 0.5f, h * 0.34f), Offset(w * 0.82f, h * 0.66f), w * 0.11f, StrokeCap.Round)
}

// --- The raised chain ---

/**
 * What rises out of the bar: a row of floating screens (More, Settings), then the chain,
 * centred between the volume column and the right edge — power, the five tiles and OUT on the signal line, each its own screen with nothing
 * behind it. Fills the area above the bar.
 */
@Composable
private fun FloatingChain(
    engine: HomeEngineState,
    powered: Boolean,
    g: Float,
    openingStage: HomeStage?,
    animateTiles: Boolean,
    actions: LauncherActions,
) {
    val s = LocalHomeScale.current
    val sweep = animateFloatAsState(
        if (powered) 1f else 0f,
        tween(if (powered) SweepOnMs else SweepOffMs, easing = LinearEasing),
        label = "launcher chain sweep",
    )
    var moreBounds by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.size(s.dp(DesignW), s.dp(BarTop))) {
        FloatButton(
            Modifier.at(DesignW - ChainSide - 2 * PanelButton - 12f, PanelTop, PanelButton, PanelRow).onGloballyPositioned { moreBounds = it.boundsInRoot() },
            stringResource(R.string.launcher_more), null,
        ) { actions.more(moreBounds) }
        FloatButton(Modifier.at(DesignW - ChainSide - PanelButton, PanelTop, PanelButton, PanelRow), stringResource(R.string.launcher_dsp_settings), null, actions::dspSettings)

        val chainW = DesignW - ChainLeft - ChainSide
        val layout = ChainLayout(chainW)
        Box(Modifier.at(ChainLeft, ChainTop, chainW, BarTop - ChainTop - ChainBottomGap)) {
            SignalPath(g, { sweep.value }, spark = powered, layout = layout)
            PowerNode(powered, g, actions::togglePower, Modifier.offset(x = s.dp(layout.powerX - ColumnWidth / 2)))
            Row(
                Modifier.offset(x = s.dp(layout.tileLeft(0))).fillMaxHeight().padding(vertical = s.dp(TileInset)),
                horizontalArrangement = Arrangement.spacedBy(s.dp(layout.gap)),
            ) {
                for (stage in HomeStage.entries) {
                    val tile = stage.tile()
                    StageCard(
                        title = stringResource(tile.title),
                        subtitle = stringResource(tile.subtitle),
                        accent = tile.accent,
                        art = tile.art,
                        borderLit = { ChainSweep.tiles(sweep.value) },
                        spark = powered,
                        selected = openingStage == stage,
                        onClick = { actions.openStage(stage) },
                        width = layout.cardWidth,
                        animateArt = animateTiles,
                    )
                }
            }
            OutputNode({ ChainSweep.arrived(sweep.value) }, Modifier.offset(x = s.dp(layout.outX - OutColumnWidth / 2)))
        }
    }
}

/** A floating screen used as a button: a title and, optionally, a hint under it. */
@Composable
private fun FloatButton(modifier: Modifier, title: String, hint: String?, onClick: () -> Unit) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(12f))
    Column(
        modifier
            .clip(shape)
            .background(HomePalette.Panel)
            .border(s.dp(1.5f), HomePalette.ButtonEdge, shape)
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = s.sp(17f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (hint != null) Text(hint, color = HomePalette.Muted, fontSize = s.sp(12f), maxLines = 1)
    }
}

// The layout, in design dp at 1280 x 480.
internal const val DesignW = 1280f
internal const val DesignH = 480f
private const val Margin = 14f
private const val VolumeScale = 0.75f
private const val GridLeft = 112f
private const val GridRight = 676f
private const val GridBottom = 376f
private const val GridCols = 3
private const val GridRows = 3
private const val IconSize = 86f
private const val CornerSize = 58f
private const val CarX = DesignW - Margin - CornerSize
private const val DrawerX = CarX - 12f - CornerSize
private const val BarSide = 10f
private const val BarTop = 388f
private const val BarHeight = 82f
private const val BarNode = 58f
private const val ChipScale = 0.82f
private const val ChipWidth = 150f
private const val MeterScale = 0.56f
private const val ChainSide = 30f
// Right of the volume column, which stays on screen.
private const val ChainLeft = GridLeft
private const val PanelTop = 20f
private const val PanelRow = 56f
private const val PanelButton = 200f
private const val ChainTop = 92f
private const val ChainBottomGap = 10f
private const val TileInset = 24f
private const val PanelMs = 380
private const val SweepOnMs = 3000
private const val SweepOffMs = 900
// Where along the bar's sweep (0..1) each part lights: the chips one after another, then the
// meters, then the chevron at the far end.
private const val SweepChips = 0.12f
private const val SweepChip = 0.11f
private const val SweepMeter = 0.62f
private const val SweepEnd = 0.92f
