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
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
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
import app.siphondsp.compose.controls.knobFocusRing
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
import app.siphondsp.compose.home.drawNodeGlow
import app.siphondsp.compose.home.OutSize
import app.siphondsp.compose.home.NodeSize
import app.siphondsp.compose.home.NodeGlow
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
import app.siphondsp.launcher.LauncherCustomisation
import app.siphondsp.launcher.Track
import app.siphondsp.launcher.SlotTarget
import app.siphondsp.view.LevelReadout

/** What is open over the home screen: nothing, the app drawer, or the car menu. */
sealed interface LauncherOverlay {
    data object None : LauncherOverlay
    /** The app list; with [pickFor], tapping an app puts it in that home slot instead of opening it. */
    data class Drawer(val pickFor: Int? = null) : LauncherOverlay
    /** The head unit's own screens and switches; with [pickFor], tapping one fills that slot. */
    data class CarMenu(val pickFor: Int? = null) : LauncherOverlay
    /** The apps hidden from the drawer; tapping one shows it again. */
    data object HiddenApps : LauncherOverlay
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
    /** Pick an image for the icon of [key] (a [SlotTarget.encode] string). */
    fun pickIcon(key: String)
    fun resetIcon(key: String)
    fun setHidden(app: AppEntry, hidden: Boolean)
    fun pickWallpaper()
    fun resetWallpaper()
    fun backUpLayout()
    fun restoreLayout()
    /** Opens Android's notification-access settings so now playing can see media sessions. */
    fun allowNowPlaying()
    fun mediaPlayPause()
    fun mediaNext()
    fun mediaPrevious()
    fun openTrackApp(track: Track)
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
 * unit has. [overlay] is the drawer or car menu when one is open. [look] is the user's wallpaper,
 * custom icons and hidden apps; [track] is what's playing, shown on a card above the car.
 *
 * Everything that can be tapped can also be reached with the iDrive knob (a D-pad), with the
 * DSP pages' focus ring on whatever it's on.
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
    look: LauncherCustomisation.Look = LauncherCustomisation.Look(),
    track: Track? = null,
    nowPlayingAllowed: Boolean = true,
    knobFocusRequests: Int = 0,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val knobEntry = remember { FocusRequester() }
    val overlayEntry = remember { FocusRequester() }
    // Whether knob focus is inside the open drawer or car menu; see the key handler below.
    var overlayHasFocus by remember { mutableStateOf(false) }
    // The host bumps [knobFocusRequests] when the iDrive knob is used while nothing here has focus:
    // focus then lands on the open drawer or car menu, else on the DSP bar (on screen in every
    // other state), and moves on from there.
    LaunchedEffect(knobFocusRequests) {
        if (knobFocusRequests > 0) {
            if (overlay != LauncherOverlay.None) overlayEntry.requestFocus() else knobEntry.requestFocus()
        }
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusGroup()
            // The knob moves focus in the direction it's pushed or turned. Where there is nothing
            // that way (a knob that only turns sends one axis), it steps to the next or previous
            // control instead, so every control is reachable either way. Past the last control it
            // wraps round to the DSP bar rather than leaving the screen with nothing focused. The
            // focused control sees the key first, so one that uses arrows itself (the search box's
            // cursor) keeps them.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val (direction, step) = when (event.key) {
                    Key.DirectionLeft -> FocusDirection.Left to FocusDirection.Previous
                    Key.DirectionRight -> FocusDirection.Right to FocusDirection.Next
                    Key.DirectionUp -> FocusDirection.Up to FocusDirection.Previous
                    Key.DirectionDown -> FocusDirection.Down to FocusDirection.Next
                    else -> return@onKeyEvent false
                }
                val moved = focusManager.moveFocus(direction) || focusManager.moveFocus(step)
                // With a drawer or menu open, focus stays in it: a move that would land on the bar or
                // volume behind it, or off the end, wraps to the panel's Close button instead.
                val inOverlay = overlay != LauncherOverlay.None
                when {
                    inOverlay && (!moved || !view.hasFocus() || !overlayHasFocus) -> overlayEntry.requestFocus()
                    !inOverlay && (!moved || !view.hasFocus()) -> knobEntry.requestFocus()
                }
                true
            },
    ) {
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
        // 0 home, 1 raised: the bar's move to the top, the volume column's growth and the chain
        // screen's rise all follow it together.
        val raise by animateFloatAsState(if (expanded) 1f else 0f, tween(PanelMs), label = "raise")

        CompositionLocalProvider(LocalHomeScale provides scale) {
            // The home screen itself, blurred while something is over it.
            Box(Modifier.fillMaxSize().blur(blur)) {
                val wallpaper = look.wallpaper
                if (wallpaper != null) {
                    Image(
                        wallpaper, contentDescription = null,
                        contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Image(
                        painterResource(R.drawable.launcher_wallpaper), contentDescription = null,
                        contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // The app grid and corner buttons fade away while the chain or a drawer is up, so
                // what's over the blurred background isn't competing with them.
                if (homeAlpha > 0f) {
                    Design(Modifier.align(Alignment.Center).graphicsLayer { alpha = homeAlpha }) {
                        AppGrid(apps, slots, look.icons, onOverlayChange, actions)
                        if (track != null) NowPlayingCard(track, actions)
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
                // Raised, the front page's old layout comes back: the bar moves up to where the top
                // screen was and the chain screen rises into its old place under it.
                if (raise > 0f) {
                    ChainScreen(engine, powered, g, raise, openingStage, animateTiles, actions)
                }
                DspBar(engine, powered, g, raise, readout, meterLevels, expanded, onExpandedChange, actions, knobEntry)
                // The master volume stays in focus whatever is open; raised, it runs down beside the
                // chain screen as on the old front page. The chain and drawers leave its column clear.
                CompositionLocalProvider(LocalHomeScale provides HomeScale(k * VolumeScale)) {
                    VolumeColumn(
                        engine.masterVolumeDb, engine.masterMuted, g, actions::volumeStep, actions::toggleMute,
                        Modifier
                            .offset(scale.dp(Margin), scale.dp(TopMargin))
                            .height(scale.dp(lerp(GridBottom, ChainBottom, raise) - TopMargin)),
                    )
                }
            }
        }
        // Drawers start under the head unit's top banner and stop above the DSP bar, which stays
        // usable under them.
        val letterbox = ((maxHeight - scale.dp(DesignH)) / 2).coerceAtLeast(0.dp)
        val overlayBottom = scale.dp(DesignH - BarTop + 12f) + letterbox
        val overlayTop = scale.dp(TopMargin) + letterbox
        Box(Modifier.fillMaxSize().onFocusChanged { overlayHasFocus = it.hasFocus }) {
        when (overlay) {
            is LauncherOverlay.Drawer -> AppDrawer(apps, slots, look, overlay.pickFor, scale.dp(GridLeft), overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions)
            is LauncherOverlay.CarMenu -> CarMenu(
                carFunctions, overlay.pickFor, look.wallpaper != null, nowPlayingAllowed, scale.dp(GridLeft), overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions,
            )
            LauncherOverlay.HiddenApps -> HiddenApps(apps, look, scale.dp(GridLeft), overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions)
            LauncherOverlay.None -> {}
        }
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
private fun AppGrid(
    apps: List<AppEntry>?,
    slots: List<SlotTarget>,
    icons: Map<String, ImageBitmap>,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    // The same pitch across as down, so the gaps between icons are even both ways; the grid is
    // centred in the space between the volume column and the car.
    val pitch = (GridBottom - TopMargin) / GridRows
    val left = (GridLeft + GridRight) / 2 - (pitch * (GridCols - 1) + IconSize) / 2
    for (i in 0 until HomeSlots.SLOTS) {
        val x = left + (i % GridCols) * pitch
        val y = TopMargin + (i / GridCols) * pitch + (pitch - IconSize) / 2
        var menu by remember { mutableStateOf(false) }
        val target = slots.getOrElse(i) { SlotTarget.Empty }
        Box(Modifier.at(x, y, IconSize, IconSize)) {
            SlotIcon(
                target, apps, icons[target.encode()],
                onClick = {
                    when (target) {
                        SlotTarget.Empty -> onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
                        is SlotTarget.Car -> actions.runCar(target.function)
                        is SlotTarget.App -> if (apps != null) {
                            apps.firstOrNull { it.component == target.component }
                                ?.let(actions::launchApp) ?: onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
                        }
                    }
                },
                onLongClick = { menu = true },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(R.string.launcher_slot_choose_app) { menu = false; onOverlayChange(LauncherOverlay.Drawer(pickFor = i)) }
                MenuItem(R.string.launcher_slot_choose_car) { menu = false; onOverlayChange(LauncherOverlay.CarMenu(pickFor = i)) }
                if (i > 0) MenuItem(R.string.launcher_slot_move_earlier) { menu = false; actions.swapSlots(i, i - 1) }
                if (i < HomeSlots.SLOTS - 1) MenuItem(R.string.launcher_slot_move_later) { menu = false; actions.swapSlots(i, i + 1) }
                if (target != SlotTarget.Empty) {
                    MenuItem(R.string.launcher_change_icon) { menu = false; actions.pickIcon(target.encode()) }
                    if (target.encode() in icons) MenuItem(R.string.launcher_reset_icon) { menu = false; actions.resetIcon(target.encode()) }
                    MenuItem(R.string.launcher_slot_clear) { menu = false; actions.assignSlot(i, SlotTarget.Empty) }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

/**
 * One slot: the user's custom icon ([custom]) or the app's own icon, a car function's tile, or an
 * empty "+" to fill. No label under it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotIcon(target: SlotTarget, apps: List<AppEntry>?, custom: ImageBitmap?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(IconSize * 0.24f))
    val app = (target as? SlotTarget.App)?.let { t -> apps?.firstOrNull { it.component == t.component } }
    val icon = custom ?: app?.icon
    val description = when (target) {
        SlotTarget.Empty -> stringResource(R.string.launcher_empty_slot)
        is SlotTarget.Car -> stringResource(target.function.label)
        is SlotTarget.App -> app?.label ?: target.component.packageName
    }
    Box(
        Modifier
            .fillMaxSize()
            .knobFocusRing(s.dp(IconSize * 0.24f))
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
            .at(x, TopMargin, CornerSize, CornerSize)
            .knobFocusRing(s.dp(14f))
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
    raise: Float,
    readout: () -> LevelReadout,
    meterLevels: () -> LevelReadout,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: LauncherActions,
    knobEntry: FocusRequester,
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
    var moreBounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        Modifier
            // Along the bottom at home; raised, at the top right of the volume column, where the
            // front page's top screen used to be.
            .at(
                lerp(BarSide, RaisedLeft, raise), lerp(BarTop, TopMargin, raise),
                lerp(DesignW - 2 * BarSide, DesignW - RaisedLeft - BarSide, raise), BarHeight,
            )
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
            .knobFocusRing(s.dp(16f))
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
            // Where the iDrive knob's focus enters the home screen.
            .focusRequester(knobEntry)
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
        if (expanded) {
            // More and Settings sit at the bar's end while raised, as on the old top screen.
            BarButton(stringResource(R.string.launcher_more), Modifier.onGloballyPositioned { moreBounds = it.boundsInRoot() }, { actions.more(moreBounds) }) { drawMoreDots() }
            BarButton(stringResource(R.string.launcher_dsp_settings), Modifier, actions::dspSettings) { drawSettingsGlyph() }
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
            .knobFocusRing(s.dp(BarNode / 2))
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
    val chip = Modifier.width(s.dp(ChipWidth)).fillMaxHeight().knobFocusRing(s.dp(10f))
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
 * The chain screen, in the front page's old place under the raised bar: a dark screen with the
 * grey hairline edge and, while the DSP is on, a purple edge. Inside, IN, the five DSP tiles and
 * OUT on the signal line. Power lives on the bar, so the chain starts at IN, a mirror of OUT.
 * It rises into place with [raise].
 */
@Composable
private fun ChainScreen(
    engine: HomeEngineState,
    powered: Boolean,
    g: Float,
    raise: Float,
    openingStage: HomeStage?,
    animateTiles: Boolean,
    actions: LauncherActions,
) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val sweep = animateFloatAsState(
        if (powered) 1f else 0f,
        tween(if (powered) SweepOnMs else SweepOffMs, easing = LinearEasing),
        label = "launcher chain sweep",
    )
    val shape = RoundedCornerShape(s.dp(16f))
    val width = DesignW - RaisedLeft - BarSide
    // As on the old front page: the chain drawn at ChainScale of its design size, so the tiles keep
    // their old size and the even gaps between them.
    val layout = ChainLayout(width / ChainScale)
    Box(
        Modifier
            .at(RaisedLeft, ChainScreenTop + (1 - raise) * ChainRise, width, ChainScreenHeight)
            .graphicsLayer { alpha = raise }
            .clip(shape)
            .background(HomePalette.Panel)
            .border(s.dp(2f), lerp(HomePalette.PanelEdge, signal, 0.7f * g), shape),
    ) {
      CompositionLocalProvider(LocalHomeScale provides HomeScale(s.k * ChainScale)) {
        val c = LocalHomeScale.current
        SignalPath(g, { sweep.value }, spark = powered, layout = layout)
        InputNode(g, Modifier.offset(x = c.dp(layout.powerX - ColumnWidth / 2)))
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
                    borderLit = { ChainSweep.tiles(sweep.value) },
                    spark = powered,
                    selected = openingStage == stage,
                    onClick = { actions.openStage(stage) },
                    modifier = Modifier.knobFocusRing(c.dp(16f)),
                    width = layout.cardWidth,
                    animateArt = animateTiles,
                )
            }
        }
        OutputNode({ ChainSweep.arrived(sweep.value) }, Modifier.offset(x = c.dp(layout.outX - OutColumnWidth / 2)))
      }
    }
}

/**
 * Where the signal comes in: OUT's mirror at the start of the chain, "IN / from apps", ringed
 * purple while the DSP is on and white while it's bypassed. Not a control; power is on the bar.
 * It sits in the power button's old column, so a short stretch of line joins it to the chain's.
 */
@Composable
private fun InputNode(g: Float, modifier: Modifier) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    Box(modifier.width(s.dp(ColumnWidth)).fillMaxHeight()) {
        Canvas(Modifier.align(Alignment.Center).size(s.dp(ColumnWidth), s.dp(OutSize))) {
            val r = s.dp(OutSize / 2).toPx()
            val c = center
            // From the circle's edge to where the chain's own line starts (the old button's edge).
            drawLine(
                lerp(HomePalette.Idle, signal, g), Offset(c.x + r, c.y), Offset(c.x + s.dp(NodeSize / 2).toPx(), c.y),
                s.dp(2.5f).toPx(),
            )
            drawNodeGlow(signal, g, s.dp(NodeGlow).toPx())
            drawCircle(HomePalette.OutFill, r, c)
            val rim = s.dp(3f + g).toPx()
            drawCircle(lerp(Color.White.copy(alpha = 0.85f), signal, g), r - rim / 2, c, style = Stroke(rim))
        }
        Text(
            stringResource(R.string.launcher_in), color = Color.White, fontSize = s.sp(20f), fontWeight = FontWeight.Bold,
            maxLines = 1, modifier = Modifier.align(Alignment.Center),
        )
        Text(
            stringResource(R.string.launcher_from_apps), color = HomePalette.Muted, fontSize = s.sp(14f), maxLines = 1,
            modifier = Modifier.align(Alignment.Center).offset(y = s.dp(OutSize / 2 + 20f)),
        )
    }
}

/** A glyph button on the raised bar (More, Settings). */
@Composable
private fun BarButton(label: String, modifier: Modifier, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    Box(
        modifier
            .padding(start = s.dp(8f))
            .size(s.dp(BarHeight - 22f))
            .knobFocusRing(s.dp(10f))
            .clip(RoundedCornerShape(s.dp(10f)))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(s.dp(30f)), onDraw = glyph)
    }
}

/** Three dots across the middle: More. */
private fun DrawScope.drawMoreDots() {
    val r = size.width * 0.085f
    for (fx in listOf(0.2f, 0.5f, 0.8f)) drawCircle(HomePalette.Button, r, Offset(size.width * fx, size.height / 2))
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

// --- Now playing ---

/**
 * What's playing, on a small screen above the car between the grid and the corner buttons: the
 * album art, title and artist (tap to open the playing app), and previous / play-pause / next.
 */
@Composable
private fun NowPlayingCard(track: Track, actions: LauncherActions) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(14f))
    Row(
        Modifier
            .at(NowX, TopMargin, DrawerX - 14f - NowX, NowHeight)
            .clip(shape)
            .background(HomePalette.Panel)
            .border(s.dp(1.5f), HomePalette.PanelEdge, shape)
            .padding(s.dp(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .knobFocusRing(s.dp(10f))
                .clip(RoundedCornerShape(s.dp(10f)))
                .clickable(role = Role.Button) { actions.openTrackApp(track) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val art = track.art
            val artSize = Modifier.size(s.dp(NowHeight - 14f)).clip(RoundedCornerShape(s.dp(8f)))
            if (art != null) {
                Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = artSize)
            } else {
                Box(artSize.background(HomePalette.IdleFill))
            }
            Column(Modifier.padding(horizontal = s.dp(12f)).weight(1f)) {
                Text(track.title, color = Color.White, fontSize = s.sp(17f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.artist.isNotEmpty()) {
                    Text(track.artist, color = HomePalette.Subtitle, fontSize = s.sp(13f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        MediaButton(stringResource(R.string.launcher_media_previous), actions::mediaPrevious) { drawSkip(forward = false) }
        MediaButton(
            stringResource(if (track.playing) R.string.launcher_media_pause else R.string.launcher_media_play), actions::mediaPlayPause,
        ) { if (track.playing) drawPause() else drawPlay() }
        MediaButton(stringResource(R.string.launcher_media_next), actions::mediaNext) { drawSkip(forward = true) }
    }
}

@Composable
private fun MediaButton(label: String, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    Box(
        Modifier
            .size(s.dp(NowHeight - 14f))
            .knobFocusRing(s.dp(10f))
            .clip(RoundedCornerShape(s.dp(10f)))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(s.dp(22f)), onDraw = glyph)
    }
}

private fun DrawScope.drawPlay() {
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width * 0.2f, 0f)
        lineTo(size.width, size.height / 2)
        lineTo(size.width * 0.2f, size.height)
        close()
    }
    drawPath(p, HomePalette.Button)
}

private fun DrawScope.drawPause() {
    val w = size.width * 0.28f
    drawRect(HomePalette.Button, Offset(size.width * 0.12f, 0f), Size(w, size.height))
    drawRect(HomePalette.Button, Offset(size.width * 0.6f, 0f), Size(w, size.height))
}

/** A triangle and a bar: skip forward, or mirrored, back. */
private fun DrawScope.drawSkip(forward: Boolean) {
    val bar = size.width * 0.14f
    val p = androidx.compose.ui.graphics.Path().apply {
        if (forward) {
            moveTo(0f, 0f); lineTo(size.width - bar, size.height / 2); lineTo(0f, size.height)
        } else {
            moveTo(size.width, 0f); lineTo(bar, size.height / 2); lineTo(size.width, size.height)
        }
        close()
    }
    drawPath(p, HomePalette.Button)
    drawRect(HomePalette.Button, Offset(if (forward) size.width - bar else 0f, 0f), Size(bar, size.height))
}

// The layout, in design dp at 1280 x 480.
internal const val DesignW = 1280f
internal const val DesignH = 480f
private const val Margin = 14f
// Clear of the head unit's own top banner (back, recents, screen off...), which draws over apps;
// the old front page left the same room.
private const val TopMargin = 48f
private const val VolumeScale = 0.75f
private const val GridLeft = 112f
private const val GridRight = 676f
private const val GridBottom = 376f
private const val GridCols = 3
private const val GridRows = 3
private const val IconSize = 86f
private const val CornerSize = 58f
// The now-playing card: from just right of the grid to the corner buttons, above the car.
private const val NowX = 690f
private const val NowHeight = 56f
private const val CarX = DesignW - Margin - CornerSize
private const val DrawerX = CarX - 12f - CornerSize
private const val BarSide = 10f
private const val BarTop = 388f
private const val BarHeight = 82f
private const val BarNode = 58f
private const val ChipScale = 0.82f
private const val ChipWidth = 150f
private const val MeterScale = 0.56f
// Raised: the bar and the chain screen start right of the volume column, the bar at the top and the
// chain screen centred in the height under it, at the old front page's size; it rises [ChainRise]
// into place. The volume column runs down to the chain screen's bottom edge, as it used to.
private const val RaisedLeft = GridLeft
private const val ChainScale = 0.76f
// The chain screen's height in chain units (232 dp at ChainScale), and the room above and below the
// tiles inside it (they stand 225 units tall), both as on the old front page.
private const val ChainUnits = 305f
private const val TileInset = 40f
private const val ChainScreenHeight = ChainUnits * ChainScale
private const val ChainSpaceTop = TopMargin + BarHeight
private const val ChainScreenTop = ChainSpaceTop + (BarTop + BarHeight - ChainSpaceTop - ChainScreenHeight) / 2
private const val ChainBottom = ChainScreenTop + ChainScreenHeight
private const val ChainRise = 60f
private const val PanelMs = 380
private const val SweepOnMs = 3000
private const val SweepOffMs = 900
// Where along the bar's sweep (0..1) each part lights: the chips one after another, then the
// meters, then the chevron at the far end.
private const val SweepChips = 0.12f
private const val SweepChip = 0.11f
private const val SweepMeter = 0.62f
private const val SweepEnd = 0.92f
