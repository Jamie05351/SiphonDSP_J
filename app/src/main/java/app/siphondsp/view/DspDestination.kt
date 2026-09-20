package app.siphondsp.view

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import app.siphondsp.R
import app.siphondsp.activity.CrossoverTiltActivity
import app.siphondsp.activity.GainLimiterActivity
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.activity.ParametricEqualizerActivity
import app.siphondsp.compose.controls.DspSidebarNav
import app.siphondsp.compose.theme.BmwDspTheme
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.reflect.KClass

enum class DspDestination(
    @StringRes val labelRes: Int,
    @StringRes val sidebarLabelRes: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val iconOn: Int,
    @DrawableRes val iconOff: Int,
    @DrawableRes val backdrop: Int,
    @DrawableRes val backdropPhone: Int,
    val activityClass: KClass<out AppCompatActivity>,
    val workspaceMode: String? = null,
    val showInPrimaryNav: Boolean = true,
) {
    // Declaration order is display order (used directly by DspCrossNavBar.populate()) --
    // matches the main tile grid's PEQ, Gains, Xovers, Compressor, All-pass order
    // (fragment_dsp_page_shortcuts.xml) rather than an arbitrary/functional grouping, so the
    // sidebar doesn't present a different sequence than the page the user navigated in from.
    //
    // `backdrop` is a full-screen, per-destination piece of art (rail housing and background baked
    // in) -- hand-authored per destination, not generated. Tile icons and the selected-tile glow
    // are drawn live by DspCrossNavBar's Compose tiles instead (`iconOn`/`iconOff` below), not
    // baked into this image.
    //
    // `backdropPhone` is a second, separately-authored set for a regular phone screen (drawable-
    // nodpi, since it's picked by name at runtime -- see DspCrossNavBar.isHeadUnitDisplay --
    // rather than by density/config qualifiers). The head-unit set stays exactly as authored;
    // this is purely additive.
    //
    // `iconOn`/`iconOff`: the hand-authored tile glyph in its lit (selected) and dim (unselected)
    // colour variants -- native canvas sizes vary per asset, scaled to fit inside the tile box.
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, R.drawable.nav_peq_on, R.drawable.nav_peq_off, R.drawable.dsp_workspace_backdrop_peq, R.drawable.dsp_workspace_backdrop_peq_phone, ParametricEqualizerActivity::class),
    GAINS_DELAY(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, R.drawable.nav_gains_delay_on, R.drawable.nav_gains_delay_off, R.drawable.dsp_workspace_backdrop_gains, R.drawable.dsp_workspace_backdrop_gains_phone, GainLimiterActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, R.drawable.nav_crossover_on, R.drawable.nav_crossover_off, R.drawable.dsp_workspace_backdrop_xover, R.drawable.dsp_workspace_backdrop_xover_phone, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    COMPRESSOR(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, R.drawable.nav_compressor_on, R.drawable.nav_compressor_off, R.drawable.dsp_workspace_backdrop_compressor, R.drawable.dsp_workspace_backdrop_compressor_phone, NativeBmwCompressorActivity::class),
    // 5th tile: the per-output all-pass screen (MODE_ALLPASS, OutputAllPassFragment). Was the
    // routing-matrix editor historically; that screen is gone (the matrix itself still runs in
    // the native chain). The Measurements / routing rows now live in the Signal Generator screen
    // (SignalGeneratorScreen) instead of a Settings-page inline card.
    ALLPASS(R.string.action_allpass, R.string.action_allpass, R.drawable.ic_twotone_route_24dp, R.drawable.nav_allpass_on, R.drawable.nav_allpass_off, R.drawable.dsp_workspace_backdrop_allpass, R.drawable.dsp_workspace_backdrop_allpass_phone, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ALLPASS),
}

object DspCrossNavBar {
    // The rail -- rounded glass panel + background -- is baked into each destination's own
    // full-screen backdrop image (DspDestination.backdrop, set on R.id.dsp_workspace_backdrop
    // below). Tile icons and the current destination's lit glow are drawn live instead, by
    // DspSidebarNav (Compose), hosted in the dsp_cross_nav ComposeView over dsp_sidebar's reserved
    // column (see that column's own comment in activity_parametric_eq.xml) -- populate() just
    // sets the backdrop image and pushes this destination's state into that ComposeView.

    // Dialed in against the real empty-tile-slot backdrop PNGs with an HTML/JS calibrator (sliders
    // over the actual art, live-updating this array) rather than eyeballed -- see DspSidebarNav's
    // TILE_LEFT_INSET_FRACTION/TILE_RIGHT_INSET_FRACTION for the matching horizontal inset, which
    // came from the same tool. These are weights (a ratio), not absolute pixels, so the source
    // images' native resolution doesn't need to match the on-device render size -- only the
    // proportions matter.
    private val ROW_WEIGHTS = intArrayOf(56, 135, 21, 140, 18, 130, 18, 140, 21, 135, 62)

    // The head unit is explicitly authored/documented (activity_parametric_eq.xml) as a fixed
    // 1280x480 mdpi display, i.e. screenWidthDp ~= 1280 exactly (mdpi is 1px == 1dp). No real
    // phone gets remotely close to that in landscape at any density, so a wide margin below it
    // (1100dp) reliably tells the two apart without needing an exact resolution/density match.
    // NOTE: the sidebar's clickable column width (dsp_sidebar_width, 140dp, fixed) does not scale
    // with this switch -- it was sized against the head unit's 1280dp-wide layout. The phone
    // backdrop art bakes in roughly the same rail-to-width proportion, but a real device may not
    // land pixel-for-pixel; worth a quick on-device check of tap-target alignment.
    private fun isHeadUnitDisplay(activity: FragmentActivity): Boolean = activity.isHeadUnitDisplay()

    // Phone only (populate() never calls this on the head unit, whose fixed dp dimens --
    // dsp_sidebar_width, dsp_toolbar_nav_inset, dsp_status_strip_margin_start -- stay exactly as
    // authored). Both backdrop arts are 2340px wide with the rail's tile column 256px wide (the
    // head unit's 140dp column at its 2340/1280 art scale), and the phone art's tile rows are the
    // head-unit rows scaled by 1080/878, so ROW_WEIGHTS and the tile insets in DspSidebarNav
    // already fit it. Only the column width and the toolbar insets built on it need to follow the
    // art's own centerCrop scale, since a fixed 140dp is ~50% too wide on a ~832dp phone. The dp
    // buffers past the rail (43dp to the back arrow, +72dp to the status strip) are touch-target
    // spacing, so they're kept as-is.
    private const val PHONE_ART_WIDTH = 2340f
    private const val PHONE_ART_HEIGHT = 1080f
    private const val RAIL_ART_WIDTH = 256f
    private const val NAV_INSET_BUFFER_DP = 43
    private const val STATUS_STRIP_GAP_DP = 72

    private fun applyPhoneRailGeometry(activity: FragmentActivity) {
        val backdrop = activity.findViewById<ImageView>(R.id.dsp_workspace_backdrop) ?: return
        backdrop.doOnLayout {
            val scale = max(it.width / PHONE_ART_WIDTH, it.height / PHONE_ART_HEIGHT)
            val railPx = (RAIL_ART_WIDTH * scale).roundToInt()
            val density = activity.resources.displayMetrics.density
            val navInset = railPx + (NAV_INSET_BUFFER_DP * density).roundToInt()
            val stripStart = navInset + (STATUS_STRIP_GAP_DP * density).roundToInt()

            activity.findViewById<View>(R.id.dsp_sidebar)?.updateLayoutParams { width = railPx }
            activity.findViewById<Toolbar>(R.id.toolbar)?.let { toolbar ->
                toolbar.setPaddingRelative(navInset, toolbar.paddingTop, toolbar.paddingEnd, toolbar.paddingBottom)
                toolbar.setContentInsetsRelative(navInset, toolbar.contentInsetEnd)
                toolbar.contentInsetStartWithNavigation = navInset
            }
            for (id in intArrayOf(R.id.dsp_status_strip, R.id.dsp_toolbar_actions)) {
                activity.findViewById<View>(id)?.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = stripStart }
            }
        }
    }

    fun populate(
        activity: FragmentActivity,
        container: ComposeView,
        current: DspDestination,
        canNavigate: () -> Boolean = { true },
    ) {
        val destinations = DspDestination.entries.filter { it.showInPrimaryNav }

        // The rail visual: swap in this destination's own backdrop (housing + background baked
        // in). Picks the head-unit or phone art per-destination based on the live screen width --
        // see isHeadUnitDisplay().
        val backdrop = if (isHeadUnitDisplay(activity)) current.backdrop else current.backdropPhone
        activity.findViewById<ImageView>(R.id.dsp_workspace_backdrop)?.setImageResource(backdrop)
        if (!isHeadUnitDisplay(activity)) applyPhoneRailGeometry(activity)

        container.setContent {
            BmwDspTheme {
                DspSidebarNav(
                    destinations = destinations,
                    current = current,
                    weights = ROW_WEIGHTS,
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
