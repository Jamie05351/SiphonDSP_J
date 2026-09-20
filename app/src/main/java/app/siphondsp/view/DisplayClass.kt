package app.siphondsp.view

import android.content.Context

/**
 * True on the fixed 1280x480 mdpi head unit (screenWidthDp ~= 1280); false on a phone. No real
 * phone gets remotely close to 1280dp wide in landscape at any density, so a wide margin below it
 * (1100dp) reliably tells the two apart. Everything phone-specific (art, rects, rail geometry) is
 * gated on this being false, so the head unit's own layout path is never touched.
 */
internal fun Context.isHeadUnitDisplay(): Boolean = resources.configuration.screenWidthDp >= 1100
