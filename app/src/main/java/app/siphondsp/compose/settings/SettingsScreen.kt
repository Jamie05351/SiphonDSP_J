package app.siphondsp.compose.settings

import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.BuildConfig
import app.siphondsp.R
import app.siphondsp.model.Translator
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import app.siphondsp.utils.preferences.Preferences
import com.hippo.unifile.UniFile
import java.util.Locale

/** The pages of the Settings screen, in the order the root lists them. */
enum class SettingsPage(@StringRes val title: Int) {
    Root(R.string.title_activity_settings),
    AudioFormat(R.string.audio_format_header),
    Troubleshooting(R.string.troubleshooting),
    Limitations(R.string.troubleshooting_view_limitations),
    Backup(R.string.backup_settings),
    Misc(R.string.misc_settings),
    About(R.string.about_settings),
}

/** What the Settings pages ask their activity to do (pickers, intents, services). */
interface SettingsActions {
    fun onBufferOrEncodingChanged()
    fun pickBackupLocation()
    fun createBackup()
    fun pickRestoreFile()
    fun restore(uri: Uri, dirty: Boolean)
    fun onBackupFrequencyChanged(hours: Int)
    fun restartSetup()
    fun shareDump()
    fun openNotificationAccess()
    fun openUrl(url: String)
    fun toast(@StringRes message: Int)
}

/**
 * The whole Settings screen. Pages are plain state here; the one-Activity navigation graph
 * replaces this in a later step.
 *
 * @param pendingRestore a backup file the user just picked; the restore-mode question is asked
 *        for it, then [onRestoreHandled] clears it.
 */
@Composable
fun SettingsScreen(
    prefs: Preferences.App,
    actions: SettingsActions,
    pendingRestore: Uri?,
    onRestoreHandled: () -> Unit,
    onExit: () -> Unit,
) {
    var stack by rememberSaveable { mutableStateOf(listOf(SettingsPage.Root)) }
    val page = stack.last()
    val back: () -> Unit = {
        if (stack.size > 1) {
            stack = stack.dropLast(1)
        } else {
            onExit()
        }
    }
    BackHandler(onBack = back)
    val open: (SettingsPage) -> Unit = { next -> stack = stack + next }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            slideInHorizontally { if (forward) it else -it } togetherWith
                slideOutHorizontally { if (forward) -it else it }
        },
        label = "settingsPage",
    ) { shown ->
        AppScreen(
            title = stringResource(shown.title),
            onBack = back,
        ) {
            when (shown) {
                SettingsPage.Root -> RootPage(open)
                SettingsPage.AudioFormat -> AudioFormatPage(prefs, actions)
                SettingsPage.Troubleshooting -> TroubleshootingPage(prefs, actions, open)
                SettingsPage.Limitations -> SettingsColumn { LimitationsList() }
                SettingsPage.Backup -> BackupPage(prefs, actions)
                SettingsPage.Misc -> MiscPage(prefs, actions)
                SettingsPage.About -> AboutPage(actions)
            }
        }
    }

    if (pendingRestore != null) {
        AlertDialog(
            onDismissRequest = onRestoreHandled,
            title = { Text(stringResource(R.string.backup_restore_mode_title)) },
            text = {
                Column {
                    TextButton(onClick = { actions.restore(pendingRestore, dirty = false); onRestoreHandled() }) {
                        Text(stringResource(R.string.backup_restore_mode_clean))
                    }
                    TextButton(onClick = { actions.restore(pendingRestore, dirty = true); onRestoreHandled() }) {
                        Text(stringResource(R.string.backup_restore_mode_dirty))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onRestoreHandled) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun RootPage(open: (SettingsPage) -> Unit) = SettingsColumn {
    SettingsItem(
        title = stringResource(R.string.audio_format_header),
        summary = stringResource(R.string.audio_format_summary),
        icon = R.drawable.ic_twotone_volume_up_24dp,
        onClick = { open(SettingsPage.AudioFormat) },
    )
    SettingsItem(
        title = stringResource(R.string.troubleshooting),
        summary = stringResource(R.string.troubleshooting_summary),
        icon = R.drawable.ic_twotone_troubleshoot_24dp,
        onClick = { open(SettingsPage.Troubleshooting) },
    )
    SettingsItem(
        title = stringResource(R.string.backup_settings),
        summary = stringResource(R.string.backup_settings_summary),
        icon = R.drawable.ic_twotone_settings_backup_restore_24dp,
        onClick = { open(SettingsPage.Backup) },
    )
    SettingsItem(
        title = stringResource(R.string.misc_settings),
        summary = stringResource(R.string.misc_settings_summary),
        icon = R.drawable.ic_twotone_more_horiz_24,
        onClick = { open(SettingsPage.Misc) },
    )
    SettingsItem(
        title = stringResource(R.string.about_settings),
        summary = stringResource(R.string.about_settings_summary),
        icon = R.drawable.ic_twotone_help_24dp,
        onClick = { open(SettingsPage.About) },
    )
}

@Composable
private fun AudioFormatPage(prefs: Preferences.App, actions: SettingsActions) = SettingsColumn {
    val encoding by prefs.collectAsState<String>(R.string.key_audioformat_encoding)
    val bufferSize by prefs.collectAsState<Float>(R.string.key_audioformat_buffersize)

    SettingsCategory(stringResource(R.string.audio_format_section_header))
    SettingsChoiceItem(
        title = stringResource(R.string.audio_format_encoding),
        entries = stringArrayResource(R.array.audio_format_encodings).toList(),
        values = stringArrayResource(R.array.audio_format_encodings_values).toList(),
        selected = encoding,
        onSelect = {
            if (it != encoding) {
                prefs.set(R.string.key_audioformat_encoding, it)
                actions.onBufferOrEncodingChanged()
            }
        },
    )
    SettingsSliderItem(
        title = stringResource(R.string.audio_format_buffer_size),
        value = bufferSize,
        range = 128f..16384f,
        step = 128f,
        unit = stringResource(R.string.audio_format_buffer_size_unit).trim(),
        onCommit = {
            if (it != bufferSize) {
                if (it <= 1024f) actions.toast(R.string.audio_format_buffer_size_warning_low_value)
                prefs.set(R.string.key_audioformat_buffersize, it)
                actions.onBufferOrEncodingChanged()
            }
        },
    )
}

@Composable
private fun TroubleshootingPage(prefs: Preferences.App, actions: SettingsActions, open: (SettingsPage) -> Unit) = SettingsColumn {
    val messages = rememberMessageDialogState()
    val powersave by prefs.collectAsState<Boolean>(R.string.key_powersave_suspend)
    val method by prefs.collectAsState<String>(R.string.key_session_detection_method)
    val polling by prefs.collectAsState<Boolean>(R.string.key_session_continuous_polling)
    val pollingRate by prefs.collectAsState<String>(R.string.key_session_continuous_polling_rate)
    val lossIgnore by prefs.collectAsState<Boolean>(R.string.key_session_loss_ignore)
    val appProblemIgnore by prefs.collectAsState<Boolean>(R.string.key_session_app_problem_ignore)
    val excludeRestricted by prefs.collectAsState<Boolean>(R.string.key_session_exclude_restricted)
    val warning = stringResource(R.string.warning)
    val lossWarning = stringResource(R.string.session_loss_ignore_warning)

    SettingsCategory(stringResource(R.string.troubleshooting_docs))
    SettingsItem(
        title = stringResource(R.string.troubleshooting_view_limitations),
        summary = stringResource(R.string.troubleshooting_view_limitations_summary),
        onClick = { open(SettingsPage.Limitations) },
    )

    SettingsCategory(stringResource(R.string.troubleshooting_actions))
    SettingsItem(
        title = stringResource(R.string.troubleshooting_notification_access),
        summary = stringResource(R.string.troubleshooting_notification_access_summary),
        onClick = actions::openNotificationAccess,
    )
    SettingsItem(
        title = stringResource(R.string.troubleshooting_dump),
        summary = stringResource(R.string.troubleshooting_dump_summary),
        onClick = actions::shareDump,
    )

    SettingsCategory(stringResource(R.string.powersave))
    SettingsSwitchItem(
        title = stringResource(R.string.powersave_suspend),
        checked = powersave,
        onCheckedChange = { prefs.set(R.string.key_powersave_suspend, it) },
        summaryOn = stringResource(R.string.powersave_suspend_on),
        summaryOff = stringResource(R.string.powersave_suspend_off),
    )

    SettingsCategory(stringResource(R.string.session_detection_header))
    SettingsChoiceItem(
        title = stringResource(R.string.session_detection_method),
        entries = stringArrayResource(R.array.session_detection_methods).toList(),
        values = stringArrayResource(R.array.session_detection_methods_values).toList(),
        selected = method,
        onSelect = { prefs.set(R.string.key_session_detection_method, it) },
    )
    SettingsSwitchItem(
        title = stringResource(R.string.session_continuous_polling),
        checked = polling,
        onCheckedChange = { prefs.set(R.string.key_session_continuous_polling, it) },
        summaryOn = stringResource(R.string.session_continuous_polling_on),
        summaryOff = stringResource(R.string.session_continuous_polling_off),
    )
    SettingsNumberItem(
        title = stringResource(R.string.session_continuous_polling_rate),
        value = pollingRate,
        enabled = polling,
        onCommit = { prefs.set(R.string.key_session_continuous_polling_rate, it) },
    )
    SettingsSwitchItem(
        title = stringResource(R.string.session_loss_ignore),
        checked = lossIgnore,
        onCheckedChange = {
            prefs.set(R.string.key_session_loss_ignore, it)
            if (it) messages.show(warning, lossWarning)
        },
        summaryOn = stringResource(R.string.session_loss_ignore_on),
        summaryOff = stringResource(R.string.session_loss_ignore_off),
    )
    SettingsSwitchItem(
        title = stringResource(R.string.session_app_problem_ignore),
        checked = appProblemIgnore,
        onCheckedChange = { prefs.set(R.string.key_session_app_problem_ignore, it) },
        summaryOn = stringResource(R.string.session_loss_ignore_on),
        summaryOff = stringResource(R.string.session_loss_ignore_off),
    )
    SettingsSwitchItem(
        title = stringResource(R.string.session_exclude_restricted),
        checked = excludeRestricted,
        onCheckedChange = { prefs.set(R.string.key_session_exclude_restricted, it) },
        summaryOn = stringResource(R.string.session_exclude_restricted_on),
        summaryOff = stringResource(R.string.session_exclude_restricted_off),
    )
    messages.Render()
}

@Composable
private fun BackupPage(prefs: Preferences.App, actions: SettingsActions) = SettingsColumn {
    val context = LocalContext.current
    val frequency by prefs.collectAsState<String>(R.string.key_backup_frequency)
    val location by prefs.collectAsState<String>(R.string.key_backup_location)
    val maximum by prefs.collectAsState<String>(R.string.key_backup_maximum)
    val locationSummary = remember(location) { backupLocationSummary(context, location) }

    SettingsCategory(stringResource(R.string.backup_manual_backup))
    SettingsItem(
        title = stringResource(R.string.backup_create),
        summary = stringResource(R.string.backup_create_summary),
        onClick = actions::createBackup,
    )
    SettingsItem(
        title = stringResource(R.string.backup_restore),
        summary = stringResource(R.string.backup_restore_summary),
        onClick = actions::pickRestoreFile,
    )

    SettingsCategory(stringResource(R.string.backup_automatic_backup))
    SettingsChoiceItem(
        title = stringResource(R.string.backup_frequency),
        entries = stringArrayResource(R.array.backup_frequency).toList(),
        values = stringArrayResource(R.array.backup_frequency_values).toList(),
        selected = frequency,
        onSelect = {
            prefs.set(R.string.key_backup_frequency, it)
            actions.onBackupFrequencyChanged(it.toIntOrNull() ?: 0)
        },
    )
    SettingsItem(
        title = stringResource(R.string.backup_location),
        summary = locationSummary ?: stringResource(R.string.value_not_set),
        onClick = actions::pickBackupLocation,
    )
    val maxima = stringArrayResource(R.array.backup_maximum).toList()
    SettingsChoiceItem(
        title = stringResource(R.string.backup_maximum),
        entries = maxima,
        values = maxima,
        selected = maximum,
        onSelect = { prefs.set(R.string.key_backup_maximum, it) },
    )
    SettingsItem(
        title = stringResource(R.string.backup_compat_info),
        icon = R.drawable.ic_twotone_info_24dp,
    )
}

private fun backupLocationSummary(context: Context, location: String): String? =
    if (location.isBlank()) null
    else runCatching { UniFile.fromUri(context, location.toUri())?.filePath?.let { "$it/automatic" } }.getOrNull()

@Composable
private fun MiscPage(prefs: Preferences.App, actions: SettingsActions) = SettingsColumn {
    val context = LocalContext.current
    val excludeRecents by prefs.collectAsState<Boolean>(R.string.key_exclude_app_from_recents)
    val autostart by prefs.collectAsState<Boolean>(R.string.key_autostart_prompt_at_boot)

    // Read again whenever the screen comes back: the user grants these in system settings.
    var allowSkipPrompt by remember { mutableStateOf(false) }
    var allowAutoStart by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        allowSkipPrompt = context.hasProjectMediaAppOp()
        allowAutoStart = allowSkipPrompt && Settings.canDrawOverlays(context)
        onPauseOrDispose {}
    }

    SettingsCategory(stringResource(R.string.app_behavior))
    SettingsSwitchItem(
        title = stringResource(R.string.exclude_app_from_recents),
        checked = excludeRecents,
        onCheckedChange = { prefs.set(R.string.key_exclude_app_from_recents, it) },
        summaryOn = stringResource(R.string.exclude_app_from_recents_on),
        summaryOff = stringResource(R.string.exclude_app_from_recents_off),
    )
    SettingsSwitchItem(
        title = stringResource(if (allowAutoStart) R.string.autostart_service_at_boot else R.string.autostart_prompt_at_boot),
        checked = autostart,
        onCheckedChange = { prefs.set(R.string.key_autostart_prompt_at_boot, it) },
        summaryOn = stringResource(if (allowAutoStart) R.string.autostart_service_at_boot_on else R.string.autostart_prompt_at_boot_on),
        summaryOff = stringResource(if (allowAutoStart) R.string.autostart_service_at_boot_off else R.string.autostart_prompt_at_boot_off),
    )

    SettingsCategory(stringResource(R.string.misc_permission_header))
    PermissionRow(stringResource(R.string.misc_permission_skip_prompt), allowSkipPrompt)
    PermissionRow(stringResource(R.string.misc_permission_auto_start), allowAutoStart)
    if (!allowSkipPrompt || !allowAutoStart) {
        SettingsItem(
            title = stringResource(R.string.misc_permission_restart_setup),
            summary = stringResource(R.string.misc_permission_restart_setup_summary),
            onClick = actions::restartSetup,
        )
    }
}

@Composable
private fun PermissionRow(title: String, allowed: Boolean) {
    SettingsItem(
        title = title,
        summary = stringResource(if (allowed) R.string.permission_allowed else R.string.permission_not_allowed),
        icon = if (allowed) R.drawable.ic_twotone_check_circle_24dp else R.drawable.ic_twotone_warning_24dp,
    )
}

@Composable
private fun AboutPage(actions: SettingsActions) = SettingsColumn {
    val context = LocalContext.current
    val translators = remember { Translator.readLanguageMap(context) }
    var chooseTranslator by remember { mutableStateOf<Pair<String, List<Translator>>?>(null) }

    SettingsItem(
        title = stringResource(R.string.credits_version),
        summary = BuildConfig.VERSION_NAME,
        icon = R.drawable.ic_twotone_history_24dp,
    )
    if (BuildConfig.DEBUG || BuildConfig.PREVIEW) {
        val type = if (BuildConfig.PREVIEW) "Preview" else "Debug"
        SettingsItem(
            title = stringResource(R.string.credits_build_info),
            summary = "$type build @${BuildConfig.COMMIT_SHA} (compiled at ${BuildConfig.BUILD_TIME})",
            icon = R.drawable.ic_twotone_info_24dp,
        )
    }
    SettingsItem(
        title = stringResource(R.string.credits_project_page),
        summary = stringResource(R.string.credits_project_page_summary),
        icon = R.drawable.ic_baseline_github_24dp,
        onClick = { actions.openUrl(PROJECT_URL) },
    )

    SettingsCategory(stringResource(R.string.credits))
    SettingsItem(
        title = stringResource(R.string.credits_capture),
        summary = "Tim Schneeberger (@thepbone)",
        onClick = { actions.openUrl("https://github.com/thepbone") },
    )
    SettingsItem(
        title = stringResource(R.string.credits_dsp_origin),
        summary = "James Fung (@james34602)",
        onClick = { actions.openUrl("https://github.com/james34602") },
    )

    if (translators.isNotEmpty()) {
        SettingsCategory(stringResource(R.string.translators))
        val locale = context.resources.configuration.locales[0]
        translators.forEach { (cc, people) ->
            val language = Locale.forLanguageTag(cc).getDisplayLanguage(locale)
            val region = Locale.forLanguageTag(cc).getDisplayCountry(locale)
            val title = if (region.isNullOrBlank()) language else "$language ($region)"
            SettingsItem(
                title = title,
                summary = people.joinToString(", ") { it.name },
                onClick = {
                    if (people.size == 1) {
                        actions.openUrl(crowdinProfile(people[0]))
                    } else {
                        chooseTranslator = title to people
                    }
                },
            )
        }
    }

    chooseTranslator?.let { (title, people) ->
        AlertDialog(
            onDismissRequest = { chooseTranslator = null },
            title = { Text(title) },
            text = {
                Column {
                    people.forEach { person ->
                        TextButton(onClick = {
                            chooseTranslator = null
                            actions.openUrl(crowdinProfile(person))
                        }) { Text(person.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseTranslator = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

private fun crowdinProfile(person: Translator) = "https://crowdin.com/profile/${person.username}"

private const val PROJECT_URL = "https://github.com/Jamie05351/SiphonDSP_J"
