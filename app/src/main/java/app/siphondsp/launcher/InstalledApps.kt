package app.siphondsp.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/** One app on the head unit that has a launcher entry: its name, the exact activity, its icon. */
@Immutable
class AppEntry(val label: String, val component: ComponentName, val icon: ImageBitmap?) {
    val key: String get() = component.flattenToString()
}

/**
 * The installed apps the home screen and drawer show, kept current while the process lives.
 *
 * Loaded off the main thread through [LauncherApps], the launcher API: it lists exactly what other
 * launchers list, and its callback reports installs, updates, removals and apps being enabled or
 * disabled even while the home screen is in the background, so nothing needs a rescan on return.
 * Bursts of changes (an app store updating several apps) are folded into one reload. Unchanged apps
 * keep their already-drawn icon.
 */
class InstalledApps private constructor(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    private var reload: Job? = null
    private val icons = HashMap<String, Pair<Long, ImageBitmap?>>()

    /** Every launchable app except J_DSP itself, sorted by name; null until the first load ends. */
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
    }

    init {
        launcherApps.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        scheduleReload(immediately = true)
    }

    fun find(component: ComponentName): AppEntry? = _apps.value?.firstOrNull { it.component == component }

    private fun scheduleReload(immediately: Boolean = false) {
        reload?.cancel()
        reload = scope.launch {
            if (!immediately) delay(RELOAD_DEBOUNCE_MS)
            try {
                _apps.value = load()
            } catch (e: RuntimeException) {
                // Keep showing the last list rather than an empty home screen.
                Timber.w(e, "App list reload failed")
            }
        }
    }

    // A cancelled reload can still be mid-way through (loading has no suspension points), so two
    // can overlap; the icon cache is only touched under its own lock.
    private fun load(): List<AppEntry> = synchronized(icons) {
        val own = context.packageName
        val size = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val seen = HashSet<String>()
        val result = launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != own }
            .map { info -> entry(info, size).also { seen += it.key } }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        icons.keys.retainAll(seen)
        result
    }

    private fun entry(info: LauncherActivityInfo, size: Int): AppEntry {
        val key = info.componentName.flattenToString()
        val stamp = info.firstInstallTime xor lastUpdate(info.componentName.packageName)
        val cached = icons[key]
        val icon = if (cached != null && cached.first == stamp) {
            cached.second
        } else {
            draw(info.getBadgedIcon(0), size).also { icons[key] = stamp to it }
        }
        return AppEntry(info.label.toString(), info.componentName, icon)
    }

    private fun lastUpdate(pkg: String): Long = try {
        context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
    } catch (_: Exception) {
        0L
    }

    companion object {
        private const val RELOAD_DEBOUNCE_MS = 250L
        private const val ICON_DP = 96f

        @Volatile private var instance: InstalledApps? = null

        fun get(context: Context): InstalledApps =
            instance ?: synchronized(this) {
                instance ?: InstalledApps(context.applicationContext).also { instance = it }
            }

        /** [drawable] as a square bitmap [size] px across, or null if it can't be drawn. */
        internal fun draw(drawable: Drawable?, size: Int): ImageBitmap? = try {
            drawable?.toBitmap(size, size)?.asImageBitmap()
        } catch (e: RuntimeException) {
            Timber.w(e, "Icon could not be drawn")
            null
        }
    }
}
