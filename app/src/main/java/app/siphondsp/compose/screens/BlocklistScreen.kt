package app.siphondsp.compose.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.siphondsp.R
import app.siphondsp.compose.controls.CarUi
import app.siphondsp.compose.settings.AppScreen
import app.siphondsp.compose.settings.InfoCard
import app.siphondsp.compose.settings.WarningCardBackground
import app.siphondsp.model.AppInfo
import app.siphondsp.model.room.BlockedApp

private val SummaryColor = Color(0xFFC6C5CE)

/**
 * The capture blocklist: excluded apps, add (from the installed apps) and remove.
 *
 * @param blocked the excluded apps, sorted; null while the database is still loading.
 * @param installedApps every installed app for the picker; null while they're being read.
 * @param restrictedApps apps that block audio capture themselves (shown as a notice).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocklistScreen(
    blocked: List<BlockedApp>?,
    installedApps: List<AppInfo>?,
    restrictedApps: List<String>,
    onOpenPicker: () -> Unit,
    onAdd: (AppInfo) -> Unit,
    onRemove: (BlockedApp) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<BlockedApp?>(null) }
    var showRestricted by remember { mutableStateOf(false) }

    AppScreen(
        title = stringResource(R.string.title_activity_blocklist),
        onBack = onBack,
        actions = {
            IconButton(onClick = { picking = true }) {
                Icon(
                    painterResource(R.drawable.ic_twotone_add_24dp),
                    contentDescription = stringResource(R.string.blocklist_add_exclusion_alt),
                    tint = Color.White,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (restrictedApps.isNotEmpty()) {
                InfoCard(
                    title = null,
                    body = pluralStringResource(R.plurals.unsupported_apps, restrictedApps.size, restrictedApps.size),
                    icon = R.drawable.ic_twotone_info_24dp,
                    background = WarningCardBackground,
                    onClick = { showRestricted = true },
                )
            }
            when {
                blocked == null -> Unit
                blocked.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.blocklist_no_exclusions), color = SummaryColor, fontSize = CarUi.MinText)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(blocked, key = { it.packageName }) { app ->
                        AppRow(
                            icon = app.appIcon,
                            title = app.appName ?: app.packageName,
                            summary = "${app.packageName} (UID: ${app.uid})",
                            onClick = { removing = app },
                        )
                    }
                }
            }
        }
    }

    // Loads the installed apps whenever the picker is showing, including a picker restored open
    // after the activity was recreated (the list itself isn't kept across that).
    LaunchedEffect(picking) {
        if (picking) onOpenPicker()
    }

    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            AppPicker(installedApps) {
                picking = false
                onAdd(it)
            }
        }
    }

    removing?.let { app ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.blocklist_delete_title)) },
            text = { Text(stringResource(R.string.blocklist_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemove(app)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showRestricted) {
        val message = context.getString(R.string.blocklist_unsupported_apps_message, restrictedApps.joinToString("<br/>"))
        AlertDialog(
            onDismissRequest = { showRestricted = false },
            title = { Text(stringResource(R.string.blocklist_unsupported_apps)) },
            text = { Text(AnnotatedString.fromHtml(message)) },
            confirmButton = { TextButton(onClick = { showRestricted = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/** Installed apps with a search field; user apps first, then by name. */
@Composable
private fun AppPicker(apps: List<AppInfo>?, onPick: (AppInfo) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            enabled = apps != null,
            singleLine = true,
            label = { Text(stringResource(R.string.app_list_search)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        if (apps == null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.app_list_loading))
            }
        } else {
            val shown = remember(apps, query) {
                if (query.isBlank()) apps else apps.filter { it.appName.contains(query, ignoreCase = true) }
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.packageName }) { app ->
                    AppRow(icon = app.icon, title = app.appName, summary = app.packageName, onClick = { onPick(app) })
                }
            }
        }
    }
}

@Composable
internal fun AppRow(icon: Drawable?, title: String, summary: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CarUi.MinTouch + 16.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(icon, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = CarUi.MinText, maxLines = 1)
            Text(summary, color = SummaryColor, fontSize = CarUi.MinDenseText, maxLines = 1)
        }
    }
}

@Composable
internal fun AppIcon(icon: Drawable?, modifier: Modifier = Modifier) {
    val bitmap = remember(icon) { icon?.let { runCatching { it.toBitmap(IconPx, IconPx).asImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(bitmap, contentDescription = stringResource(R.string.app_list_icon_alt), modifier = modifier)
    } else {
        Spacer(modifier)
    }
}

private const val IconPx = 128
