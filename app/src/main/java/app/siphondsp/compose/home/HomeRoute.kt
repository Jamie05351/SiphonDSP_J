package app.siphondsp.compose.home

import android.animation.ValueAnimator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.R
import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.compose.nav.Routes
import app.siphondsp.model.MasterVolume
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.utils.extensions.ContextExtensions.restoreDspSettings
import app.siphondsp.view.DspDestination
import app.siphondsp.view.DspRail
import app.siphondsp.view.HomeLevelFeed
import app.siphondsp.view.LevelReadout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The front page as a navigation destination (was DspFragment): [HomeScreen] with its live
 * state, the tile launch (glow flash, then the page morphing into the workspace) and the
 * overflow menu.
 *
 * @param powered the engine's real power state, owned by MainActivity.
 */
@Composable
fun HomeRoute(powered: Boolean, onTogglePower: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()

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

    // The level feed (and so the analyser) and the tiles' loops run only while the page is resumed.
    val levelFeed = remember { HomeLevelFeed(onFrame = { levelFrame = it }, onReadout = { levelReadout = it }) }
    LifecycleResumeEffect(levelFeed) {
        // Back from a workspace: the glow and morph are done with.
        openingTile = null
        scope.launch { morph.snapTo(0f) }
        engineState = HomeEngineState.from(NativeBmwDspValues.load(context))
        levelFeed.running = true
        live = true
        onPauseOrDispose {
            // A launch still waiting out its flash must not open over wherever the user went.
            if (pendingOpen?.isActive == true) {
                pendingOpen?.cancel()
                openingTile = null
                scope.launch { morph.snapTo(0f) }
            }
            pendingOpen = null
            levelFeed.running = false
            live = false
        }
    }

    fun openStage(stage: HomeStage) {
        if (openingTile != null) return
        openingTile = stage
        pendingOpen = scope.launch {
            // The tap reads as confirmed, then the page turns into the workspace, which opens
            // over that last frame (it already looks the same). Instant with animations off.
            if (ValueAnimator.areAnimatorsEnabled()) {
                delay(TILE_FLASH_MS)
                morph.animateTo(1f, tween(SidebarMorphMs.toInt(), easing = LinearEasing))
            }
            navigator.openWorkspace(stage.destination())
        }
    }

    Box(Modifier.fillMaxSize()) {
        HomeScreen(
            engine = engineState,
            powered = powered,
            readout = { levelReadout },
            meterLevels = { levelFrame },
            openingStage = openingTile,
            onTogglePower = { if (openingTile == null) onTogglePower() },
            onOpenStage = { stage, _ -> openStage(stage) },
            onOpenGlobal = { stage ->
                if (openingTile == null) {
                    when (stage) {
                        GlobalStage.TILT -> navigator.openWorkspace(DspDestination.CROSSOVER_TILT)
                        GlobalStage.MBC -> navigator.openWorkspace(DspDestination.DYNAMICS)
                        // The master limiter lives on Output; ALLPASS opens Alignment on its page.
                        GlobalStage.LIMITER -> navigator.openWorkspace(DspDestination.OUTPUT)
                        GlobalStage.ALLPASS -> navigator.openWorkspace(DspDestination.ALIGNMENT, Routes.ALIGNMENT_PAGE_ALLPASS)
                    }
                }
            },
            onSettings = { if (openingTile == null) navigator.open(Routes.SETTINGS) },
            onMore = { bounds -> if (openingTile == null) moreAnchor = bounds },
            // The DSP master volume: the steering-wheel buttons' path, without their toast.
            onVolumeStep = { steps -> MasterVolume.step(context, steps) },
            onToggleMute = { MasterVolume.setMuted(context, null) },
            morphProgress = { morph.value },
            sidebarWidth = DspRail.sidebarWidthDp(context).dp,
            animateTiles = live,
        )

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
