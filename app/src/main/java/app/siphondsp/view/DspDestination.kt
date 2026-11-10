package app.siphondsp.view

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.siphondsp.R

/**
 * The five DSP workspaces, in display order: the front page's chain (PEQ, Alignment, Xovers,
 * Dynamics, Output), which is also the sidebar rail's order. [route] is the workspace's
 * destination in the app's navigation graph (see `compose/nav/AppNavHost.kt`).
 */
enum class DspDestination(
    @StringRes val labelRes: Int,
    @StringRes val sidebarLabelRes: Int,
    @DrawableRes val icon: Int,
    val route: String,
    val showInPrimaryNav: Boolean = true,
) {
    PARAMETRIC_EQ(R.string.action_parametric_eq, R.string.sidebar_label_parametric_eq, R.drawable.ic_twotone_peq_sliders_28dp, "peq"),
    ALIGNMENT(R.string.action_gain_limiter, R.string.sidebar_label_gains_delay, R.drawable.ic_twotone_gain_knob_28dp, "alignment"),
    CROSSOVER_TILT(R.string.action_crossover_tilt, R.string.sidebar_label_crossover_tilt, R.drawable.ic_twotone_crossover_tilt_28dp, "crossover"),
    DYNAMICS(R.string.action_compressor, R.string.sidebar_label_compressor, R.drawable.ic_twotone_compressor_pulse_28dp, "dynamics"),
    // The end of the chain: headroom, post gains, master limiter, master volume.
    OUTPUT(R.string.action_output, R.string.sidebar_label_output, R.drawable.ic_twotone_gain_knob_28dp, "output"),
}

/** Geometry of the workspace sidebar rail, shared by the workspaces and the front page's morph. */
object DspRail {
    // The head unit is a fixed 1280x480 mdpi display with its own authored dimens; a phone gets a
    // narrower rail (124dp is too wide on a ~832dp phone). The buffers past the rail (43dp to the
    // back arrow, +72dp to the status strip) are touch-target spacing and stay the same.
    private const val PHONE_RAIL_WIDTH_DP = 118f
    private const val NAV_INSET_DP = 43f
    private const val STATUS_STRIP_GAP_DP = 72f

    /** The rail's width in dp: the head unit's dsp_sidebar_width, or the phone rail. */
    fun sidebarWidthDp(context: Context): Float =
        if (context.isHeadUnitDisplay()) {
            context.resources.getDimension(R.dimen.dsp_sidebar_width) / context.resources.displayMetrics.density
        } else {
            PHONE_RAIL_WIDTH_DP
        }

    /** Where the status strip and the toolbar actions start, from the screen's left edge, in dp. */
    fun toolbarActionsStartDp(context: Context): Float =
        if (context.isHeadUnitDisplay()) {
            context.resources.getDimension(R.dimen.dsp_status_strip_margin_start) / context.resources.displayMetrics.density
        } else {
            PHONE_RAIL_WIDTH_DP + NAV_INSET_DP + STATUS_STRIP_GAP_DP
        }
}
