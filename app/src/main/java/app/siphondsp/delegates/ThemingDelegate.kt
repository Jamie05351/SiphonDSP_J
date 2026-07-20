package app.siphondsp.delegates

import android.app.Activity
import app.siphondsp.R
import app.siphondsp.model.preference.AppTheme
import app.siphondsp.utils.preferences.Preferences
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

interface ThemingDelegate {
    fun applyAppTheme(activity: Activity)

    companion object {
        fun getThemeResIds(appTheme: AppTheme, isAmoled: Boolean): List<Int> {
            val resIds = mutableListOf<Int>()
            resIds += when (appTheme) {
                AppTheme.MONET, AppTheme.CUSTOM -> R.style.Theme_SiphonDSP_Monet
                AppTheme.GREEN_APPLE -> R.style.Theme_SiphonDSP_GreenApple
                AppTheme.STRAWBERRY_DAIQUIRI -> R.style.Theme_SiphonDSP_StrawberryDaiquiri
                AppTheme.HONEY -> R.style.Theme_SiphonDSP_Honey
                AppTheme.TEALTURQUOISE -> R.style.Theme_SiphonDSP_TealTurquoise
                AppTheme.YINYANG -> R.style.Theme_SiphonDSP_YinYang
                AppTheme.YOTSUBA -> R.style.Theme_SiphonDSP_Yotsuba
                AppTheme.TIDAL_WAVE -> R.style.Theme_SiphonDSP_TidalWave
                else -> R.style.Theme_SiphonDSP
            }

            if (isAmoled) {
                resIds += R.style.ThemeOverlay_SiphonDSP_Amoled
            }

            return resIds
        }

        /**
         * [DynamicColorsOptions] for the CUSTOM theme: derives a full Material3 tonal palette
         * from a single user-picked seed color, using the same DynamicColors mechanism the
         * MONET theme already relies on (Android 12+, or supported Samsung devices).
         */
        fun customColorOptions(seedColor: Int) = DynamicColorsOptions.Builder()
            .setContentBasedSource(seedColor)
            .build()
    }
}

class ThemingDelegateImpl : ThemingDelegate, KoinComponent {
    private val preferences: Preferences.App by inject()

    override fun applyAppTheme(activity: Activity) {
        val isAmoled = preferences.get<Boolean>(R.string.key_appearance_pure_black)
        val appTheme = AppTheme.valueOf(preferences.get((R.string.key_appearance_app_theme)))

        ThemingDelegate.getThemeResIds(appTheme, isAmoled).forEach { activity.setTheme(it) }

        if (appTheme == AppTheme.CUSTOM) {
            val seedColor = preferences.get<Int>(R.string.key_appearance_custom_color)
            DynamicColors.applyToActivityIfAvailable(activity, ThemingDelegate.customColorOptions(seedColor))
        }
    }
}
