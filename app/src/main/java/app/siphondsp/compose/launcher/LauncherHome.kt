package app.siphondsp.compose.launcher

import android.os.Build
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.compose.home.EdgeHalo
import app.siphondsp.compose.home.EdgeHaloAlpha
import app.siphondsp.compose.home.EdgeWidth
import app.siphondsp.compose.home.neonEdge
import app.siphondsp.compose.home.VolumePod
import app.siphondsp.compose.home.PowerNode
import app.siphondsp.compose.home.CardWidth
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
    /** Opens the Android settings page for what the Spotify / Waze swipe still lacks. */
    fun allowAppSwipe()
    fun mediaPlayPause()
    /** Play only (never pause), for the music tile's Play while it shows nothing playing. */
    fun mediaPlay()
    fun mediaNext()
    fun mediaPrevious()
    fun openTrackApp(track: Track)
    /** Takes the home tile [key] (a [HomeTile.key]) out of the tile row. */
    fun hideTile(key: String)
    /** Puts every hidden home tile back in the tile row. */
    fun showHiddenTiles()
}

/**
 * The front page as the head unit's home screen, laid out in dp at 1280 x 480 like [HomeScreen]
 * (scaled down to fit a smaller screen): the car wallpaper, a slim master volume strip at the far
 * left, then the double-width music tile pinned beside it, then the other home tiles in a fixed
 * two-by-two grid as wide as the music tile, the app drawer and car menu at the top right, and the
 * DSP dock along the bottom: power, the five DSP tiles, the headroom at OUT, then More (which holds
 * the DSP settings). A dock tile opens its page.
 *
 * [apps] is null until the installed apps first load. [carFunctions] are the car functions this head
 * unit has. [overlay] is the drawer or car menu when one is open. [look] is the user's wallpaper,
 * custom icons and hidden apps (and hidden tiles); [track] is what's playing, shown on the music
 * tile; [lastPlayer] is the package of the last app seen playing, which the music tile names when
 * nothing is.
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
    overlay: LauncherOverlay,
    onOverlayChange: (LauncherOverlay) -> Unit,
    openingStage: HomeStage?,
    animateTiles: Boolean,
    actions: LauncherActions,
    modifier: Modifier = Modifier,
    look: LauncherCustomisation.Look = LauncherCustomisation.Look(),
    track: Track? = null,
    lastPlayer: String? = null,
    nowPlayingAllowed: Boolean = true,
    appSwipeAllowed: Boolean = true,
    knobFocusRequests: Int = 0,
    onKnobFocusResult: (Boolean) -> Unit = {},
    onHasFocusChanged: (Boolean) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val knobEntry = remember { FocusRequester() }
    val overlayEntry = remember { FocusRequester() }
    // Whether knob focus is inside the open drawer or car menu; see the key handler below.
    var overlayHasFocus by remember { mutableStateOf(false) }
    // The host bumps [knobFocusRequests] when the iDrive knob is used while nothing here has focus:
    // focus then lands on the open drawer or car menu, else on the DSP bar (on screen in every
    // other state), and moves on from there. Each request is handled once: entering composition
    // again (a rebuilt view) starts from the current count, so focus is never taken without a
    // knob press.
    var handledKnobRequests by remember { mutableIntStateOf(knobFocusRequests) }
    LaunchedEffect(overlay) {
        if (overlay != LauncherOverlay.None) {
            // A tile focused before the menu opened must not keep handling Enter while hidden.
            focusManager.clearFocus()
            if (!view.isInTouchMode) runCatching { overlayEntry.requestFocus() }
        }
    }
    LaunchedEffect(knobFocusRequests) {
        if (knobFocusRequests > handledKnobRequests) {
            handledKnobRequests = knobFocusRequests
            val taken = runCatching {
                if (overlay != LauncherOverlay.None) overlayEntry.requestFocus() else knobEntry.requestFocus()
            }.getOrDefault(false)
            onKnobFocusResult(taken)
        }
    }
    DesignFontScale {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .background(Color.Black)
                // Whether any control here has focus, for the host: the view can keep Android focus
                // after the focused control goes away, which wouldn't say whether the knob has a target.
                .onFocusChanged { onHasFocusChanged(it.hasFocus) }
                .focusGroup()
                // The knob moves focus in the direction it's pushed or turned. Where there is nothing
                // that way (a knob that only turns sends one axis), it steps to the next or previous
                // control instead, which wraps round from the last control to the first, so every
                // control is reachable either way. If focus would otherwise leave the screen it goes
                // back to the DSP bar. The focused control sees the key first, so one that uses arrows
                // itself (the search box's cursor) keeps them.
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
                    // Guarded: the target can be between compositions (an overlay switching) as a key
                    // arrives, and a failed request must never take the home screen down.
                    when {
                        inOverlay && (!moved || !view.hasFocus() || !overlayHasFocus) -> runCatching { overlayEntry.requestFocus() }
                        !inOverlay && (!moved || !view.hasFocus()) -> runCatching { knobEntry.requestFocus() }
                    }
                    true
                },
        ) {
            // Do not compose fixed-size controls during a transient zero-size layout.
            if (maxWidth <= HomeEdgePadding * 2 || maxHeight <= 0.dp) return@BoxWithConstraints
            val geometry = launcherGeometry(maxWidth.value, maxHeight.value)
            val scale = HomeScale(geometry.scale)
            val designWidth = geometry.designWidth
            val g = animateActive(powered, "launcher power")
            val covered = overlay != LauncherOverlay.None
            val blur by animateDpAsState(if (covered) 16.dp else 0.dp, tween(PanelMs), label = "home blur")
            // Without blur (before Android 12) the home screen is dimmed harder so what's over it reads.
            val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val dimTo = when {
                covered -> if (blurs) 0.6f else 0.85f
                else -> 0f
            }
            val dim by animateFloatAsState(dimTo, tween(PanelMs), label = "home dim")
            val homeAlpha by animateFloatAsState(if (covered) 0f else 1f, tween(PanelMs / 2), label = "home fade")

            CompositionLocalProvider(LocalHomeScale provides scale) {
                // The home screen itself, blurred while something is over it.
                Box(Modifier.fillMaxSize().blur(blur)) {
                    val wallpaper = look.wallpaper
                    if (wallpaper != null) {
                        Image(wallpaper, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Image(
                            painterResource(R.drawable.launcher_wallpaper), contentDescription = null,
                            contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd, modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // The volume and tiles fade away while a drawer or menu is up, so what's
                    // over the blurred background isn't competing with them.
                    if (homeAlpha > 0f) {
                        Design(designWidth, Modifier.align(Alignment.Center).graphicsLayer { alpha = homeAlpha }) {
                            VolumePod(
                                engine.masterVolumeDb, engine.masterMuted, g, actions::volumeStep, actions::toggleMute,
                                Modifier.at(PodX, TilesTop, PodWidth, TileHeight),
                            )
                            TileGrid(apps, slots, look.hidden, track, lastPlayer, onOverlayChange, actions)
                            CornerButton(designWidth - 2 * CornerSize - 12f, stringResource(R.string.launcher_drawer), { onOverlayChange(LauncherOverlay.Drawer()) }) { drawDrawerGlyph() }
                            CornerButton(designWidth - CornerSize, stringResource(R.string.launcher_car_menu), { onOverlayChange(LauncherOverlay.CarMenu()) }) { drawCarGlyph() }
                        }
                    }
                }
                if (dim > 0f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
                }
                // Retain animation state and the requester node while a menu is open. An
                // unplaced dock is neither drawn nor a target for touch or focus navigation.
                Design(designWidth, Modifier.align(Alignment.Center).dockVisibility(covered)) {
                    Dock(designWidth, engine, powered, g, readout, openingStage, animateTiles, actions, knobEntry)
                }
            }
            // Menus use the space below the head unit's top banner, including the dock area.
            val letterbox = ((maxHeight - scale.dp(DesignH)) / 2).coerceAtLeast(0.dp)
            val overlayBottom = HomeEdgePadding
            val overlayTop = scale.dp(TopMargin) + letterbox
            Box(Modifier.fillMaxSize().onFocusChanged { overlayHasFocus = it.hasFocus }) {
            when (overlay) {
                is LauncherOverlay.Drawer -> AppDrawer(apps, slots, look, overlay.pickFor, HomeEdgePadding, overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions)
                is LauncherOverlay.CarMenu -> CarMenu(
                    carFunctions, overlay.pickFor, look.wallpaper != null, HomeTile.anyHidden(look.hidden), nowPlayingAllowed, appSwipeAllowed, HomeEdgePadding, overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions,
                )
                LauncherOverlay.HiddenApps -> HiddenApps(apps, look, HomeEdgePadding, overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions)
                LauncherOverlay.None -> {}
            }
            }
        }
    }
}

internal data class LauncherGeometry(val scale: Float, val designWidth: Float)

/** Fit the original design inside the padded width, then expand it when height limits scale. */
internal fun launcherGeometry(widthDp: Float, heightDp: Float): LauncherGeometry {
    val availableWidth = widthDp - HomeEdgePadding.value * 2
    require(availableWidth > 0f && heightDp > 0f)
    val scale = minOf(1f, availableWidth / DesignW, heightDp / DesignH)
    return LauncherGeometry(scale, maxOf(DesignW, availableWidth / scale))
}

/** Keep the dock alive without placing its subtree while a launcher menu covers it. */
internal fun Modifier.dockVisibility(covered: Boolean): Modifier =
    this.then(if (covered) Modifier.clearAndSetSemantics { } else Modifier)
        .layout { measurable, constraints ->
            val dock = measurable.measure(constraints)
            layout(dock.width, dock.height) {
                if (!covered) dock.placeRelative(0, 0)
            }
        }

/**
 * Text at its design size, whatever the system font size: the launcher is laid out in fixed
 * 1280 x 480 design units, and a larger font size overflowed its fixed boxes (the now-playing card
 * lost its lines to it on the head unit).
 */
@Composable
internal fun DesignFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f), content = content)
}

/** The full available width at the current vertical design scale, with 15 dp screen edges. */
@Composable
private fun Design(width: Float, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val s = LocalHomeScale.current
    Box(modifier.size(s.dp(width), s.dp(DesignH)), content = content)
}

/** A design-unit rectangle inside [Design]. */
@Composable
private fun Modifier.at(x: Float, y: Float, w: Float, h: Float): Modifier {
    val s = LocalHomeScale.current
    return this.offset(s.dp(x), s.dp(y)).size(s.dp(w), s.dp(h))
}


// --- The tile row ---

/**
 * The tiles beside the volume: the [MusicTile] pinned on the left, then the other tiles in a fixed
 * grid of [HomeTile.Columns] columns as wide as the music tile ([HomeTile.visible]: every other
 * [HomeTile] not in [hidden], row by row). Every tile is on screen at once, with nothing to swipe
 * to; a hidden tile leaves its place empty at the end of the grid.
 */
@Composable
private fun TileGrid(
    apps: List<AppEntry>?,
    slots: List<SlotTarget>,
    hidden: Set<String>,
    track: Track?,
    lastPlayer: String?,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    Box(Modifier.at(TilesX, TilesTop, MusicWidth, TileHeight)) {
        MusicTile(slots, apps, track, lastPlayer, onOverlayChange, actions)
    }
    HomeTile.visible(hidden).forEachIndexed { n, tile ->
        val column = n % HomeTile.Columns
        val row = n / HomeTile.Columns
        Box(Modifier.at(GridX + column * (TileWidth + TileGap), TilesTop + row * (GridTileHeight + TileGap), TileWidth, GridTileHeight)) {
            Tile(tile, slots, apps, onOverlayChange, actions)
        }
    }
}

/** [tile], opening the app or car function kept in its slot. Hold for the slot's menu. */
@Composable
private fun Tile(
    tile: HomeTile,
    slots: List<SlotTarget>,
    apps: List<AppEntry>?,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    val i = tile.ordinal
    var menu by remember { mutableStateOf(false) }
    val target = slots.getOrElse(i) { SlotTarget.Empty }
    val app = (target as? SlotTarget.App)?.let { t -> apps?.firstOrNull { it.component == t.component } }
    val open = {
        when (target) {
            SlotTarget.Empty -> onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
            is SlotTarget.Car -> actions.runCar(target.function)
            is SlotTarget.App -> if (apps != null) {
                app?.let(actions::launchApp) ?: onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
            }
        }
    }
    val attached = when (target) {
        is SlotTarget.App -> app?.label ?: target.component.packageName
        is SlotTarget.Car -> stringResource(target.function.label)
        SlotTarget.Empty -> stringResource(R.string.launcher_tile_choose)
    }
    Box(Modifier.fillMaxSize()) {
        // The edge wears the attached app's own colour; a car function or an empty slot keeps the tile's.
        HomeTileFace(tile, attached, open, { menu = true }, edge = app?.accent ?: tile.accent)
        SlotMenu(menu, { menu = false }, i, target, onOverlayChange, actions, hide = { actions.hideTile(tile.key) })
    }
}

/** A slot's menu: choose an app or car function, clear it, and (except the music tile) hide the tile. */
@Composable
private fun SlotMenu(
    expanded: Boolean,
    dismiss: () -> Unit,
    slot: Int,
    target: SlotTarget,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
    hide: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        MenuItem(R.string.launcher_slot_choose_app) { dismiss(); onOverlayChange(LauncherOverlay.Drawer(pickFor = slot)) }
        MenuItem(R.string.launcher_slot_choose_car) { dismiss(); onOverlayChange(LauncherOverlay.CarMenu(pickFor = slot)) }
        if (target != SlotTarget.Empty) {
            MenuItem(R.string.launcher_slot_clear) { dismiss(); actions.assignSlot(slot, SlotTarget.Empty) }
        }
        if (hide != null) MenuItem(R.string.launcher_tile_hide) { dismiss(); hide() }
    }
}

/**
 * The music tile, pinned at the left of the tile row and never hidden: the album art over the whole
 * tile, and on a dark fade along its bottom the title, then the artist and player beside previous,
 * play / pause and next.
 * Whatever is playing shows here, from any player. With nothing playing it names the last player
 * (else the app in the music slot) with its icon, and offers Play. Tapping opens the player;
 * holding opens the slot's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicTile(
    slots: List<SlotTarget>,
    apps: List<AppEntry>?,
    track: Track?,
    lastPlayer: String?,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    val s = LocalHomeScale.current
    val tile = HomeTile.MUSIC
    val i = tile.ordinal
    var menu by remember { mutableStateOf(false) }
    val target = slots.getOrElse(i) { SlotTarget.Empty }
    val slotApp = (target as? SlotTarget.App)?.let { t -> apps?.firstOrNull { it.component == t.component } }
    // The player: what's playing, else the last app seen playing, else the music slot's app. The
    // slot's own entry when it is that package (a package can list several), else its first entry.
    val playerPackage = track?.packageName ?: lastPlayer ?: slotApp?.component?.packageName
    val player = slotApp?.takeIf { it.component.packageName == playerPackage }
        ?: apps?.firstOrNull { it.component.packageName == playerPackage }
    val open: () -> Unit = {
        when {
            track != null -> actions.openTrackApp(track)
            player != null -> actions.launchApp(player)
            target is SlotTarget.Car -> actions.runCar(target.function)
            // Nothing to open yet: choose the music slot's app. Not before the apps have loaded.
            apps != null -> onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
            else -> {}
        }
    }
    val title = stringResource(tile.title)
    val heading = track?.title ?: player?.label ?: title
    val detail = track?.artist ?: stringResource(R.string.launcher_nothing_playing)
    // The player's label only: never a package id (apps still loading, or the player uninstalled
    // or without a launcher entry), which then simply isn't named.
    val playerName = player?.label.orEmpty()
    val corner = s.dp(MusicCorner)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .knobFocusRing(corner, grow = true)
                .neonEdge(MusicCorner, player?.accent ?: tile.accent)
                .clip(RoundedCornerShape(corner))
                .background(HomePalette.TileBase)
                .combinedClickable(role = Role.Button, onClick = open, onLongClick = { menu = true })
                .semantics { contentDescription = listOf(title, heading, track?.artist.orEmpty(), playerName).filter { it.isNotEmpty() }.distinct().joinToString(": ") },
        ) {
            val art = track?.art
            if (art != null) {
                // The album art over the whole tile: what the tile is this size for.
                Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                // No art (or nothing playing): the tile's own picture, faint, with the player's icon
                // in the middle of the space above the text.
                Image(
                    painterResource(tile.image), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f },
                )
                player?.icon?.let { icon ->
                    Box(Modifier.fillMaxWidth().height(s.dp(TileHeight - MusicBand)), contentAlignment = Alignment.Center) {
                        Image(icon, contentDescription = null, modifier = Modifier.size(s.dp(MusicIcon)).clip(RoundedCornerShape(s.dp(18f))))
                    }
                }
            }
            // A dark fade up from the bottom, so the text and controls read over any art.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        MusicFadeStart to Color.Transparent,
                        MusicFadeSolid to HomePalette.TileBase.copy(alpha = 0.88f),
                        1f to HomePalette.TileBase.copy(alpha = 0.94f),
                    ),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(s.dp(MusicPad))) {
                Text(
                    heading, color = Color.White, fontSize = s.sp(18f), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = s.dp(6f)), verticalAlignment = Alignment.CenterVertically) {
                    // The artist and player take what the controls leave, so they never run under them.
                    Column(Modifier.weight(1f).clipToBounds()) {
                        Text(detail, color = HomePalette.Subtitle, fontSize = s.sp(13f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (playerName.isNotEmpty() && playerName != heading) {
                            Text(
                                playerName, color = player?.accent ?: tile.accent, fontSize = s.sp(12f), fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = s.dp(2f)),
                            )
                        }
                    }
                    Spacer(Modifier.width(s.dp(MusicInfoGap)))
                    Row(
                        Modifier.width(s.dp(MediaControlsWidth)).height(s.dp(MediaButtonSize)),
                        horizontalArrangement = Arrangement.spacedBy(s.dp(MediaButtonGap)),
                    ) {
                        if (track != null) {
                            MediaButton(stringResource(R.string.launcher_media_previous), actions::mediaPrevious) { drawSkip(forward = false) }
                            MediaButton(
                                stringResource(if (track.playing) R.string.launcher_media_pause else R.string.launcher_media_play), actions::mediaPlayPause,
                            ) { if (track.playing) drawPause() else drawPlay() }
                            MediaButton(stringResource(R.string.launcher_media_next), actions::mediaNext) { drawSkip(forward = true) }
                        } else {
                            // Nothing playing: only Play, in its usual place, which resumes the last player.
                            // Play, not play / pause: a session can be playing without a title (so no
                            // track), and a toggle would then pause it.
                            Spacer(Modifier.width(s.dp(MediaButtonSize)))
                            MediaButton(stringResource(R.string.launcher_media_play), actions::mediaPlay) { drawPlay() }
                        }
                    }
                }
            }
        }
        // No Hide: the music tile always shows.
        SlotMenu(menu, { menu = false }, i, target, onOverlayChange, actions, hide = null)
    }
}

/** A media control: [MediaButtonSize] square, the minimum touch size at the base scale. */
@Composable
private fun MediaButton(label: String, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    Box(
        Modifier
            .size(s.dp(MediaButtonSize))
            .knobFocusRing(s.dp(12f))
            .clip(RoundedCornerShape(s.dp(12f)))
            .background(HomePalette.Panel.copy(alpha = 0.6f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(s.dp(20f)), onDraw = glyph)
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

// --- The dock ---

/**
 * The DSP along the bottom of the home screen, inside the 15 dp screen edges, with a
 * [DockInset] bottom inset:
 * the front page's own chain (power, the five tiles and the headroom at OUT) at [DockScale] of its
 * size, then More (the DSP settings are in it). A tile opens its stage's page; power works straight
 * from the dock.
 * The panel's edge is lit purple while the DSP is on.
 */
@Composable
private fun Dock(
    width: Float,
    engine: HomeEngineState,
    powered: Boolean,
    g: Float,
    readout: () -> LevelReadout,
    openingStage: HomeStage?,
    animateTiles: Boolean,
    actions: LauncherActions,
    knobEntry: FocusRequester,
) {
    val s = LocalHomeScale.current
    val chainWidth = width - DockButtonsWidth
    val signal = BmwTheme.colors.sliderHeadroom
    val sweep = animateFloatAsState(
        if (powered) 1f else 0f,
        tween(if (powered) SweepOnMs else SweepOffMs, easing = LinearEasing),
        label = "dock sweep",
    )
    val px = s.k * LocalDensity.current.density
    var moreBounds by remember { mutableStateOf(Rect.Zero) }
    val shape = RoundedCornerShape(s.dp(DockCorner))
    // The wallpaper darkens a little towards the panel, so it sits into the page.
    Box(
        Modifier
            .at(0f, DockTop - DockFade, width, DockFade)
            .background(Brush.verticalGradient(listOf(Color.Transparent, HomePalette.Panel.copy(alpha = 0.35f)))),
    )
    // The panel: its edge solid purple all the way round while on, as the chain tiles' edges are.
    Box(
        Modifier
            .at(0f, DockTop, width, DockHeight)
            .neonEdge(DockCorner, lerp(HomePalette.PanelEdge, signal, g), glow = g)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(HomePalette.Panel.copy(alpha = DockShadeTop), HomePalette.Panel.copy(alpha = DockShadeBottom)),
                ),
            ),
    )
    // The chain over the panel, unclipped so the tiles' halos show in full.
    Box(Modifier.at(0f, DockChainTop, width, DockChainHeight)) {
        Box(Modifier.size(s.dp(chainWidth), s.dp(DockChainHeight))) {
            CompositionLocalProvider(LocalHomeScale provides HomeScale(s.k * DockScale)) {
                val c = LocalHomeScale.current
                val layout = ChainLayout(chainWidth / DockScale, maxCardWidth = CardWidth)
                SignalPath(g, { sweep.value }, spark = powered, layout = layout)
                // No caption under the button: the panel is only as tall as the tiles. Its colour says on or off.
                PowerNode(powered, g, actions::togglePower, Modifier.offset(x = c.dp(layout.powerX - ColumnWidth / 2)), caption = false)
                Row(
                    Modifier.offset(x = c.dp(layout.tileLeft(0))).fillMaxHeight().padding(vertical = c.dp(DockTileInset)),
                    horizontalArrangement = Arrangement.spacedBy(c.dp(layout.gap)),
                ) {
                    for (stage in HomeStage.entries) {
                        val tile = stage.tile()
                        StageCard(
                            title = stringResource(tile.title),
                            subtitle = null,
                            accent = tile.accent,
                            art = tile.art,
                            borderLit = { ChainSweep.tiles(sweep.value) },
                            spark = powered,
                            selected = openingStage == stage,
                            onClick = { actions.openStage(stage) },
                            modifier = Modifier
                                .knobFocusRing(c.dp(16f), grow = true)
                                // The first tile is where the iDrive knob's focus enters the home screen.
                                .then(if (stage == HomeStage.entries.first()) Modifier.focusRequester(knobEntry) else Modifier),
                            width = layout.cardWidth,
                            animateArt = animateTiles,
                        )
                    }
                }
                HeadroomNode(
                    readout, engine.ceilingDb, g, { ChainSweep.arrived(sweep.value) },
                    Modifier.offset(x = c.dp(layout.outX - ColumnWidth / 2)),
                )
            }
        }
        Box(
            Modifier
                .offset(x = s.dp(chainWidth), y = s.dp(DockTop - DockChainTop))
                .height(s.dp(DockHeight))
                .padding(vertical = s.dp(20f))
                .width(s.dp(2f))
                .background(HomePalette.PanelEdge),
        )
        Column(
            Modifier.offset(x = s.dp(chainWidth + 2f), y = s.dp(DockTop - DockChainTop)).width(s.dp(DockButtonsWidth - 2f)).height(s.dp(DockHeight)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(s.dp(8f), Alignment.CenterVertically),
        ) {
            BarButton(stringResource(R.string.launcher_more), Modifier.onGloballyPositioned { moreBounds = it.boundsInRoot() }, { actions.more(moreBounds) }) { drawMoreDots() }
        }
    }
}

/**
 * OUT, showing the headroom: the figure in the middle of the ring. The ring
 * is white while bypassed, as OUT's, and turns the DSP's purple as the power-on sweep reaches it
 * ([lit], 0..1), or red once the output goes past the ceiling. Edged like the chain tiles.
 */
@Composable
private fun HeadroomNode(readout: () -> LevelReadout, ceilingDb: Float, g: Float, lit: () -> Float, modifier: Modifier) {
    val s = LocalHomeScale.current
    // Read here, so a new readout recomposes only this.
    val levels = readout()
    val headroomDb = levels.headroomDb(ceilingDb)
    // headroomDb stops at 0; past the ceiling is the louder peak itself going over it.
    val over = !levels.silent && maxOf(levels.leftPeakDb, levels.rightPeakDb) > ceilingDb
    val powered = g >= 0.5f
    val colour = if (over) HomePalette.HeadroomCritical else BmwTheme.colors.sliderHeadroom
    Box(modifier.width(s.dp(ColumnWidth)).fillMaxHeight().clearAndSetSemantics { }) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(s.dp(HeadroomSize))
                .drawBehind {
                    val t = lit()
                    val r = size.minDimension / 2
                    val spread = s.dp(EdgeHalo).toPx()
                    for (step in 1..3) {
                        val o = spread * step / 3f
                        drawCircle(colour.copy(alpha = EdgeHaloAlpha * t * g / step), r + o / 2, style = Stroke(o))
                    }
                    drawCircle(HomePalette.OutFill, r)
                    val rim = s.dp(EdgeWidth).toPx()
                    drawCircle(lerp(Color.White.copy(alpha = 0.85f), colour, t * g), r - rim / 2, style = Stroke(rim))
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = when {
                        !powered -> "—"
                        headroomDb == null -> "--"
                        else -> formatDb(headroomDb)
                    },
                    color = lerp(HomePalette.Dim, Color.White, g),
                    fontSize = s.sp(36f),
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
                if (powered && headroomDb != null) {
                    Text(
                        "dB", color = colour, fontSize = s.sp(15f), fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.alignByBaseline().padding(start = s.dp(2f)),
                    )
                }
            }
        }
    }
}

/** All apps or the car menu, in the top right corner. */
@Composable
private fun CornerButton(x: Float, label: String, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(14f))
    Box(
        Modifier
            .at(x, TopMargin, CornerSize, CornerSize)
            .knobFocusRing(s.dp(14f), grow = true)
            .neonEdge(14f, HomePalette.PanelEdge, glow = 0f)
            .clip(shape)
            .background(HomePalette.Panel)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(s.dp(CornerSize * 0.5f)), onDraw = glyph)
    }
}

/**
 * A car side-on: the body, the cabin on top and two wheels cut out of the body's bottom edge. The
 * car menu, so it no longer shares the gear with the DSP settings.
 */
private fun DrawScope.drawCarGlyph() {
    val w = size.width
    val h = size.height
    val c = HomePalette.Button
    val cabin = Path().apply {
        moveTo(w * 0.24f, h * 0.48f)
        lineTo(w * 0.34f, h * 0.24f)
        lineTo(w * 0.66f, h * 0.24f)
        lineTo(w * 0.78f, h * 0.48f)
        close()
    }
    drawPath(cabin, c)
    val wheel = w * 0.11f
    val wheelY = h * 0.74f
    val wheels = Path().apply {
        addOval(Rect(Offset(w * 0.27f, wheelY), wheel * 1.5f))
        addOval(Rect(Offset(w * 0.73f, wheelY), wheel * 1.5f))
    }
    // The body, with room cut round each wheel so they read apart from it.
    clipPath(wheels, ClipOp.Difference) {
        drawRoundRect(c, Offset(w * 0.04f, h * 0.44f), Size(w * 0.92f, h * 0.3f), CornerRadius(h * 0.1f))
    }
    drawCircle(c, wheel, Offset(w * 0.27f, wheelY))
    drawCircle(c, wheel, Offset(w * 0.73f, wheelY))
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

/** The glyph button at the dock's end (More). */
@Composable
private fun BarButton(label: String, modifier: Modifier, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    Box(
        modifier
            .size(s.dp(DockButtonSize))
            .knobFocusRing(s.dp(12f))
            .clip(RoundedCornerShape(s.dp(12f)))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(s.dp(34f)), onDraw = glyph)
    }
}

/** Three dots across the middle: More. */
private fun DrawScope.drawMoreDots() {
    val r = size.width * 0.085f
    for (fx in listOf(0.2f, 0.5f, 0.8f)) drawCircle(HomePalette.Button, r, Offset(size.width * fx, size.height / 2))
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
private val HomeEdgePadding = 15.dp
// Clear of the head unit's own top banner (back, recents, screen off...), which draws over apps;
// the old front page left the same room.
private const val TopMargin = 48f
// The dock uses the full padded width and keeps a 6 dp bottom inset.
private const val DockScale = 0.7f
private const val DockInset = 6f
private const val DockCorner = 16f
// The chain's own height, in chain units, with the tiles DockTileInset from its top and bottom.
private const val DockUnits = 212f
private const val DockTileInset = 28f
private const val DockChainHeight = DockUnits * DockScale
// The panel's top edge, and the tiles centred in its height.
private const val DockTop = DesignH - DockInset - 4f - DockChainHeight + DockTileInset * DockScale - 3f
private const val DockHeight = DesignH - DockInset - DockTop
private const val DockTileHeight = (DockUnits - 2 * DockTileInset) * DockScale
private const val DockChainTop = DockTop + (DockHeight - DockTileHeight) / 2 - DockTileInset * DockScale
// A soft shade this far above the panel, so it sits into the wallpaper.
private const val DockFade = 50f
private const val DockShadeTop = 0.6f
private const val DockShadeBottom = 0.9f
// More at the dock's right end, past the chain.
private const val DockButtonsWidth = 96f
private const val DockButtonSize = 60f
// The headroom ring at OUT, in chain units: as big as the power button, so the figure reads.
private const val HeadroomSize = 104f
// All apps and the car menu, at the padded right edge.
private const val CornerSize = 58f
// The tiles beside the volume strip: the music tile, then the grid of the other tiles, two
// columns as wide as the music tile.
private const val TileWidth = 165f
private const val TileGap = 19f
private const val GridWidth = HomeTile.Columns * TileWidth + (HomeTile.Columns - 1) * TileGap
private const val MusicWidth = GridWidth
// The music tile's insides: the art square, then the text and the controls under it.
private const val MusicCorner = 16f
private const val MusicPad = 16f
private const val MusicInfoGap = 13f
private const val MediaButtonSize = 48f
private const val MediaButtonGap = 10f
private const val MediaControlsWidth = 3 * MediaButtonSize + 2 * MediaButtonGap
// Slim and against the left edge: a fallback for when the steering-wheel buttons fail.
private const val PodWidth = 64f
private const val PodX = 0f
private const val TilesX = PodX + PodWidth + 20f
private const val TilesTop = 60f
private const val GridX = TilesX + MusicWidth + TileGap
// Down to just above the dock's shade (the page dots used to sit in the gap).
private const val TileHeight = DockTop - 14f - TilesTop
// Two rows of grid tiles in the music tile's height.
private const val GridTileHeight = (TileHeight - TileGap) / 2
// The text and controls' band along the bottom, which the fade darkens; the fade starts at
// MusicFadeStart of the tile's height and is solid from MusicFadeSolid.
private const val MusicBand = 100f
private const val MusicFadeStart = 0.42f
private const val MusicFadeSolid = 0.7f
// The player's icon while there's no art.
private const val MusicIcon = 96f
private const val PanelMs = 380
private const val SweepOnMs = 3000
private const val SweepOffMs = 900
