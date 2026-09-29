package app.siphondsp.view

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.compose.runtime.mutableStateOf
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
    // `backdrop` is the full-screen head-unit art (rail housing, tiles and background baked in).
    // Since the v5 art every destination shares one image (dsp_workspace_backdrop_v5, 2048x768 --
    // the head unit's aspect) with no selected-tile highlight, baked or live. Gains & Delay draws
    // its car live (CarSpeakerDiagram) rather than swapping in per-band art.
    //
    // `backdropPhone` is the matching phone art (dsp_workspace_backdrop_v5_phone, 1846x852 -- the
    // phone's aspect), picked by name at runtime (see DspCrossNavBar.isHeadUnitDisplay) rather
    // than by density/config qualifiers.
    //
    // `iconOn`/`iconOff`: the hand-authored tile glyph in its lit (selected) and dim (unselected)
    // colour variants -- native canvas sizes vary per asset, scaled to fit inside the tile box.
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, R.drawable.nav_peq_on, R.drawable.nav_peq_off, R.drawable.dsp_workspace_backdrop_v5, R.drawable.dsp_workspace_backdrop_v5_phone, ParametricEqualizerActivity::class),
    GAINS_DELAY(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, R.drawable.nav_gains_delay_on, R.drawable.nav_gains_delay_off, R.drawable.dsp_workspace_backdrop_v5, R.drawable.dsp_workspace_backdrop_v5_phone, GainLimiterActivity::class),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, R.drawable.nav_crossover_on, R.drawable.nav_crossover_off, R.drawable.dsp_workspace_backdrop_v5, R.drawable.dsp_workspace_backdrop_v5_phone, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_CROSSOVER),
    COMPRESSOR(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, R.drawable.nav_compressor_on, R.drawable.nav_compressor_off, R.drawable.dsp_workspace_backdrop_v5, R.drawable.dsp_workspace_backdrop_v5_phone, NativeBmwCompressorActivity::class),
    // 5th tile: the per-output all-pass screen (MODE_ALLPASS, OutputAllPassFragment). Was the
    // routing-matrix editor historically; that screen is gone (the matrix itself still runs in
    // the native chain). The Measurements / routing rows now live in the Signal Generator screen
    // (SignalGeneratorScreen) instead of a Settings-page inline card.
    ALLPASS(R.string.action_allpass, R.string.action_allpass, R.drawable.ic_twotone_route_24dp, R.drawable.nav_allpass_on, R.drawable.nav_allpass_off, R.drawable.dsp_workspace_backdrop_v5, R.drawable.dsp_workspace_backdrop_v5_phone, CrossoverTiltActivity::class, CrossoverTiltActivity.MODE_ALLPASS),
}

object DspCrossNavBar {
    // The rail -- rounded glass panel + background -- is baked into each destination's own
    // full-screen backdrop image (DspDestination.backdrop, set on R.id.dsp_workspace_backdrop
    // below). Tile icons and the current destination's lit glow are drawn live instead, by
    // DspSidebarNav (Compose), hosted in the dsp_cross_nav ComposeView over dsp_sidebar's reserved
    // column (see that column's own comment in activity_parametric_eq.xml) -- populate() just
    // sets the backdrop image and pushes this destination's state into that ComposeView.

    /**
     * Where one backdrop's baked-in rail tiles sit. [rowWeights] alternate gap / tile / gap ...
     * top to bottom as the art's own y fractions x 10000 (a ratio, so the art's native size
     * doesn't matter); [leftInset]/[rightInset] are the tiles' x span as fractions of the sidebar
     * column. On a phone the column itself follows the art ([railWidth] px of [width] x [height]).
     */
    private class RailArt(
        val rowWeights: IntArray,
        val leftInset: Float,
        val rightInset: Float,
        val width: Float = 0f,
        val height: Float = 0f,
        val railWidth: Float = 0f,
    )

    // Head unit, v5 plain art (2048x768): tile outlines at y 43-159, 179-296, 315-432, 452-568,
    // 588-704 and x 34-159, i.e. 21.25..100 dp of the fixed 140 dp column.
    private val HEAD_UNIT_V5 = RailArt(
        intArrayOf(560, 1523, 247, 1536, 234, 1536, 247, 1523, 247, 1523, 824),
        leftInset = 21.25f / 140f,
        rightInset = (140f - 100f) / 140f,
    )

    // Phone, v5 art (1846x852): rail column 0..245 px, tiles at x 49-212 and y 45-180, 199-333,
    // 352-485, 504-638, 657-795.
    private val PHONE_V5 = RailArt(
        intArrayOf(528, 1596, 211, 1585, 211, 1573, 211, 1585, 211, 1631, 658),
        leftInset = 49f / 245f,
        rightInset = (245f - 213f) / 245f,
        width = 1846f,
        height = 852f,
        railWidth = 245f,
    )

    // Phones keep the plain art's rail on every page: re-sizing the sidebar column while the pager
    // swipes would relayout the page mid-gesture. The head unit's Gains & Delay pages no longer
    // swap in car art (the car is drawn live), so every head-unit page uses the plain art's rail.
    private fun railArtFor(headUnit: Boolean): RailArt = if (headUnit) HEAD_UNIT_V5 else PHONE_V5

    // The rail rows in use for the current display. Only the Compose tile rows change with it;
    // the sidebar column's size never does.
    private val currentRail = mutableStateOf(HEAD_UNIT_V5)

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
    // authored). The sidebar column and the toolbar insets built on it follow the rail's width in
    // the art at its centerCrop scale, since a fixed 140dp is ~50% too wide on a ~832dp phone.
    // The dp buffers past the rail (43dp to the back arrow, +72dp to the status strip) are
    // touch-target spacing, so they're kept as-is.
    private const val NAV_INSET_BUFFER_DP = 43
    private const val STATUS_STRIP_GAP_DP = 72

    private fun applyPhoneRailGeometry(activity: FragmentActivity) {
        val backdrop = activity.findViewById<ImageView>(R.id.dsp_workspace_backdrop) ?: return
        // doOnLayout fires while the parent ConstraintLayout is still mid-layout, and layoutParams
        // changes made there were sometimes swallowed (sidebar stayed 140dp while the toolbar
        // margin took effect). Posting runs the update after that pass, so it always lands.
        backdrop.doOnLayout { view -> view.post { applyRailGeometry(activity, view.width, view.height, currentRail.value) } }
    }

    private fun applyRailGeometry(activity: FragmentActivity, viewWidth: Int, viewHeight: Int, art: RailArt) {
        val scale = max(viewWidth / art.width, viewHeight / art.height)
        val railPx = (art.railWidth * scale).roundToInt()
        val density = activity.resources.displayMetrics.density
        val stripStart = railPx + ((NAV_INSET_BUFFER_DP + STATUS_STRIP_GAP_DP) * density).roundToInt()

        activity.findViewById<View>(R.id.dsp_sidebar)?.updateLayoutParams { width = railPx }
        // The toolbar starts at the rail's edge (its own 43dp padding is the gap to the arrow).
        activity.findViewById<Toolbar>(R.id.toolbar)?.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = railPx }
        for (id in intArrayOf(R.id.dsp_status_strip, R.id.dsp_toolbar_actions)) {
            activity.findViewById<View>(id)?.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = stripStart }
        }
    }

    /** Swaps the workspace backdrop for the head-unit or phone art, per [isHeadUnitDisplay]. Any
     *  variant must share its tier's exact pixel size, or centerCrop drifts the baked-in rail away
     *  from the live sidebar tiles. */
    fun showBackdrop(activity: FragmentActivity, headUnit: Int, phone: Int) {
        val isHeadUnit = isHeadUnitDisplay(activity)
        val backdrop = if (isHeadUnit) headUnit else phone
        activity.findViewById<ImageView>(R.id.dsp_workspace_backdrop)?.setImageResource(backdrop)
        if (isHeadUnit) currentRail.value = railArtFor(headUnit = true)
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
        val headUnit = isHeadUnitDisplay(activity)
        currentRail.value = railArtFor(headUnit)
        showBackdrop(activity, current.backdrop, current.backdropPhone)
        if (!headUnit) applyPhoneRailGeometry(activity)

        container.setContent {
            BmwDspTheme {
                DspSidebarNav(
                    destinations = destinations,
                    current = current,
                    weights = currentRail.value.rowWeights,
                    leftInsetFraction = currentRail.value.leftInset,
                    rightInsetFraction = currentRail.value.rightInset,
                    bakedInArt = true,
                    // The v5 head-unit art has no selected-tile mark, and none is drawn over it.
                    showSelection = !headUnit,
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
