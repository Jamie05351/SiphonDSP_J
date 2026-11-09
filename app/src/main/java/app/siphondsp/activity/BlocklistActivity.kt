package app.siphondsp.activity

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.siphondsp.R
import app.siphondsp.compose.screens.BlocklistScreen
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.AppInfo
import app.siphondsp.model.room.BlockedApp
import app.siphondsp.model.room.resolveBlockedAppIcons
import app.siphondsp.session.dump.DumpManager
import app.siphondsp.session.rootless.SessionRecordingPolicyManager
import app.siphondsp.utils.extensions.CompatExtensions.getInstalledApplicationsCompat
import app.siphondsp.utils.extensions.ContextExtensions.getAppIcon
import app.siphondsp.utils.extensions.ContextExtensions.getAppNameFromUidSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.android.ext.android.inject

/** Apps whose audio is never captured ([BlocklistScreen]). */
class BlocklistActivity : BaseActivity() {

    private val dumpManager: DumpManager by inject()
    private val repository get() = app.blockedAppRepository

    private var blocked by mutableStateOf<List<BlockedApp>?>(null)
    private var installedApps by mutableStateOf<List<AppInfo>?>(null)
    private var restrictedApps by mutableStateOf<List<String>>(emptyList())

    // Keyed by packageName, not uid: a shared android:sharedUserId gives several apps one uid.
    private val iconCache = hashMapOf<String, Drawable>()
    private lateinit var policyManager: SessionRecordingPolicyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyManager = SessionRecordingPolicyManager(this)

        lifecycleScope.launch {
            repository.blocklist
                .map { list ->
                    withContext(Dispatchers.IO) {
                        resolveBlockedAppIcons(list, iconCache) { getAppIcon(it) }
                    }
                    list.sortedBy { it.appName }
                }
                .collect { blocked = it }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { restrictedApps = readRestrictedApps() }
        }

        setContent {
            BmwDspTheme {
                BlocklistScreen(
                    blocked = blocked,
                    installedApps = installedApps,
                    restrictedApps = restrictedApps,
                    onOpenPicker = ::loadInstalledApps,
                    onAdd = { info ->
                        lifecycleScope.launch { repository.insert(BlockedApp(info.uid, info.packageName, info.appName)) }
                    },
                    onRemove = { app -> lifecycleScope.launch { repository.delete(app) } },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onDestroy() {
        policyManager.destroy()
        super.onDestroy()
    }

    /** Apps that block capture themselves; only listed when "Exclude unsupported apps" is on. */
    private suspend fun readRestrictedApps(): List<String> {
        if (!prefsApp.get<Boolean>(R.string.key_session_exclude_restricted)) return emptyList()
        return withContext(Dispatchers.IO) {
            dumpManager.dumpCaptureAllowlistLog()?.let { policyManager.update(it) }
            policyManager.getRestrictedUids().map { "${getAppNameFromUidSafe(it)} (UID $it)" }
        }
    }

    private fun loadInstalledApps() {
        if (installedApps != null) return
        lifecycleScope.launch {
            installedApps = withContext(Dispatchers.IO) {
                packageManager.getInstalledApplicationsCompat(0)
                    .filterNot { (it.flags and ApplicationInfo.FLAG_INSTALLED) == 0 }
                    .mapIndexed { index, info ->
                        if (index % 5 == 0) yield()
                        AppInfo(
                            info.loadLabel(packageManager).toString(),
                            info.packageName,
                            info.loadIcon(packageManager),
                            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0,
                            info.uid,
                        )
                    }
                    .sortedWith(compareBy({ !it.isSystem }, { it.appName }))
            }
        }
    }
}
