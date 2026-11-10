package app.siphondsp.compose.onboarding

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Process.myUid
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.R
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

const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
private const val REQUEST_CODE_SHIZUKU_GRANT = 1

/** A dialog the wizard is showing. */
private data class Prompt(
    val title: Int,
    val message: Int,
    val confirm: Int = android.R.string.ok,
    val onConfirm: (() -> Unit)? = null,
    val dismiss: Int? = null,
)

/**
 * The setup wizard's logic: what the app can't do, then granting DUMP / PROJECT_MEDIA (through
 * Shizuku or by hand over ADB), then the runtime permissions. With [redoAdbSetup] only the method
 * choice and the ADB/Shizuku steps run (Settings -> Misc -> "Restart setup wizard").
 */
private class OnboardingController(
    private val activity: Activity,
    private val redoAdbSetup: Boolean,
    pageState: MutableIntState,
    methodState: MutableState<SetupMethod>,
    private val requestRuntimePermissions: (Array<String>) -> Unit,
    private val onExit: () -> Unit,
    private val onFinished: () -> Unit,
) {
    var page by pageState
    var method by methodState
    var shizukuAlive by mutableStateOf(false)
    var prompt by mutableStateOf<Prompt?>(null)

    /** Bumped on resume, so the Shizuku "installed" checks are read again. */
    var resumeCount by mutableIntStateOf(0)

    val lastPage get() = if (redoAdbSetup) OnboardingPages.ADB_SETUP else OnboardingPages.READY
    val firstPage get() = if (redoAdbSetup) OnboardingPages.METHOD_SELECT else OnboardingPages.WELCOME

    val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Timber.d("Shizuku binder received")
        shizukuAlive = true
    }
    val binderDeadListener = Shizuku.OnBinderDeadListener {
        Timber.d("Shizuku binder died")
        shizukuAlive = false
    }
    val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (result == PERMISSION_GRANTED) {
            if (requestCode == REQUEST_CODE_SHIZUKU_GRANT) changePage(true)
        } else {
            showShizukuDenied()
        }
    }

    fun onRuntimePermissionsResult(granted: Map<String, Boolean>) {
        if (granted.all { it.value }) {
            if (page == OnboardingPages.RUNTIME_PERMISSIONS) changePage(forward = true, ignoreConditions = true)
        } else {
            prompt = Prompt(R.string.onboarding_perm_missing_title, R.string.onboarding_perm_missing)
        }
    }

    fun onBack() {
        if (page == firstPage) onExit() else changePage(false)
    }

    /** Reads [resumeCount], so it is rebuilt after a resume (the "installed" checks). */
    fun buildUi(): OnboardingUi {
        @Suppress("UNUSED_VARIABLE") val resumed = resumeCount
        val (title, steps) = setupInstructions()
        return OnboardingUi(
            page = page,
            // A re-run setup's first page can still go back: that leaves the wizard.
            isFirstPage = page == firstPage && !redoAdbSetup,
            isLastPage = page == lastPage,
            shizukuSupported = SdkCheck.isQ,
            showNotificationPermission = SdkCheck.isTiramisu,
            setupTitle = title,
            setupSteps = steps,
        )
    }

    private fun setupInstructions(): Pair<String, List<SetupStep>> {
        if (method == SetupMethod.Shizuku) {
            val installed = activity.isPackageInstalled(SHIZUKU_PKG)
            val running = shizukuAlive && installed
            return activity.getString(R.string.onboarding_adb_shizuku_title) to listOf(
                SetupStep(
                    icon = if (installed) R.drawable.ic_twotone_check_circle_24dp else R.drawable.ic_numeric_1_circle_outline,
                    body = activity.getString(R.string.onboarding_adb_shizuku_install_instruction),
                    button = activity.getString(
                        if (installed) R.string.onboarding_adb_shizuku_install_button_done
                        else R.string.onboarding_adb_shizuku_install_button,
                    ),
                    buttonEnabled = !installed,
                    onButton = ::viewShizukuInMarket,
                ),
                SetupStep(
                    icon = if (running) R.drawable.ic_twotone_check_circle_24dp else R.drawable.ic_numeric_2_circle_outline,
                    body = activity.getString(R.string.onboarding_adb_shizuku_open_instruction),
                    button = activity.getString(
                        if (running) R.string.onboarding_adb_shizuku_open_button_done
                        else R.string.onboarding_adb_shizuku_open_button,
                    ),
                    buttonEnabled = !running,
                    onButton = { if (!activity.launchApp(SHIZUKU_PKG)) requestShizukuInstallation() },
                ),
                SetupStep(
                    icon = R.drawable.ic_numeric_3_circle_outline,
                    body = activity.getString(R.string.onboarding_adb_shizuku_grant_instruction),
                    button = activity.getString(R.string.onboarding_adb_shizuku_grant_button),
                    onButton = ::grantViaShizuku,
                ),
            )
        }
        return activity.getString(R.string.onboarding_adb_adb_title) to listOf(
            SetupStep(
                icon = R.drawable.ic_numeric_1_circle_outline,
                body = activity.getString(R.string.onboarding_adb_manual_step1),
                button = activity.getString(R.string.onboarding_adb_manual_step1_button),
                onButton = {
                    try {
                        activity.startActivity(Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))
                    } catch (_: ActivityNotFoundException) {
                        activity.toast(R.string.no_activity_found)
                    }
                },
            ),
            SetupStep(R.drawable.ic_numeric_2_circle_outline, activity.getString(R.string.onboarding_adb_manual_step2)),
            SetupStep(R.drawable.ic_numeric_3_circle_outline, activity.getString(R.string.onboarding_adb_manual_step3)),
            SetupStep(R.drawable.ic_numeric_4_circle_outline, activity.getString(R.string.onboarding_adb_manual_step4, activity.packageName)),
            SetupStep(R.drawable.ic_numeric_5_circle_outline, activity.getString(R.string.onboarding_adb_manual_step5b_required, activity.packageName)),
            SetupStep(R.drawable.ic_twotone_info_24dp, activity.getString(R.string.onboarding_adb_manual_step5c, activity.packageName)),
            SetupStep(R.drawable.ic_numeric_6_circle_outline, activity.getString(R.string.onboarding_adb_manual_step5)),
        )
    }

    fun changePage(forward: Boolean, ignoreConditions: Boolean = false) {
        val next = nextPage(if (forward) page + 1 else page - 1, forward)
        if (next < firstPage) {
            // Back from the first page of a re-run setup leaves the wizard.
            if (redoAdbSetup) onExit()
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
            OnboardingPages.RUNTIME_PERMISSIONS -> activity.hasNotificationPermission() && activity.hasRecordPermission()
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
    private fun areAdbPermissionsGranted() = activity.hasDumpPermission() && activity.hasProjectMediaAppOp()

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
            !activity.hasDumpPermission() -> Prompt(R.string.onboarding_adb_not_granted_title, R.string.onboarding_adb_dump_permission_not_granted)
            !activity.hasProjectMediaAppOp() -> Prompt(R.string.onboarding_adb_not_granted_title, R.string.onboarding_adb_project_media_not_granted)
            else -> null
        }
        return false
    }

    /** DUMP, SYSTEM_ALERT_WINDOW and the PROJECT_MEDIA app op, granted as the shell through Shizuku. */
    private fun grantAsShell() {
        val pkg = activity.packageName
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
        if (!activity.hasRecordPermission()) requested.add(Manifest.permission.RECORD_AUDIO)
        sdkAbove(Build.VERSION_CODES.TIRAMISU) {
            if (!activity.hasNotificationPermission()) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requested.isEmpty()) return true
        requestRuntimePermissions(requested.toTypedArray())
        return false
    }

    private fun grantViaShizuku() {
        when {
            !activity.isPackageInstalled(SHIZUKU_PKG) -> requestShizukuInstallation()
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

    private fun viewShizukuInMarket() = activity.openPlayStoreApp(SHIZUKU_PKG)

    private fun finishSetup() = onFinished()
}

/**
 * The setup wizard as a route. [onFinished] runs once the last page is passed; [onExit] when it
 * is left from its first page.
 */
@Composable
fun OnboardingRoute(fixPerms: Boolean, redoAdbSetup: Boolean, onExit: () -> Unit, onFinished: () -> Unit) {
    val activity = LocalContext.current as Activity
    val pageState = rememberSaveable {
        mutableIntStateOf(if (redoAdbSetup) OnboardingPages.METHOD_SELECT else OnboardingPages.WELCOME)
    }
    val methodState = rememberSaveable { mutableStateOf(SetupMethod.None) }
    val exit by rememberUpdatedState(onExit)
    val finished by rememberUpdatedState(onFinished)

    var permissionsResult: (Map<String, Boolean>) -> Unit = {}
    val runtimePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsResult(it)
    }
    val controller = remember(activity, redoAdbSetup) {
        OnboardingController(
            activity = activity,
            redoAdbSetup = redoAdbSetup,
            pageState = pageState,
            methodState = methodState,
            requestRuntimePermissions = { runtimePermissionLauncher.launch(it) },
            onExit = { exit() },
            onFinished = { finished() },
        )
    }
    permissionsResult = controller::onRuntimePermissionsResult

    // Shown once per visit, not again after a recreation.
    val showFixPrompt = rememberSaveable { mutableStateOf(fixPerms) }
    LaunchedEffect(controller) {
        if (showFixPrompt.value) {
            showFixPrompt.value = false
            controller.prompt = Prompt(R.string.onboarding_fix_permissions_title, R.string.onboarding_fix_permissions)
        }
    }

    DisposableEffect(controller) {
        Shizuku.addBinderReceivedListenerSticky(controller.binderReceivedListener)
        Shizuku.addBinderDeadListener(controller.binderDeadListener)
        Shizuku.addRequestPermissionResultListener(controller.permissionResultListener)
        onDispose {
            Shizuku.removeBinderReceivedListener(controller.binderReceivedListener)
            Shizuku.removeBinderDeadListener(controller.binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(controller.permissionResultListener)
        }
    }
    LifecycleResumeEffect(controller) {
        controller.resumeCount++
        onPauseOrDispose {}
    }
    BackHandler(onBack = controller::onBack)

    OnboardingScreen(
        ui = controller.buildUi(),
        onBack = controller::onBack,
        onNext = { controller.changePage(true) },
        onChooseMethod = {
            controller.method = it
            controller.changePage(true)
        },
    )
    controller.prompt?.let { shown ->
        AlertDialog(
            onDismissRequest = { controller.prompt = null },
            title = { Text(stringResource(shown.title)) },
            text = { Text(stringResource(shown.message)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.prompt = null
                    shown.onConfirm?.invoke()
                }) { Text(stringResource(shown.confirm)) }
            },
            dismissButton = if (shown.dismiss != null) {
                { TextButton(onClick = { controller.prompt = null }) { Text(stringResource(shown.dismiss)) } }
            } else {
                null
            },
        )
    }
}
