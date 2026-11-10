package app.siphondsp.model.room

import android.graphics.drawable.Drawable

/** Fill each [BlockedApp.appIcon] from [cache] (keyed by packageName), loading misses via [loadIcon]. */
internal fun resolveBlockedAppIcons(
    apps: List<BlockedApp>,
    cache: MutableMap<String, Drawable>,
    loadIcon: (packageName: String) -> Drawable?,
) {
    apps.forEach { app ->
        app.appIcon = cache[app.packageName] ?: loadIcon(app.packageName)?.also { icon ->
            cache[app.packageName] = icon
        }
    }
}
