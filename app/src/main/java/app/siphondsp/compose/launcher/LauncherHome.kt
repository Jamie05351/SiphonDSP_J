package app.siphondsp.compose.launcher

import android.os.Build
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.runtime.key
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
    /** Opens the Android settings page for what the Spotify / Waze swipe still lacks. */
    fun allowAppSwipe()
    fun mediaPlayPause()
    fun mediaNext()
    fun mediaPrevious()
    fun openTrackApp(track: Track)
    /** Switches the now-playing card between its compact and large sizes. */
    fun toggleNowPlayingSize()
    /** Takes the home tile [key] (a [HomeTile.key]) out of the tile row. */
    fun hideTile(key: String)
    /** Puts every hidden home tile back in the tile row. */
    fun showHiddenTiles()
}

/**
 * The front page as the head unit's home screen, laid out in dp at 1280 x 480 like [HomeScreen]
 * (scaled down to fit a smaller screen): the car wallpaper, a slim master volume strip at the far
 * left, the row of home tiles beside it (three showing, swiped through), the app drawer and car
 * menu at the top right, and the DSP dock along the bottom: power, the five DSP tiles, the
 * headroom at OUT, then More and Settings. A dock tile opens its page.
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
    overlay: LauncherOverlay,
    onOverlayChange: (LauncherOverlay) -> Unit,
    openingStage: HomeStage?,
    animateTiles: Boolean,
    actions: LauncherActions,
    modifier: Modifier = Modifier,
    look: LauncherCustomisation.Look = LauncherCustomisation.Look(),
    track: Track? = null,
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
            val k = minOf(1f, maxWidth.value / DesignW, maxHeight.value / DesignH)
            val scale = HomeScale(k)
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
                        Design(Modifier.align(Alignment.Center).graphicsLayer { alpha = homeAlpha }) {
                            VolumePod(
                                engine.masterVolumeDb, engine.masterMuted, g, actions::volumeStep, actions::toggleMute,
                                Modifier.at(PodX, TilesTop, PodWidth, TileHeight),
                            )
                            TileRow(apps, slots, look.hidden, track, onOverlayChange, actions)
                            CornerButton(DrawerX, stringResource(R.string.launcher_drawer), { onOverlayChange(LauncherOverlay.Drawer()) }) { drawDrawerGlyph() }
                            CornerButton(CarX, stringResource(R.string.launcher_car_menu), { onOverlayChange(LauncherOverlay.CarMenu()) }) { drawSettingsGlyph() }
                        }
                    }
                }
                if (dim > 0f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
                }
                Design(Modifier.align(Alignment.Center)) {
                    Dock(engine, powered, g, readout, openingStage, animateTiles, actions, knobEntry)
                }
            }
            // Drawers start under the head unit's top banner and stop above the dock, which stays
            // usable under them.
            val letterbox = ((maxHeight - scale.dp(DesignH)) / 2).coerceAtLeast(0.dp)
            val overlayBottom = scale.dp(DesignH - DockTop + 12f) + letterbox
            val overlayTop = scale.dp(TopMargin) + letterbox
            Box(Modifier.fillMaxSize().onFocusChanged { overlayHasFocus = it.hasFocus }) {
            when (overlay) {
                is LauncherOverlay.Drawer -> AppDrawer(apps, slots, look, overlay.pickFor, scale.dp(OverlayLeft), overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions)
                is LauncherOverlay.CarMenu -> CarMenu(
                    carFunctions, overlay.pickFor, look.wallpaper != null, look.hidden.any { it.startsWith(HomeTile.KeyPrefix) }, nowPlayingAllowed, appSwipeAllowed, scale.dp(OverlayLeft), overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions,
                )
                LauncherOverlay.HiddenApps -> HiddenApps(apps, look, scale.dp(OverlayLeft), overlayTop, overlayBottom, overlayEntry, onOverlayChange, actions)
                LauncherOverlay.None -> {}
            }
            }
        }
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


// --- The tile row ---

/**
 * One row of tiles, [TilesPerPage] showing, beside the volume: every [HomeTile] not in [hidden], in
 * order. Swiping moves a tile at a time and wraps round from the last to the first; the dots under
 * it say which tile is at the left. The row keeps its own swipes: the swipe to the DSP settings page
 * works everywhere else on the home screen.
 */
@Composable
private fun TileRow(
    apps: List<AppEntry>?,
    slots: List<SlotTarget>,
    hidden: Set<String>,
    track: Track?,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    val s = LocalHomeScale.current
    val view = LocalView.current
    val tiles = HomeTile.entries.filter { it.key !in hidden }
    if (tiles.isEmpty()) return
    // Many copies of the row, starting in the middle, so swiping either way never runs out. Started
    // again when a tile is hidden or shown, so every page still maps to the same tile and dot.
    val pager = key(tiles.size) { rememberPagerState(initialPage = tiles.size * (LoopCopies / 2)) { tiles.size * LoopCopies } }
    HorizontalPager(
        pager,
        Modifier
            .at(TilesX, TilesTop, TilesWidth, TileHeight)
            // A touch on the row is the row's: the pager round the home screen mustn't take it
            // as the swipe to the settings page.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
            },
        pageSize = PageSize.Fixed(s.dp(TileWidth)),
        pageSpacing = s.dp(TileGap),
        beyondViewportPageCount = TilesPerPage,
    ) { page ->
        Tile(tiles[page % tiles.size], slots, apps, track, onOverlayChange, actions)
    }
    val current = pager.currentPage % tiles.size
    Row(
        Modifier.at(TilesX, DotsTop, TilesWidth, DotSize),
        horizontalArrangement = Arrangement.spacedBy(s.dp(8f), Alignment.CenterHorizontally),
    ) {
        for (i in tiles.indices) {
            Box(
                Modifier
                    .width(s.dp(if (i == current) DotSize * 2.5f else DotSize))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (i == current) BmwTheme.colors.sliderHeadroom else HomePalette.Idle),
            )
        }
    }
}

/**
 * [tile], opening the app or car function kept in its slot. The music tile shows what's playing,
 * with the album art and the media controls. Hold for the slot's menu.
 */
@Composable
private fun Tile(
    tile: HomeTile,
    slots: List<SlotTarget>,
    apps: List<AppEntry>?,
    track: Track?,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    val i = tile.ordinal
    var menu by remember { mutableStateOf(false) }
    val target = slots.getOrElse(i) { SlotTarget.Empty }
    val app = (target as? SlotTarget.App)?.let { t -> apps?.firstOrNull { it.component == t.component } }
    val open = {
        when (target) {
            SlotTarget.Empty -> if (tile == HomeTile.DSP) actions.dspSettings() else onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
            is SlotTarget.Car -> actions.runCar(target.function)
            is SlotTarget.App -> if (apps != null) {
                app?.let(actions::launchApp) ?: onOverlayChange(LauncherOverlay.Drawer(pickFor = i))
            }
        }
    }
    val attached = when (target) {
        is SlotTarget.App -> app?.label ?: target.component.packageName
        is SlotTarget.Car -> stringResource(target.function.label)
        SlotTarget.Empty -> stringResource(if (tile == HomeTile.DSP) R.string.launcher_dsp_settings else R.string.launcher_tile_choose)
    }
    Box(Modifier.fillMaxSize()) {
        // Whatever is playing shows on the music tile, from any player; tapping it opens that player.
        val playing = track?.takeIf { tile == HomeTile.MUSIC }
        if (playing != null) {
            HomeTileFace(
                tile, listOf(playing.title, playing.artist).filter { it.isNotEmpty() }.joinToString(" · "), { actions.openTrackApp(playing) }, { menu = true },
                art = playing.art,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MediaButton(stringResource(R.string.launcher_media_previous), actions::mediaPrevious) { drawSkip(forward = false) }
                    MediaButton(
                        stringResource(if (playing.playing) R.string.launcher_media_pause else R.string.launcher_media_play), actions::mediaPlayPause,
                    ) { if (playing.playing) drawPause() else drawPlay() }
                    MediaButton(stringResource(R.string.launcher_media_next), actions::mediaNext) { drawSkip(forward = true) }
                }
            }
        } else {
            HomeTileFace(tile, attached, open, { menu = true })
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem(R.string.launcher_slot_choose_app) { menu = false; onOverlayChange(LauncherOverlay.Drawer(pickFor = i)) }
            MenuItem(R.string.launcher_slot_choose_car) { menu = false; onOverlayChange(LauncherOverlay.CarMenu(pickFor = i)) }
            if (target != SlotTarget.Empty) {
                MenuItem(R.string.launcher_slot_clear) { menu = false; actions.assignSlot(i, SlotTarget.Empty) }
            }
            MenuItem(R.string.launcher_tile_hide) { menu = false; actions.hideTile(tile.key) }
        }
    }
}

@Composable
private fun MediaButton(label: String, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    val s = LocalHomeScale.current
    Box(
        Modifier
            .size(s.dp(44f), s.dp(36f))
            .knobFocusRing(s.dp(10f))
            .clip(RoundedCornerShape(s.dp(10f)))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(s.dp(18f)), onDraw = glyph)
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

// --- The dock ---

/**
 * The DSP along the bottom of the home screen, a panel [DockInset] in from the sides and bottom:
 * the front page's own chain (power, the five tiles and the headroom at OUT) at [DockScale] of its
 * size, then More and Settings. A tile opens its stage's page; power works straight from the dock.
 * The panel's edge is lit purple while the DSP is on.
 */
@Composable
private fun Dock(
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
            .at(0f, DockTop - DockFade, DesignW, DockFade)
            .background(Brush.verticalGradient(listOf(Color.Transparent, HomePalette.Panel.copy(alpha = 0.35f)))),
    )
    // The panel: its edge solid purple all the way round while on, as the chain tiles' edges are.
    Box(
        Modifier
            .at(DockInset, DockTop, DesignW - 2 * DockInset, DockHeight)
            .neonEdge(DockCorner, lerp(HomePalette.PanelEdge, signal, g), glow = g)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(HomePalette.Panel.copy(alpha = DockShadeTop), HomePalette.Panel.copy(alpha = DockShadeBottom)),
                ),
            ),
    )
    // The chain over the panel, unclipped so the tiles' halos show in full.
    Box(Modifier.at(DockInset, DockChainTop, DesignW - 2 * DockInset, DockChainHeight)) {
        Box(Modifier.size(s.dp(DockChainWidth), s.dp(DockChainHeight))) {
            CompositionLocalProvider(LocalHomeScale provides HomeScale(s.k * DockScale)) {
                val c = LocalHomeScale.current
                val layout = ChainLayout(DockChainWidth / DockScale, maxCardWidth = CardWidth)
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
                .offset(x = s.dp(DockChainWidth), y = s.dp(DockTop - DockChainTop))
                .height(s.dp(DockHeight))
                .padding(vertical = s.dp(20f))
                .width(s.dp(2f))
                .background(HomePalette.PanelEdge),
        )
        Column(
            Modifier.offset(x = s.dp(DockChainWidth + 2f), y = s.dp(DockTop - DockChainTop)).width(s.dp(DockButtonsWidth - 2f)).height(s.dp(DockHeight)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(s.dp(8f), Alignment.CenterVertically),
        ) {
            BarButton(stringResource(R.string.launcher_more), Modifier.onGloballyPositioned { moreBounds = it.boundsInRoot() }, { actions.more(moreBounds) }) { drawMoreDots() }
            BarButton(stringResource(R.string.launcher_dsp_settings), Modifier, actions::dspSettings) { drawSettingsGlyph() }
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

/** A glyph button at the dock's end (More, Settings). */
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
private const val Margin = 14f
// Clear of the head unit's own top banner (back, recents, screen off...), which draws over apps;
// the old front page left the same room.
private const val TopMargin = 48f
private const val OverlayLeft = Margin
// The dock: a panel 6 dp in from the sides and bottom, holding the front page's chain at DockScale.
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
// More and Settings at the dock's right end, past the chain.
private const val DockButtonsWidth = 96f
private const val DockChainWidth = DesignW - 2 * DockInset - DockButtonsWidth
private const val DockButtonSize = 60f
// The headroom ring at OUT, in chain units: as big as the power button, so the figure reads.
private const val HeadroomSize = 104f
// All apps and the car menu, in the top right corner as before.
private const val CornerSize = 58f
private const val CarX = DesignW - Margin - CornerSize
private const val DrawerX = CarX - 12f - CornerSize
// The tile row beside the volume strip, three tiles showing.
private const val TilesPerPage = 3
private const val TileWidth = 165f
private const val TileGap = 19f
private const val TilesWidth = TilesPerPage * TileWidth + (TilesPerPage - 1) * TileGap
// Slim and against the left edge: a fallback for when the steering-wheel buttons fail.
private const val PodWidth = 64f
private const val PodX = 20f
private const val TilesX = PodX + PodWidth + 20f
private const val TilesTop = 60f
private const val DotSize = 8f
private const val DotsTop = DockTop - 14f - DotSize
private const val TileHeight = DotsTop - 10f - TilesTop
private const val LoopCopies = 1000
private const val PanelMs = 380
private const val SweepOnMs = 3000
private const val SweepOffMs = 900
