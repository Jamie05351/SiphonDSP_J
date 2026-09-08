package app.siphondsp.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.siphondsp.view.BmwDashboardSkin

/**
 * Base Material3 [androidx.compose.material3.ColorScheme]. Every DSP workspace in this app is a
 * fixed dark dashboard (see BmwDashboardSkin), so this only defines a dark scheme -- there is no
 * light variant to switch to. `primary` is mapped to the app's light-blue accent since that's the
 * closest Material role to how it's actually used (default interactive accent, selection
 * highlight); the domain-specific band/section colors that don't fit Material's roles at all live
 * in [BmwColors] instead, not forced in here.
 */
private val BmwDarkColorScheme = darkColorScheme(
    primary = Color(BmwDashboardSkin.LIGHT_BLUE),
    onPrimary = Color.Black,
    secondary = Color(BmwDashboardSkin.LIGHT_BLUE_BRIGHT),
    error = Color(BmwDashboardSkin.M_RED),
    background = Color.Black,
    surface = Color(BmwDashboardSkin.SIDEBAR_GUNMETAL),
    onBackground = Color.White,
    onSurface = Color.White,
)

/**
 * Root theme composable for every ported DSP screen. Wraps [MaterialTheme] with
 * [BmwDarkColorScheme] and provides [LocalBmwColors] so descendants can reach the band-specific
 * accent palette via [BmwTheme.colors] (see usage below).
 *
 * `isSystemInDarkTheme()` is read but currently ignored on purpose -- this dashboard is always
 * dark regardless of the device's system theme (a car head unit's own OS theme setting shouldn't
 * change how the DSP tuning screens look), kept as a parameter only so a future light variant
 * (if one is ever designed) has an obvious place to branch from without changing every call site.
 */
@Composable
fun BmwDspTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBmwColors provides BmwDashboardColors) {
        MaterialTheme(
            colorScheme = BmwDarkColorScheme,
            content = content,
        )
    }
}

/** `BmwTheme.colors.sliderLowBand` reads better at call sites than
 *  `LocalBmwColors.current.sliderLowBand` -- mirrors Material3's own `MaterialTheme.colorScheme`
 *  convenience-accessor pattern. */
object BmwTheme {
    val colors: BmwColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBmwColors.current
}
