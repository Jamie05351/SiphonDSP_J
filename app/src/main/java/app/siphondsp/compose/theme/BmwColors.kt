package app.siphondsp.compose.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.siphondsp.view.BmwDashboardSkin

/**
 * The BMW dashboard's domain-specific accent palette (Low band, Mid band, Headroom, Tilt, etc.),
 * as Compose [Color]s.
 *
 * Material3's default color roles (primary/secondary/tertiary) don't map onto this app's actual
 * semantics -- "Low=blue / Mid=yellow / Headroom=purple / Tilt=orange" are fixed, named
 * conventions used consistently across every DSP workspace (see BmwDashboardSkin's own doc
 * comments), not a generic 3-role Material scheme. Forcing them into primary/secondary would
 * both be semantically wrong and make screens harder to read later ("wait, which role is Mid
 * band again?").
 *
 * Every value here reads directly from [BmwDashboardSkin]'s existing `Int` constants rather than
 * repeating the hex literals -- BmwDashboardSkin stays the single source of truth for color
 * values while both the View system and Compose are live side by side. If a color changes there,
 * it changes here automatically; there is nothing to keep in sync by hand.
 */
data class BmwColors(
    val lightBlue: Color,
    val lightBlueBright: Color,
    val mBlue: Color,
    val mRed: Color,
    val mGreen: Color,
    val midBandYellow: Color,
    val sidebarGunmetal: Color,
    val sliderLowBand: Color,
    val sliderMidBand: Color,
    val sliderHeadroom: Color,
    val sliderTilt: Color,
    val sliderDefault: Color,
    val toggleOnGreen: Color,
)

/** The single instance of [BmwColors] this app uses -- there is currently no light/alternate
 *  variant (every DSP workspace is a fixed dark dashboard), so this is not theme-switchable yet.
 *  If that ever changes, this becomes a function returning different instances instead of a
 *  single val, same as Material3's own dark/light ColorScheme pair. */
val BmwDashboardColors = BmwColors(
    lightBlue = Color(BmwDashboardSkin.LIGHT_BLUE),
    lightBlueBright = Color(BmwDashboardSkin.LIGHT_BLUE_BRIGHT),
    mBlue = Color(BmwDashboardSkin.M_BLUE),
    mRed = Color(BmwDashboardSkin.M_RED),
    mGreen = Color(BmwDashboardSkin.M_GREEN),
    midBandYellow = Color(BmwDashboardSkin.MID_BAND_YELLOW),
    sidebarGunmetal = Color(BmwDashboardSkin.SIDEBAR_GUNMETAL),
    sliderLowBand = Color(BmwDashboardSkin.SLIDER_LOW_BAND_COLOR),
    sliderMidBand = Color(BmwDashboardSkin.SLIDER_MID_BAND_COLOR),
    sliderHeadroom = Color(BmwDashboardSkin.SLIDER_HEADROOM_COLOR),
    sliderTilt = Color(BmwDashboardSkin.SLIDER_TILT_COLOR),
    sliderDefault = Color(BmwDashboardSkin.SLIDER_DEFAULT_COLOR),
    toggleOnGreen = Color(BmwDashboardSkin.TOGGLE_ON_GREEN),
)

/** CompositionLocal so any composable can read `LocalBmwColors.current.lowBand` etc. without
 *  threading a BmwColors parameter through every function signature. staticCompositionLocalOf
 *  (not the dynamic variant) because this value never changes at runtime -- no light/dark
 *  switching exists yet (see BmwDashboardColors' doc above), so Compose doesn't need to track
 *  fine-grained recomposition on reads of it. */
val LocalBmwColors = staticCompositionLocalOf { BmwDashboardColors }
