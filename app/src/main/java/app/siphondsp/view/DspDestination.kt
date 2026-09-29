package app.siphondsp.view

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.compose.controls.DspSidebarRail
import app.siphondsp.compose.controls.DspWorkspaceBackdrop
import app.siphondsp.compose.theme.BmwDspTheme
import kotlin.math.roundToInt
import kotlin.reflect.KClass

enum class DspDestination(
    @StringRes val labelRes: Int,
    @StringRes val sidebarLabelRes: Int,
    @DrawableRes val icon: Int,
    val activityClass: KClass<out AppCompatActivity>,
    val workspaceMode: String? = null,
    val showInPrimaryNav: Boolean = true,
) {
    // Declaration order is display order (used directly by DspCrossNavBar.populate()) --
    // matches the main tile grid's PEQ, Gains, Xovers, Compressor, All-pass order
    // (fragment_dsp_page_shortcuts.xml) rather than an arbitrary/functional grouping, so the
    // sidebar doesn't present a different sequence than the page the user navigated in from.
    //
    // The workspace's faceplate (background, bezel, rail housing) and each rail tile's glyph are
    // drawn live by DspWorkspaceBackdrop / DspSidebarRail (Compose); there is no per-destination art.
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, ParametricEqualizerActivity::class),
    GAINS_DELAY(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, GainLimiterActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    COMPRESSOR(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, NativeBmwCompressorActivity::class),
    // 5th tile: the per-output all-pass screen (MODE_ALLPASS, OutputAllPassFragment). Was the
    // routing-matrix editor historically; that screen is gone (the matrix itself still runs in
    // the native chain). The Measurements / routing rows now live in the Signal Generator screen
    // (SignalGeneratorScreen) instead of a Settings-page inline card.
    ALLPASS(R.string.action_allpass, R.string.action_allpass, R.drawable.ic_twotone_route_24dp, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ALLPASS),
}

object DspCrossNavBar {
    // The workspace's faceplate is drawn live: DspWorkspaceBackdrop (Compose) fills R.id.dsp_workspace_
    // backdrop with the textured background and the screen bezel, and DspSidebarRail (Compose) draws
    // the rail housing and the five tiles, hosted in the dsp_cross_nav ComposeView over dsp_sidebar's
    // reserved column (see that column's own comment in activity_parametric_eq.xml). populate() sets
    // both and pushes this destination's state into the rail.

    // The head unit is explicitly authored/documented (activity_parametric_eq.xml) as a fixed
    // 1280x480 mdpi display, i.e. screenWidthDp ~= 1280 exactly (mdpi is 1px == 1dp). No real
    // phone gets remotely close to that in landscape at any density, so a wide margin below it
    // (1100dp) reliably tells the two apart without needing an exact resolution/density match.
    private fun isHeadUnitDisplay(activity: FragmentActivity): Boolean = activity.isHeadUnitDisplay()

    // Phone only (populate() never calls this on the head unit, whose fixed dp dimens --
    // dsp_sidebar_width, dsp_toolbar_nav_inset, dsp_status_strip_margin_start -- stay exactly as
    // authored). The sidebar column and the toolbar insets built on it use a phone-sized rail:
    // the head unit's fixed 140dp is ~50% too wide on a ~832dp phone. The dp buffers past the rail
    // (43dp to the back arrow, +72dp to the status strip) are touch-target spacing, so they're
    // kept as-is.
    private const val PHONE_RAIL_WIDTH_DP = 118
    private const val NAV_INSET_BUFFER_DP = 43
    private const val STATUS_STRIP_GAP_DP = 72

    private fun applyPhoneRailGeometry(activity: FragmentActivity) {
        val sidebar = activity.findViewById<View>(R.id.dsp_sidebar) ?: return
        // Posted: layoutParams changes made mid-layout were sometimes swallowed (sidebar stayed
        // 140dp while the toolbar margin took effect); posting runs the update after that pass.
        sidebar.post { applyRailGeometry(activity) }
    }

    private fun applyRailGeometry(activity: FragmentActivity) {
        val density = activity.resources.displayMetrics.density
        val railPx = (PHONE_RAIL_WIDTH_DP * density).roundToInt()
        val stripStart = railPx + ((NAV_INSET_BUFFER_DP + STATUS_STRIP_GAP_DP) * density).roundToInt()

        activity.findViewById<View>(R.id.dsp_sidebar)?.updateLayoutParams { width = railPx }
        // The toolbar starts at the rail's edge (its own 43dp padding is the gap to the arrow).
        activity.findViewById<Toolbar>(R.id.toolbar)?.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = railPx }
        for (id in intArrayOf(R.id.dsp_status_strip, R.id.dsp_toolbar_actions)) {
            activity.findViewById<View>(id)?.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = stripStart }
        }
    }

    fun populate(
        activity: FragmentActivity,
        container: ComposeView,
        current: DspDestination,
        canNavigate: () -> Boolean = { true },
    ) {
        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }
        val headUnit = isHeadUnitDisplay(activity)

        // The screen faceplate: textured background + slim metal bezel, behind everything.
        activity.findViewById<ComposeView>(R.id.dsp_workspace_backdrop)?.let { backdrop ->
            backdrop.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            backdrop.setContent { DspWorkspaceBackdrop() }
        }
        if (!headUnit) applyPhoneRailGeometry(activity)

        container.setContent {
            BmwDspTheme {
                DspSidebarRail(
                    destinations = destinations,
                    current = current,
                    // The head unit's rail housing sits 9dp in from the screen edge and is 106dp wide,
                    // inside the fixed 140dp column; a phone's column is the rail's width already.
                    modifier = if (headUnit) {
                        Modifier.padding(start = 9.dp, top = 9.dp, bottom = 9.dp).width(106.dp)
                    } else {
                        Modifier.padding(6.dp)
                    },
                    canNavigate = canNavigate,
                    onNavigate = { destination ->
                        // Rail navigation is a clean cut, not a transition: picking another DSP
                        // menu from the sidebar should just swap the screen. The platform default
                        // slides the new activity in from the right (and the old one out left),
                        // which reads like a page swipe -- and swiping is reserved for paging
                        // *within* a menu. FLAG_ACTIVITY_NO_ANIMATION suppresses both the enter
                        // and exit animation for this launch; overridePendingTransition(0, 0)
                        // covers the finishing activity on API levels where the flag alone leaves
                        // a close animation.
                        val intent = Intent(activity, destination.activityClass.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        destination.workspaceMode?.let { intent.putExtra(CrossoverTiltActivity.EXTRA_WORKSPACE_MODE, it) }
                        activity.startActivity(intent)
                        @Suppress("DEPRECATION")
                        activity.overridePendingTransition(0, 0)
                        activity.finish()
                        @Suppress("DEPRECATION")
                        activity.overridePendingTransition(0, 0)
                    },
                )
            }
        }

        container.visibility = View.VISIBLE
    }
}
