package app.siphondsp.compose.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.R
import app.siphondsp.compose.launcher.HomeTile
import app.siphondsp.compose.launcher.LauncherActions
import app.siphondsp.compose.launcher.LauncherHome
import app.siphondsp.compose.launcher.LauncherHost
import app.siphondsp.compose.launcher.LauncherOverlay
import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.compose.nav.Routes
import app.siphondsp.launcher.AppEntry
import app.siphondsp.launcher.AppSwipe
import app.siphondsp.launcher.CarFunction
import app.siphondsp.launcher.HomeSlots
import app.siphondsp.launcher.InstalledApps
import app.siphondsp.launcher.Launch
import app.siphondsp.launcher.LauncherCustomisation
import app.siphondsp.launcher.NowPlaying
import app.siphondsp.launcher.SlotTarget
import app.siphondsp.launcher.Track
import app.siphondsp.model.MasterVolume
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.utils.extensions.ContextExtensions.restoreDspSettings
import app.siphondsp.view.DspDestination
import app.siphondsp.view.DspRail
import app.siphondsp.view.HomeLevelFeed
import app.siphondsp.view.LevelReadout
import app.siphondsp.view.isHeadUnitDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The front page as a navigation destination (was DspFragment). On the head unit it is the
 * launcher home screen ([LauncherHome]: the DSP chain, the app tiles, now playing, the drawer and
 * car menu); on a phone, the DSP front page ([HomeScreen]) with the tiles folding into the sidebar.
 * Both share the live state, the tile launch (glow flash, then the workspace) and the overflow menu.
 *
 * @param powered the engine's real power state, owned by MainActivity.
 * @param launcherHost how MainActivity's knob and Home button reach the launcher home screen.
 */
@Composable
fun HomeRoute(powered: Boolean, onTogglePower: () -> Unit, launcherHost: LauncherHost? = null) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()

    // The launcher is laid out for the head unit's 1280x480 display only.
    val launcher = remember(context) { context.isHeadUnitDisplay() }

    var engineState by remember { mutableStateOf(HomeEngineState.from(NativeBmwDspValues.load(context))) }
    var levelReadout by remember { mutableStateOf(LevelReadout.SILENT) }
    var levelFrame by remember { mutableStateOf(LevelReadout.SILENT) }
    var live by remember { mutableStateOf(false) }

    // The tile being opened: lit, and every other tap ignored until the workspace covers the page.
    var openingTile by remember { mutableStateOf<HomeStage?>(null) }
    val morph = remember { Animatable(0f) }
    var pendingOpen by remember { mutableStateOf<Job?>(null) }

    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    var confirmRevert by remember { mutableStateOf(false) }

    // Launcher state (head unit).
    var overlay by remember { mutableStateOf<LauncherOverlay>(LauncherOverlay.None) }
    var appSwipeAllowed by remember { mutableStateOf(true) }
    var knobFocusRequests by remember { mutableIntStateOf(0) }
    var knobFocusFailed by remember { mutableStateOf(false) }
    var homeHasFocus by remember { mutableStateOf(false) }
    // The icon key (SlotTarget.encode) whose image is being picked; kept across recreation.
    var iconKeyPicking by rememberSaveable { mutableStateOf<String?>(null) }
    val nowPlaying = remember(context) { NowPlaying(context.applicationContext) }

    // Settings changed underneath the page (an edit, a preset or backup loaded, a revert).
    LaunchedEffect(context) {
        AppEvents.flow.collect { event ->
            val values = when (event) {
                is AppEvent.NativeBmwDspUpdated -> event.values.takeIf { it.size == NativeBmwDspValues.SIZE }
                AppEvent.PresetLoaded, AppEvent.BackupRestored -> null
                else -> return@collect
            } ?: NativeBmwDspValues.load(context)
            engineState = HomeEngineState.from(values)
        }
    }

    fun cancelPendingOpen() {
        if (pendingOpen?.isActive == true) {
            pendingOpen?.cancel()
            openingTile = null
            scope.launch { morph.snapTo(0f) }
        }
        pendingOpen = null
    }

    // The level feed (and so the analyser) and the tiles' loops run only while the page is resumed.
    val levelFeed = remember { HomeLevelFeed(onFrame = { levelFrame = it }, onReadout = { levelReadout = it }) }
    LifecycleResumeEffect(levelFeed) {
        // Back from a workspace: the glow and morph are done with.
        openingTile = null
        scope.launch { morph.snapTo(0f) }
        engineState = HomeEngineState.from(NativeBmwDspValues.load(context))
        levelFeed.running = true
        live = true
        if (launcher) {
            // Home again: the Spotify / Waze swipe is only for moving between those apps.
            AppSwipe.stop(context)
            // Without both apps installed, or with nothing J_DSP can open to grant, the car menu offers nothing.
            appSwipeAllowed = !AppSwipe.installed(context) || AppSwipe.allowed(context) || AppSwipe.settingsToAllow(context) == null
            nowPlaying.start()
            // The knob and Home button reach this screen only while it is the one in front.
            launcherHost?.knobFocus = knobFocus@{
                if (homeHasFocus) return@knobFocus false
                // If the last request couldn't land, don't keep swallowing keys: this one goes
                // through normally and the one after tries again.
                if (knobFocusFailed) {
                    knobFocusFailed = false
                    return@knobFocus false
                }
                knobFocusRequests++
                true
            }
            launcherHost?.homePressed = {
                cancelPendingOpen()
                overlay = LauncherOverlay.None
            }
        }
        onPauseOrDispose {
            // A launch still waiting out its flash must not open over wherever the user went.
            cancelPendingOpen()
            levelFeed.running = false
            live = false
            if (launcher) {
                launcherHost?.knobFocus = null
                launcherHost?.homePressed = null
                nowPlaying.stop()
                // Spotify or Waze may be what comes up; see AppSwipe.
                AppSwipe.start(context)
            }
        }
    }

    fun openStage(stage: HomeStage) {
        if (openingTile != null) return
        openingTile = stage
        pendingOpen = scope.launch {
            // The tap reads as confirmed, then (on a phone) the page turns into the workspace,
            // which opens over that last frame (it already looks the same). Instant with
            // animations off.
            if (ValueAnimator.areAnimatorsEnabled()) {
                delay(TILE_FLASH_MS)
                if (!launcher) morph.animateTo(1f, tween(SidebarMorphMs.toInt(), easing = LinearEasing))
            }
            navigator.openWorkspace(stage.destination())
        }
    }

    fun openGlobal(stage: GlobalStage) {
        if (openingTile != null) return
        when (stage) {
            GlobalStage.TILT -> navigator.openWorkspace(DspDestination.CROSSOVER_TILT)
            GlobalStage.MBC -> navigator.openWorkspace(DspDestination.DYNAMICS)
            // The master limiter lives on Output; ALLPASS opens Alignment on its page.
            GlobalStage.LIMITER -> navigator.openWorkspace(DspDestination.OUTPUT)
            GlobalStage.ALLPASS -> navigator.openWorkspace(DspDestination.ALIGNMENT, Routes.ALIGNMENT_PAGE_ALLPASS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (launcher) {
            LauncherRoute(
                engine = engineState,
                powered = powered,
                readout = { levelReadout },
                meterLevels = { levelFrame },
                openingStage = openingTile,
                animateTiles = live,
                overlay = overlay,
                onOverlayChange = { overlay = it },
                nowPlaying = nowPlaying,
                appSwipeAllowed = appSwipeAllowed,
                knobFocusRequests = knobFocusRequests,
                onKnobFocusResult = { taken -> knobFocusFailed = !taken },
                onHasFocusChanged = { homeHasFocus = it },
                iconKeyPicking = iconKeyPicking,
                onIconKeyPicking = { iconKeyPicking = it },
                onTogglePower = { if (openingTile == null) onTogglePower() },
                onOpenStage = ::openStage,
                onOpenGlobal = ::openGlobal,
                onSettings = { if (openingTile == null) navigator.open(Routes.SETTINGS) },
                onMore = { bounds -> if (openingTile == null) moreAnchor = bounds },
            )
        } else {
            HomeScreen(
                engine = engineState,
                powered = powered,
                readout = { levelReadout },
                meterLevels = { levelFrame },
                openingStage = openingTile,
                onTogglePower = { if (openingTile == null) onTogglePower() },
                onOpenStage = { stage, _ -> openStage(stage) },
                onOpenGlobal = ::openGlobal,
                onSettings = { if (openingTile == null) navigator.open(Routes.SETTINGS) },
                onMore = { bounds -> if (openingTile == null) moreAnchor = bounds },
                // The DSP master volume: the steering-wheel buttons' path, without their toast.
                onVolumeStep = { steps -> MasterVolume.step(context, steps) },
                onToggleMute = { MasterVolume.setMuted(context, null) },
                morphProgress = { morph.value },
                sidebarWidth = DspRail.sidebarWidthDp(context).dp,
                animateTiles = live,
            )
        }

        moreAnchor?.let { bounds ->
            val density = LocalDensity.current
            Box(Modifier.offset(with(density) { bounds.left.toDp() }, with(density) { bounds.bottom.toDp() })) {
                DropdownMenu(expanded = true, onDismissRequest = { moreAnchor = null }) {
                    MoreItem(R.string.action_revert) { moreAnchor = null; confirmRevert = true }
                    MoreItem(R.string.action_blocked_apps) {
                        moreAnchor = null
                        navigator.open(Routes.BLOCKLIST)
                    }
                    MoreItem(R.string.action_measurement) {
                        moreAnchor = null
                        navigator.open(Routes.MEASUREMENT_CAPTURE)
                    }
                    MoreItem(R.string.action_signal_generator) { moreAnchor = null; navigator.open(Routes.SIGNAL_GENERATOR) }
                    MoreItem(R.string.action_native_truth) { moreAnchor = null; navigator.open(Routes.NATIVE_TRUTH) }
                }
            }
        }
    }

    // Back closes a drawer or menu; on the plain home screen it does nothing while J_DSP is the home
    // app (there is nowhere to go back to). Opened from another launcher, it closes as before.
    if (launcher) {
        val activity = context as? Activity
        val isHome = remember(activity?.intent) { activity?.let(::isHomeApp) ?: false }
        BackHandler(enabled = overlay != LauncherOverlay.None || isHome) {
            if (overlay != LauncherOverlay.None) overlay = LauncherOverlay.None
        }
    }

    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text(stringResource(R.string.revert_confirmation_title)) },
            text = { Text(stringResource(R.string.revert_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevert = false
                    context.restoreDspSettings()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmRevert = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/**
 * The head unit's launcher home screen and everything it opens: apps, the car's own screens, the
 * icon / wallpaper / layout pickers, now playing. Moved here unchanged from head-unit's DspFragment.
 */
@Composable
private fun LauncherRoute(
    engine: HomeEngineState,
    powered: Boolean,
    readout: () -> LevelReadout,
    meterLevels: () -> LevelReadout,
    openingStage: HomeStage?,
    animateTiles: Boolean,
    overlay: LauncherOverlay,
    onOverlayChange: (LauncherOverlay) -> Unit,
    nowPlaying: NowPlaying,
    appSwipeAllowed: Boolean,
    knobFocusRequests: Int,
    onKnobFocusResult: (Boolean) -> Unit,
    onHasFocusChanged: (Boolean) -> Unit,
    iconKeyPicking: String?,
    onIconKeyPicking: (String?) -> Unit,
    onTogglePower: () -> Unit,
    onOpenStage: (HomeStage) -> Unit,
    onOpenGlobal: (GlobalStage) -> Unit,
    onSettings: () -> Unit,
    onMore: (Rect) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val slots = remember(app) { HomeSlots.get(app) }
    val installed = remember(app) { InstalledApps.get(app) }
    val customisation = remember(app) { LauncherCustomisation.get(app) }

    // Messages go through the app context: the home screen can be rebuilt while a picker is open.
    val pickIconImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = iconKeyPicking
        onIconKeyPicking(null)
        when {
            uri == null -> {}
            key == null -> Toast.makeText(app, R.string.launcher_icon_lost, Toast.LENGTH_LONG).show()
            else -> customisation.setIcon(key, uri) { failure ->
                val message = if (failure == null) app.getString(R.string.launcher_icon_done) else app.getString(R.string.launcher_icon_failed, failure)
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val pickWallpaperImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            customisation.setWallpaper(uri) { failure ->
                if (failure != null) Toast.makeText(app, app.getString(R.string.launcher_wallpaper_failed, failure), Toast.LENGTH_LONG).show()
            }
        }
    }
    val saveLayout = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            customisation.export(slots.slots.value, uri) { ok ->
                Toast.makeText(app, if (ok) R.string.launcher_backup_done else R.string.launcher_backup_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val openLayout = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            customisation.import(uri, slots::setAll) { ok ->
                Toast.makeText(app, if (ok) R.string.launcher_restore_done else R.string.launcher_restore_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Opens a system file picker; a unit without one gets a message instead of a crash. */
    fun launchPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No document picker")
            Toast.makeText(app, R.string.launcher_open_missing, Toast.LENGTH_SHORT).show()
        }
    }

    val actions = remember(context, nowPlaying) {
        object : LauncherActions {
            override fun togglePower() = onTogglePower()
            override fun openStage(stage: HomeStage) = onOpenStage(stage)
            override fun openGlobal(stage: GlobalStage) = onOpenGlobal(stage)
            override fun dspSettings() = onSettings()
            override fun more(bounds: Rect) = onMore(bounds)
            // The DSP master volume: same path as the steering-wheel buttons, without the toast (the
            // level is on screen); the saved value comes back through the DSP-values event.
            override fun volumeStep(steps: Int) { MasterVolume.step(context, steps) }
            override fun toggleMute() { MasterVolume.setMuted(context, null) }
            override fun launchApp(app: AppEntry) { Launch.component(context, app.component) }
            override fun runCar(function: CarFunction) { function.run(context) }
            override fun assignSlot(index: Int, target: SlotTarget) = slots.set(index, target)
            override fun swapSlots(a: Int, b: Int) = slots.swap(a, b)
            override fun appInfo(app: AppEntry) {
                val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + app.component.packageName).toUri())
                Launch.start(context, details)
            }
            override fun androidSettings() { Launch.start(context, Intent(Settings.ACTION_SETTINGS)) }
            override fun homeAppSettings() { Launch.start(context, Intent(Settings.ACTION_HOME_SETTINGS)) }
            override fun homeFull() { Toast.makeText(context, R.string.launcher_home_full, Toast.LENGTH_LONG).show() }
            override fun pickIcon(key: String) {
                onIconKeyPicking(key)
                launchPicker { pickIconImage.launch(arrayOf("image/*")) }
            }
            override fun resetIcon(key: String) { customisation.clearIcon(key) }
            override fun setHidden(app: AppEntry, hidden: Boolean) {
                customisation.setHidden(SlotTarget.App(app.component).encode(), hidden)
            }
            override fun pickWallpaper() = launchPicker { pickWallpaperImage.launch(arrayOf("image/*")) }
            override fun resetWallpaper() { customisation.clearWallpaper() }
            override fun backUpLayout() = launchPicker { saveLayout.launch("j_dsp-home-layout.json") }
            override fun restoreLayout() = launchPicker { openLayout.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            override fun allowNowPlaying() { Launch.start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            override fun allowAppSwipe() { AppSwipe.settingsToAllow(context)?.let { Launch.start(context, it) } }
            override fun mediaPlayPause() = nowPlaying.playPause()
            override fun mediaNext() = nowPlaying.next()
            override fun mediaPrevious() = nowPlaying.previous()
            override fun toggleNowPlayingSize() = customisation.toggleNowPlayingSize()
            override fun hideTile(key: String) = customisation.setHidden(key, true)
            override fun showHiddenTiles() {
                customisation.look.value.hidden.filter { it.startsWith(HomeTile.KeyPrefix) }.forEach { customisation.setHidden(it, false) }
            }
            override fun openTrackApp(track: Track) {
                context.packageManager.getLaunchIntentForPackage(track.packageName)?.let { Launch.start(context, it) }
            }
        }
    }

    val apps by installed.apps.collectAsState()
    val slotList by slots.slots.collectAsState()
    val look by customisation.look.collectAsState()
    val track by nowPlaying.track.collectAsState()
    val nowPlayingAllowed by nowPlaying.allowed.collectAsState()
    LaunchedEffect(apps) { apps?.let(slots::fillDefaults) }
    val carFunctions by produceState(emptyList<CarFunction>(), apps) {
        repeat(CAR_CHECK_TRIES) { attempt ->
            value = withContext(Dispatchers.IO) { CarFunction.entries.filter { it.isAvailable(app) } }
            // The commands need wits_pms, which can start after the home screen at boot.
            if (value.any { it.command != null } || attempt == CAR_CHECK_TRIES - 1) return@produceState
            delay(CAR_CHECK_RETRY_MS)
        }
    }
    LauncherHome(
        engine = engine,
        powered = powered,
        readout = readout,
        meterLevels = meterLevels,
        apps = apps,
        slots = slotList,
        carFunctions = carFunctions,
        overlay = overlay,
        onOverlayChange = onOverlayChange,
        openingStage = openingStage,
        animateTiles = animateTiles,
        actions = actions,
        look = look,
        track = track,
        nowPlayingAllowed = nowPlayingAllowed,
        appSwipeAllowed = appSwipeAllowed,
        knobFocusRequests = knobFocusRequests,
        onKnobFocusResult = onKnobFocusResult,
        onHasFocusChanged = onHasFocusChanged,
    )
}

/**
 * Whether this is the home screen: Android opened J_DSP as Home (the latest intent is a HOME one;
 * MainActivity keeps it current through onNewIntent), or J_DSP is the default home app.
 */
private fun isHomeApp(activity: Activity): Boolean {
    if (activity.intent?.hasCategory(Intent.CATEGORY_HOME) == true) return true
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val current = activity.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
    return current?.activityInfo?.packageName == activity.packageName
}

@Composable
private fun MoreItem(title: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(title)) }, onClick = onClick)
}

private fun HomeStage.destination() = when (this) {
    HomeStage.PEQ -> DspDestination.PARAMETRIC_EQ
    HomeStage.ALIGNMENT -> DspDestination.ALIGNMENT
    HomeStage.XOVERS -> DspDestination.CROSSOVER_TILT
    HomeStage.DYNAMICS -> DspDestination.DYNAMICS
    HomeStage.OUTPUT -> DspDestination.OUTPUT
}

private const val TILE_FLASH_MS = 80L
private const val CAR_CHECK_TRIES = 6
private const val CAR_CHECK_RETRY_MS = 5_000L
