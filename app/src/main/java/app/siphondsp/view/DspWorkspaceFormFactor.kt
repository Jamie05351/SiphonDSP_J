package app.siphondsp.view

import android.content.Context

/**
 * The dedicated BMW DSP workspace chrome (full-screen backdrop, immersive/no system bars,
 * transparent indented toolbar, no title) is authored for the head unit's ~1280x480 landscape
 * screen. On anything else -- a phone, a tablet, any portrait use -- that layout falls apart
 * (the rail baked into the backdrop gets cropped off the edge, the immersive flags are too
 * aggressive), so those get a plain fallback: system bars visible, an opaque titled toolbar, and
 * the backdrop stretched (FIT_XY) so the whole rail stays on screen and aligned with the
 * invisible click-targets.
 */
object DspWorkspaceFormFactor {

    /** True only for a short, very wide screen -- the head unit. */
    fun isHeadUnit(context: Context): Boolean {
        val c = context.resources.configuration
        val w = c.screenWidthDp
        val h = c.screenHeightDp
        return h in 1..MAX_HEAD_UNIT_HEIGHT_DP && w.toFloat() / h.toFloat() >= MIN_HEAD_UNIT_ASPECT
    }

    private const val MAX_HEAD_UNIT_HEIGHT_DP = 520
    private const val MIN_HEAD_UNIT_ASPECT = 2.4f
}
