package app.siphondsp.activity

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.os.Process.myUid
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.R
import app.siphondsp.compose.onboarding.OnboardingPages
import app.siphondsp.compose.onboarding.OnboardingScreen
import app.siphondsp.compose.onboarding.OnboardingUi
import app.siphondsp.compose.onboarding.SetupMethod
import app.siphondsp.compose.onboarding.SetupStep
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.utils.SdkCheck
import app.siphondsp.utils.extensions.ContextExtensions.isPackageInstalled
import app.siphondsp.utils.extensions.ContextExtensions.launchApp
import app.siphondsp.utils.extensions.ContextExtensions.openPlayStoreApp
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.PermissionExtensions.hasDumpPermission
import app.siphondsp.utils.extensions.PermissionExtensions.hasNotificationPermission
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import app.siphondsp.utils.extensions.PermissionExtensions.hasRecordPermission
import app.siphondsp.utils.sdkAbove
import me.timschneeberger.hiddenapi_impl.ShizukuSystemServerApi
import me.timschneeberger.hiddenapi_impl.UserHandle
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * The setup wizard: what the app can't do, then granting DUMP / PROJECT_MEDIA (through Shizuku
 * or by hand over ADB), then the runtime permissions.
 *
 * With [EXTRA_ROOTLESS_REDO_ADB_SETUP] only the method choice and the ADB/Shizuku steps run
 * (Settings → Misc → "Restart setup wizard").
 */
class OnboardingActivity : BaseActivity() {

    /** A dialog the wizard is showing. */
    private data class Prompt(
        val title: Int,
        val message: Int,
        val confirm: Int = android.R.string.ok,
        val onConfirm: (() -> Unit)? = null,
        val dismiss: Int? = null,
    )

    private var redoAdbSetup = false
    private var page by mutableIntStateOf(OnboardingPages.WELCOME)
    private var method by mutableStateOf(SetupMethod.None)
    private var shizukuAlive by mutableStateOf(false)
    private var prompt by mutableStateOf<Prompt?>(null)

    /** Bumped on resume, so the Shizuku "installed" checks are read again. */
    private var resumeCount by mutableIntStateOf(0)

    private val lastPage get() = if (redoAdbSetup) OnboardingPages.ADB_SETUP else OnboardingPages.READY
    private val firstPage get() = if (redoAdbSetup) OnboardingPages.METHOD_SELECT else OnboardingPages.WELCOME

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.all { it.value }) {
            if (page == OnboardingPages.RUNTIME_PERMISSIONS) changePage(forward = true, ignoreConditions = true)
        } else {
            prompt = Prompt(R.string.onboarding_perm_missing_title, R.string.onboarding_perm_missing)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Timber.d("Shizuku binder received")
        shizukuAlive = true
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Timber.d("Shizuku binder died")
        shizukuAlive = false
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (result == PERMISSION_GRANTED) {
            if (requestCode == REQUEST_CODE_SHIZUKU_GRANT) changePage(true)
        } else {
            showShizukuDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        redoAdbSetup = intent.getBooleanExtra(EXTRA_ROOTLESS_REDO_ADB_SETUP, false)
        page = savedInstanceState?.getInt(STATE_PAGE) ?: firstPage
        method = savedInstanceState?.getString(STATE_METHOD)?.let { SetupMethod.valueOf(it) } ?: SetupMethod.None

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_FIX_PERMS, false)) {
            prompt = Prompt(R.string.onboarding_fix_permissions_title, R.string.onboarding_fix_permissions)
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        setContent {
            BmwDspTheme {
                LifecycleResumeEffect(Unit) {
                    resumeCount++
                    onPauseOrDispose {}
                }
                BackHandler { if (page == firstPage) finish() else changePage(false) }
                OnboardingScreen(
                    ui = buildUi(resumeCount),
                    onBack = { changePage(false) },
                    onNext = { changePage(true) },
                    onChooseMethod = {
                        method = it
                        changePage(true)
                    },
                )
                prompt?.let { shown ->
                    AlertDialog(
                        onDismissRequest = { prompt = null },
                        title = { Text(getString(shown.title)) },
                        text = { Text(getString(shown.message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                prompt = null
                                shown.onConfirm?.invoke()
                            }) { Text(getString(shown.confirm)) }
                        },
                        dismissButton = if (shown.dismiss != null) {
                            { TextButton(onClick = { prompt = null }) { Text(getString(shown.dismiss)) } }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, page)
        outState.putString(STATE_METHOD, method.name)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    @Suppress("UNUSED_PARAMETER") // read so the UI is rebuilt after a resume
    private fun buildUi(resumeCount: Int): OnboardingUi {
        val (title, steps) = setupInstructions()
        return OnboardingUi(
            page = page,
            isFirstPage = page == firstPage,
            isLastPage = page == lastPage,
            shizukuSupported = SdkCheck.isQ,
            showNotificationPermission = SdkCheck.isTiramisu,
            setupTitle = title,
            setupSteps = steps,
        )
    }

    private fun setupInstructions(): Pair<String, List<SetupStep>> {
        if (method == SetupMethod.Shizuku) {
            val installed = isPackageInstalled(SHIZUKU_PKG)
            val running = shizukuAlive && installed
            return getString(R.string.onboarding_adb_shizuku_title) to listOf(
                SetupStep(
                    icon = if (installed) R.drawable.ic_twotone_check_circle_24dp else R.drawable.ic_numeric_1_circle_outline,
                    body = getString(R.string.onboarding_adb_shizuku_install_instruction),
                    button = getString(
                        if (installed) R.string.onboarding_adb_shizuku_install_button_done
                        else R.string.onboarding_adb_shizuku_install_button,
                    ),
                    buttonEnabled = !installed,
                    onButton = ::viewShizukuInMarket,
                ),
                SetupStep(
                    icon = if (running) R.drawable.ic_twotone_check_circle_24dp else R.drawable.ic_numeric_2_circle_outline,
                    body = getString(R.string.onboarding_adb_shizuku_open_instruction),
                    button = getString(
                        if (running) R.string.onboarding_adb_shizuku_open_button_done
                        else R.string.onboarding_adb_shizuku_open_button,
                    ),
                    buttonEnabled = !running,
                    onButton = { if (!launchApp(SHIZUKU_PKG)) requestShizukuInstallation() },
                ),
                SetupStep(
                    icon = R.drawable.ic_numeric_3_circle_outline,
                    body = getString(R.string.onboarding_adb_shizuku_grant_instruction),
                    button = getString(R.string.onboarding_adb_shizuku_grant_button),
                    onButton = ::grantViaShizuku,
                ),
            )
        }
        return getString(R.string.onboarding_adb_adb_title) to listOf(
            SetupStep(
                icon = R.drawable.ic_numeric_1_circle_outline,
                body = getString(R.string.onboarding_adb_manual_step1),
                button = getString(R.string.onboarding_adb_manual_step1_button),
                onButton = {
                    try {
                        startActivity(Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))
                    } catch (_: ActivityNotFoundException) {
                        toast(R.string.no_activity_found)
                    }
                },
            ),
            SetupStep(R.drawable.ic_numeric_2_circle_outline, getString(R.string.onboarding_adb_manual_step2)),
            SetupStep(R.drawable.ic_numeric_3_circle_outline, getString(R.string.onboarding_adb_manual_step3)),
            SetupStep(R.drawable.ic_numeric_4_circle_outline, getString(R.string.onboarding_adb_manual_step4, packageName)),
            SetupStep(R.drawable.ic_numeric_5_circle_outline, getString(R.string.onboarding_adb_manual_step5b_required, packageName)),
            SetupStep(R.drawable.ic_twotone_info_24dp, getString(R.string.onboarding_adb_manual_step5c, packageName)),
            SetupStep(R.drawable.ic_numeric_6_circle_outline, getString(R.string.onboarding_adb_manual_step5)),
        )
    }

    private fun changePage(forward: Boolean, ignoreConditions: Boolean = false) {
        val next = nextPage(if (forward) page + 1 else page - 1, forward)
        if (next < firstPage) {
            // Back from the first page of a re-run setup leaves the wizard.
            if (redoAdbSetup) finish()
            return
        }
        if (forward && !ignoreConditions && !canLeave(page)) {
            Timber.d("Next page not ready; instructions not yet fulfilled by the user")
            return
        }
        if (next > lastPage) {
            finishSetup()
            return
        }
        page = next
    }

    /** [candidate], or the next page in the direction of travel that still has work to do. */
    private fun nextPage(candidate: Int, forward: Boolean): Int {
        val skip = when (candidate) {
            OnboardingPages.METHOD_SELECT, OnboardingPages.ADB_SETUP -> areAdbPermissionsGranted() && !redoAdbSetup
            OnboardingPages.RUNTIME_PERMISSIONS -> hasNotificationPermission() && hasRecordPermission()
            else -> false
        }
        return if (skip) nextPage(if (forward) candidate + 1 else candidate - 1, forward) else candidate
    }

    private fun canLeave(current: Int) = when (current) {
        OnboardingPages.ADB_SETUP -> ensureAdbPermissions()
        OnboardingPages.RUNTIME_PERMISSIONS -> ensureRuntimePermissions()
        else -> true
    }

    // Android 15+ doesn't allow keyguard recording without PROJECT_MEDIA.
    private fun areAdbPermissionsGranted() = hasDumpPermission() && hasProjectMediaAppOp()

    private fun ensureAdbPermissions(): Boolean {
        // When the setup is re-run with Shizuku, grant again regardless: it also grants the
        // optional permissions.
        if (areAdbPermissionsGranted() && (!redoAdbSetup || method != SetupMethod.Shizuku)) {
            return true
        }

        if (shizukuAlive && Shizuku.checkSelfPermission() == PERMISSION_GRANTED) {
            grantAsShell()
            if (areAdbPermissionsGranted()) return true
            Timber.e("ADB permissions not granted")
            prompt = Prompt(R.string.onboarding_adb_shizuku_no_dump_perm_title, R.string.onboarding_adb_shizuku_no_dump_perm)
            return false
        }

        prompt = when {
            !hasDumpPermission() -> Prompt(R.string.onboarding_adb_not_granted_title, R.string.onboarding_adb_dump_permission_not_granted)
            !hasProjectMediaAppOp() -> Prompt(R.string.onboarding_adb_not_granted_title, R.string.onboarding_adb_project_media_not_granted)
            else -> null
        }
        return false
    }

    /** DUMP, SYSTEM_ALERT_WINDOW and the PROJECT_MEDIA app op, granted as the shell through Shizuku. */
    private fun grantAsShell() {
        val pkg = packageName
        val uid = myUid()
        Timber.d("Granting DUMP via Shizuku (uid ${Shizuku.getUid()}) for $pkg")
        ShizukuSystemServerApi.PermissionManager_grantRuntimePermission(pkg, Manifest.permission.DUMP, UserHandle.USER_SYSTEM)
        try {
            ShizukuSystemServerApi.PermissionManager_grantRuntimePermission(
                pkg, Manifest.permission.SYSTEM_ALERT_WINDOW, UserHandle.USER_SYSTEM,
            )
        } catch (ex: Exception) {
            Timber.e(ex)
        }
        try {
            ShizukuSystemServerApi.AppOpsService_setMode(
                ShizukuSystemServerApi.APP_OPS_OP_SYSTEM_ALERT_WINDOW, uid, pkg, ShizukuSystemServerApi.APP_OPS_MODE_ALLOW,
            )
        } catch (ex: Exception) {
            Timber.e(ex, "AppOpsService_setMode for SYSTEM_ALERT_WINDOW failed")
            ShizukuSystemServerApi.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        }
        try {
            ShizukuSystemServerApi.AppOpsService_setMode(
                ShizukuSystemServerApi.APP_OPS_OP_PROJECT_MEDIA, uid, pkg, ShizukuSystemServerApi.APP_OPS_MODE_ALLOW,
            )
        } catch (ex: Exception) {
            Timber.e(ex, "AppOpsService_setMode for PROJECT_MEDIA failed")
            ShizukuSystemServerApi.exec("appops set $pkg PROJECT_MEDIA allow")
        }
    }

    private fun ensureRuntimePermissions(): Boolean {
        val requested = mutableListOf<String>()
        if (!hasRecordPermission()) requested.add(Manifest.permission.RECORD_AUDIO)
        sdkAbove(Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requested.isEmpty()) return true
        runtimePermissionLauncher.launch(requested.toTypedArray())
        return false
    }

    private fun grantViaShizuku() {
        when {
            !isPackageInstalled(SHIZUKU_PKG) -> requestShizukuInstallation()
            !shizukuAlive -> prompt = Prompt(
                R.string.onboarding_adb_shizuku_grant_fail_server_dead_title,
                R.string.onboarding_adb_shizuku_grant_fail_server_dead,
            )
            Shizuku.isPreV11() -> prompt = Prompt(
                R.string.onboarding_adb_shizuku_grant_fail_version_title,
                R.string.onboarding_adb_shizuku_grant_fail_version,
                confirm = R.string.update,
                onConfirm = ::viewShizukuInMarket,
                dismiss = android.R.string.ok,
            )
            checkShizukuPermission() -> changePage(true)
        }
    }

    /** True when Shizuku already granted us; otherwise asks (the answer arrives in [permissionResultListener]). */
    private fun checkShizukuPermission(): Boolean {
        try {
            when {
                Shizuku.checkSelfPermission() == PERMISSION_GRANTED -> return true
                Shizuku.shouldShowRequestPermissionRationale() -> showShizukuDenied()
                else -> Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_GRANT)
            }
        } catch (e: Throwable) {
            Timber.e(e)
            showShizukuDenied()
        }
        return false
    }

    private fun showShizukuDenied() {
        prompt = Prompt(R.string.onboarding_adb_shizuku_grant_fail_denied_title, R.string.onboarding_adb_shizuku_grant_fail_denied)
    }

    private fun requestShizukuInstallation() {
        prompt = Prompt(
            R.string.onboarding_adb_shizuku_not_installed_title,
            R.string.onboarding_adb_shizuku_not_installed,
            confirm = R.string.install,
            onConfirm = ::viewShizukuInMarket,
            dismiss = android.R.string.cancel,
        )
    }

    private fun viewShizukuInMarket() = openPlayStoreApp(SHIZUKU_PKG)

    private fun finishSetup() {
        prefsVar.set(R.string.key_first_boot, false)
        if (!redoAdbSetup) {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FORCE_SHOW_CAPTURE_PROMPT, true))
        }
        finish()
    }

    companion object {
        const val EXTRA_FIX_PERMS = "FixPermissions"
        const val EXTRA_ROOTLESS_REDO_ADB_SETUP = "RootlessRedoAdbSetup"

        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE_SHIZUKU_GRANT = 1
        private const val STATE_PAGE = "currentPage"
        private const val STATE_METHOD = "setupMethod"
    }
}
