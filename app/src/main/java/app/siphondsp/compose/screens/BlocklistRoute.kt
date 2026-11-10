package app.siphondsp.compose.screens

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.siphondsp.MainApplication
import app.siphondsp.R
import app.siphondsp.compose.nav.LocalAppNavigator
import app.siphondsp.model.AppInfo
import app.siphondsp.model.room.BlockedApp
import app.siphondsp.model.room.resolveBlockedAppIcons
import app.siphondsp.session.dump.DumpManager
import app.siphondsp.session.rootless.SessionRecordingPolicyManager
import app.siphondsp.utils.extensions.CompatExtensions.getInstalledApplicationsCompat
import app.siphondsp.utils.extensions.ContextExtensions.getAppIcon
import app.siphondsp.utils.extensions.ContextExtensions.getAppNameFromUidSafe
import app.siphondsp.utils.preferences.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.core.context.GlobalContext

/** Apps whose audio is never captured: the state behind [BlocklistScreen]. */
class BlocklistViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<MainApplication>()
    private val repository get() = app.blockedAppRepository
    private val dumpManager: DumpManager = GlobalContext.get().get()
    private val prefs: Preferences.App = GlobalContext.get().get()
    private val policyManager = SessionRecordingPolicyManager(application)

    // Keyed by packageName, not uid: a shared android:sharedUserId gives several apps one uid.
    private val iconCache = hashMapOf<String, Drawable>()

    val blocked: StateFlow<List<BlockedApp>?> = repository.blocklist
        .map { list ->
            withContext(Dispatchers.IO) { resolveBlockedAppIcons(list, iconCache) { app.getAppIcon(it) } }
            list.sortedBy { it.appName }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _installedApps = MutableStateFlow<List<AppInfo>?>(null)
    val installedApps: StateFlow<List<AppInfo>?> = _installedApps.asStateFlow()

    private val _restrictedApps = MutableStateFlow<List<String>>(emptyList())
    val restrictedApps: StateFlow<List<String>> = _restrictedApps.asStateFlow()

    private var installedAppsJob: Job? = null

    /** Apps that block capture themselves; only listed when "Exclude unsupported apps" is on. */
    fun refreshRestrictedApps() {
        viewModelScope.launch {
            _restrictedApps.value = if (!prefs.get<Boolean>(R.string.key_session_exclude_restricted)) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    dumpManager.dumpCaptureAllowlistLog()?.let { policyManager.update(it) }
                    policyManager.getRestrictedUids().map { "${app.getAppNameFromUidSafe(it)} (UID $it)" }
                }
            }
        }
    }

    /** Each time the picker opens, so apps installed or removed meanwhile show up. */
    fun loadInstalledApps() {
        if (installedAppsJob?.isActive == true) return
        installedAppsJob = viewModelScope.launch {
            val pm = app.packageManager
            _installedApps.value = withContext(Dispatchers.IO) {
                pm.getInstalledApplicationsCompat(0)
                    .filterNot { (it.flags and ApplicationInfo.FLAG_INSTALLED) == 0 }
                    .mapIndexed { index, info ->
                        if (index % 5 == 0) yield()
                        AppInfo(
                            info.loadLabel(pm).toString(),
                            info.packageName,
                            info.loadIcon(pm),
                            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0,
                            info.uid,
                        )
                    }
                    .sortedWith(compareBy({ !it.isSystem }, { it.appName }))
            }
        }
    }

    fun add(info: AppInfo) {
        viewModelScope.launch { repository.insert(BlockedApp(info.uid, info.packageName, info.appName)) }
    }

    fun remove(blockedApp: BlockedApp) {
        viewModelScope.launch { repository.delete(blockedApp) }
    }

    override fun onCleared() {
        policyManager.destroy()
    }
}

@Composable
fun BlocklistRoute(vm: BlocklistViewModel = viewModel()) {
    val navigator = LocalAppNavigator.current
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val installedApps by vm.installedApps.collectAsStateWithLifecycle()
    val restrictedApps by vm.restrictedApps.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.refreshRestrictedApps()
        onPauseOrDispose {}
    }
    BlocklistScreen(
        blocked = blocked,
        installedApps = installedApps,
        restrictedApps = restrictedApps,
        onOpenPicker = vm::loadInstalledApps,
        onAdd = vm::add,
        onRemove = vm::remove,
        onBack = navigator::back,
    )
}
