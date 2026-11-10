package app.siphondsp.compose.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.siphondsp.R
import app.siphondsp.compose.controls.ArtPagerFinder
import app.siphondsp.compose.controls.DspPagerArrows
import app.siphondsp.compose.controls.WorkspaceArt
import app.siphondsp.compose.screens.AllPassPage
import app.siphondsp.compose.screens.BandAlignScreen
import app.siphondsp.compose.screens.CompressorBandPage
import app.siphondsp.compose.screens.CompressorDriverPage
import app.siphondsp.compose.screens.CompressorVisualiserPage
import app.siphondsp.compose.screens.CrossoverGraphMode
import app.siphondsp.compose.screens.CrossoverLowMidPage
import app.siphondsp.compose.screens.CrossoverMidHighPage
import app.siphondsp.compose.screens.CrossoverTiltPage
import app.siphondsp.compose.screens.CrossoverVirtualBassPage
import app.siphondsp.compose.screens.GainsDelayScreen
import app.siphondsp.compose.screens.HeadroomOutputScreen
import app.siphondsp.compose.screens.NativeTruthScreen
import app.siphondsp.compose.screens.ParametricEqScreen
import app.siphondsp.compose.screens.PeqToolbarActions
import app.siphondsp.compose.screens.SignalGeneratorScreen
import app.siphondsp.compose.screens.SpeakerAlignScreen
import app.siphondsp.compose.screens.VirtualCentreScreen
import app.siphondsp.compose.settings.AppScreen
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.view.DspDestination
import app.siphondsp.view.isHeadUnitDisplay
import kotlinx.coroutines.launch

/** The app's in-activity screens. The DSP workspaces' routes are [DspDestination.route]. */
object Routes {
    const val HOME = "home"
    const val BAND_ALIGN = "band-align"
    const val SIGNAL_GENERATOR = "signal-generator"
    const val NATIVE_TRUTH = "native-truth"
    const val SETTINGS = "settings"
    const val BLOCKLIST = "blocklist"
    const val MEASUREMENT_CAPTURE = "measurement-capture"
    const val APP_COMPATIBILITY = "app-compatibility"

    internal const val ONBOARDING = "onboarding"
    internal const val ARG_FIX_PERMS = "fixPerms"
    internal const val ARG_REDO_ADB_SETUP = "redoAdbSetup"

    /** The setup wizard; [redoAdbSetup] runs only the method choice and the ADB/Shizuku steps. */
    fun onboarding(fixPerms: Boolean = false, redoAdbSetup: Boolean = false) =
        "$ONBOARDING?$ARG_FIX_PERMS=$fixPerms&$ARG_REDO_ADB_SETUP=$redoAdbSetup"

    /** Alignment's pages, a finder segment each. */
    const val ALIGNMENT_PAGE_COUNT = 4
    const val ALIGNMENT_PAGE_ALLPASS = 2

    /** Dynamics: the visualiser, one page per MBC band, then the bus limiters. */
    val DYNAMICS_PAGE_COUNT = 2 + NativeBmwDspValues.MBC_BAND_COUNT

    const val CROSSOVER_PAGE_COUNT = 4

    internal const val ARG_PAGE = "page"

    /** The Alignment workspace, opened on [page] (one of its pages, e.g. [ALIGNMENT_PAGE_ALLPASS]). */
    fun alignment(page: Int = 0) = "${DspDestination.ALIGNMENT.route}?$ARG_PAGE=$page"
}

/** Moves between the app's screens; provided to every screen by [AppNavHost]. */
interface AppNavigator {
    fun open(route: String)

    /** A DSP workspace, switching from the current one if there is one (the rail's behaviour). */
    fun openWorkspace(destination: DspDestination, page: Int = 0)

    fun back()
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> { error("No AppNavigator provided") }

private class NavControllerNavigator(private val nav: NavHostController, private val onExit: () -> Unit) : AppNavigator {
    override fun open(route: String) {
        nav.navigate(route) { launchSingleTop = true }
    }

    override fun openWorkspace(destination: DspDestination, page: Int) {
        val route = if (destination == DspDestination.ALIGNMENT) Routes.alignment(page) else destination.route
        nav.navigate(route) {
            // Workspace to workspace is a swap, not a stack: back always returns to the front page.
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
    }

    override fun back() {
        if (!nav.popBackStack()) onExit()
    }
}

/**
 * The one navigation graph: the front page and every DSP workspace. Screen changes don't animate:
 * a workspace opens over the front page's own morph into it, and the rail swaps workspaces
 * cleanly (swiping is reserved for paging within one).
 */
@Composable
fun AppNavHost(nav: NavHostController, onExit: () -> Unit, home: @Composable () -> Unit) {
    val navigator = remember(nav) { NavControllerNavigator(nav, onExit) }
    CompositionLocalProvider(LocalAppNavigator provides navigator) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.HOME) { home() }
            composable(DspDestination.PARAMETRIC_EQ.route) { PeqWorkspace() }
            composable(
                route = "${DspDestination.ALIGNMENT.route}?${Routes.ARG_PAGE}={${Routes.ARG_PAGE}}",
                arguments = listOf(navArgument(Routes.ARG_PAGE) { type = NavType.IntType; defaultValue = 0 }),
            ) { entry ->
                AlignmentWorkspace(startPage = entry.arguments?.getInt(Routes.ARG_PAGE) ?: 0)
            }
            composable(DspDestination.CROSSOVER_TILT.route) { CrossoverWorkspace() }
            composable(DspDestination.DYNAMICS.route) { DynamicsWorkspace() }
            composable(DspDestination.OUTPUT.route) { OutputWorkspace() }
            composable(Routes.BAND_ALIGN) { BandAlignScreen() }
            composable(Routes.SIGNAL_GENERATOR) {
                AppScreen(title = stringResource(R.string.title_activity_signal_generator), onBack = navigator::back) { SignalGeneratorScreen() }
            }
            composable(Routes.NATIVE_TRUTH) {
                AppScreen(title = stringResource(R.string.title_activity_native_truth), onBack = navigator::back) { NativeTruthScreen() }
            }
        }
    }
}

@Composable
private fun Workspace(
    current: DspDestination,
    showStatusStrip: Boolean = true,
    toolbarActions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val navigator = LocalAppNavigator.current
    DspWorkspaceScaffold(
        current = current,
        onNavigate = { navigator.openWorkspace(it) },
        onBack = navigator::back,
        showStatusStrip = showStatusStrip,
        toolbarActions = toolbarActions,
        content = content,
    )
}

/** The page finder on the head unit, prev/next arrows on a phone. */
@Composable
private fun PagerControls(pagerState: PagerState, labels: List<String>, frac: WorkspaceArt.Frac) {
    if (LocalContext.current.isHeadUnitDisplay()) ArtPagerFinder(pagerState, labels, frac) else DspPagerArrows(pagerState)
}

@Composable
private fun PeqWorkspace() {
    val context = LocalContext.current
    // One holder for the toolbar's scope row and the graph/list, so the two stay in step.
    val holder = remember { PeqStateHolder(context.applicationContext) }
    Workspace(
        current = DspDestination.PARAMETRIC_EQ,
        // The scope switch and action chips take the whole toolbar line on this screen.
        showStatusStrip = false,
        toolbarActions = { PeqToolbarActions(holder) },
    ) { ParametricEqScreen(holder = holder) }
}

@Composable
private fun AlignmentWorkspace(startPage: Int) {
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, Routes.ALIGNMENT_PAGE_COUNT - 1)) {
        Routes.ALIGNMENT_PAGE_COUNT
    }
    val scope = rememberCoroutineScope()
    Workspace(
        current = DspDestination.ALIGNMENT,
        toolbarActions = {
            if (LocalContext.current.isHeadUnitDisplay()) {
                ArtPagerFinder(
                    pagerState = pagerState,
                    labels = listOf("DELAY", "ALIGN", "ALLPASS", "CENTRE"),
                    frac = WorkspaceArt.finder4,
                    selected = pagerState.currentPage,
                    onSelect = { page -> scope.launch { pagerState.scrollToPage(page) } },
                )
            } else {
                DspPagerArrows(pagerState)
            }
        },
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> GainsDelayScreen()
                1 -> SpeakerAlignScreen()
                Routes.ALIGNMENT_PAGE_ALLPASS -> AllPassPage()
                else -> VirtualCentreScreen()
            }
        }
    }
}

@Composable
private fun CrossoverWorkspace() {
    val pagerState = rememberPagerState { Routes.CROSSOVER_PAGE_COUNT }
    // The pages share one graph-mode choice.
    var graphMode by rememberSaveable { mutableStateOf(CrossoverGraphMode.MAGNITUDE) }
    Workspace(
        current = DspDestination.CROSSOVER_TILT,
        toolbarActions = { PagerControls(pagerState, listOf("LOW/MID", "MID/HIGH", "TILT", "V-BASS"), WorkspaceArt.finder4) },
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> CrossoverLowMidPage(graphMode, { graphMode = it })
                1 -> CrossoverMidHighPage(graphMode, { graphMode = it })
                2 -> CrossoverTiltPage(graphMode, { graphMode = it })
                else -> CrossoverVirtualBassPage(graphMode, { graphMode = it })
            }
        }
    }
}

@Composable
private fun DynamicsWorkspace() {
    val pageCount = Routes.DYNAMICS_PAGE_COUNT
    val pagerState = rememberPagerState { pageCount }
    val labels = remember { listOf("OVERVIEW") + List(NativeBmwDspValues.MBC_BAND_COUNT) { "B${it + 1}" } + "LIMITERS" }
    Workspace(
        current = DspDestination.DYNAMICS,
        toolbarActions = { PagerControls(pagerState, labels, WorkspaceArt.finder6) },
    ) {
        // Top-aligned: a page shorter than the pane (the visualiser) would otherwise float in the
        // middle and push its bottom slider toward the bezel.
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
            when (page) {
                0 -> CompressorVisualiserPage()
                pageCount - 1 -> CompressorDriverPage()
                else -> CompressorBandPage(page - 1)
            }
        }
    }
}

@Composable
private fun OutputWorkspace() {
    // One page, so no page finder on the toolbar line.
    Workspace(current = DspDestination.OUTPUT) { HeadroomOutputScreen() }
}
