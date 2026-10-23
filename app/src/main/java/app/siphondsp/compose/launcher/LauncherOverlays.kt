package app.siphondsp.compose.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import app.siphondsp.R
import app.siphondsp.compose.controls.knobFocusRing
import app.siphondsp.launcher.LauncherCustomisation
import app.siphondsp.compose.home.HomePalette
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.launcher.AppEntry
import app.siphondsp.launcher.CarFunction
import app.siphondsp.launcher.SlotTarget
import java.util.Locale

/**
 * Every installed app not hidden in [look], with a search box, over the blurred home screen.
 * Tapping an app opens it, or with [pickFor] puts it in that home slot. Long-press offers Add to
 * home, Change icon, Hide and App info.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppDrawer(
    apps: List<AppEntry>?,
    slots: List<SlotTarget>,
    look: LauncherCustomisation.Look,
    pickFor: Int?,
    start: Dp,
    top: Dp,
    bottom: Dp,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val close = { onOverlayChange(LauncherOverlay.None) }
    OverlayFrame(
        title = stringResource(if (pickFor != null) R.string.launcher_pick_app else R.string.launcher_drawer),
        start = start,
        top = top,
        bottom = bottom,
        onClose = close,
        header = {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.launcher_search)) },
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            )
        },
    ) {
        val shown = apps?.let { list ->
            val q = query.trim().lowercase(Locale.ROOT)
            list.filter { SlotTarget.App(it.component).encode() !in look.hidden }
                .filter { q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q) }
        }
        when {
            shown == null -> Message(stringResource(R.string.launcher_loading))
            shown.isEmpty() -> Message(stringResource(R.string.launcher_no_match))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.key }) { app ->
                    var menu by remember { mutableStateOf(false) }
                    val key = SlotTarget.App(app.component).encode()
                    Box {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .knobFocusRing(12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    role = Role.Button,
                                    onClick = {
                                        if (pickFor != null) actions.assignSlot(pickFor, SlotTarget.App(app.component))
                                        else actions.launchApp(app)
                                        close()
                                    },
                                    onLongClick = { if (pickFor == null) menu = true },
                                )
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val icon = look.icons[key] ?: app.icon
                            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)))
                            else Spacer(Modifier.size(64.dp))
                            Text(
                                app.label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.launcher_add_to_home)) }, onClick = {
                                menu = false
                                val free = slots.indexOfFirst { it == SlotTarget.Empty }
                                if (free >= 0) {
                                    actions.assignSlot(free, SlotTarget.App(app.component))
                                    close()
                                } else {
                                    actions.homeFull()
                                }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.launcher_change_icon)) }, onClick = {
                                menu = false
                                actions.pickIcon(key)
                            })
                            if (key in look.icons) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.launcher_reset_icon)) }, onClick = {
                                    menu = false
                                    actions.resetIcon(key)
                                })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.launcher_hide)) }, onClick = {
                                menu = false
                                actions.setHidden(app, true)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.launcher_app_info)) }, onClick = {
                                menu = false
                                actions.appInfo(app)
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The head unit's own screens and switches (Factory and KSW settings, original car, dashboard and
 * so on), then the home screen's own settings (wallpaper, hidden apps, now playing, layout backup),
 * J_DSP's and Android's settings and the default-home chooser. With [pickFor], only the car
 * functions are listed and tapping one fills that home slot.
 */
@Composable
internal fun CarMenu(
    functions: List<CarFunction>,
    pickFor: Int?,
    customWallpaper: Boolean,
    nowPlayingAllowed: Boolean,
    start: Dp,
    top: Dp,
    bottom: Dp,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    val close = { onOverlayChange(LauncherOverlay.None) }
    OverlayFrame(
        title = stringResource(if (pickFor != null) R.string.launcher_pick_car else R.string.launcher_car_menu),
        start = start,
        top = top,
        bottom = bottom,
        onClose = close,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(functions.filter { pickFor == null || !it.blocked }, key = { it.id }) { function ->
                if (function.blocked) {
                    MenuTile(stringResource(function.label), Modifier.fillMaxWidth(), stringResource(R.string.car_blocked_hint), onClick = null)
                    return@items
                }
                MenuTile(stringResource(function.label), Modifier.fillMaxWidth()) {
                    if (pickFor != null) actions.assignSlot(pickFor, SlotTarget.Car(function)) else actions.runCar(function)
                    close()
                }
            }
            if (pickFor == null) {
                item(key = "wallpaper") { MenuTile(stringResource(R.string.launcher_wallpaper), Modifier.fillMaxWidth()) { close(); actions.pickWallpaper() } }
                if (customWallpaper) {
                    item(key = "wallpaper_reset") { MenuTile(stringResource(R.string.launcher_wallpaper_reset), Modifier.fillMaxWidth()) { actions.resetWallpaper() } }
                }
                item(key = "hidden") { MenuTile(stringResource(R.string.launcher_hidden_apps), Modifier.fillMaxWidth()) { onOverlayChange(LauncherOverlay.HiddenApps) } }
                if (!nowPlayingAllowed) {
                    item(key = "now_playing") {
                        MenuTile(stringResource(R.string.launcher_allow_now_playing), Modifier.fillMaxWidth(), stringResource(R.string.launcher_allow_now_playing_hint)) {
                            close(); actions.allowNowPlaying()
                        }
                    }
                }
                item(key = "backup") { MenuTile(stringResource(R.string.launcher_backup_layout), Modifier.fillMaxWidth()) { close(); actions.backUpLayout() } }
                item(key = "restore") { MenuTile(stringResource(R.string.launcher_restore_layout), Modifier.fillMaxWidth()) { close(); actions.restoreLayout() } }
                item(key = "dsp") { MenuTile(stringResource(R.string.launcher_dsp_settings), Modifier.fillMaxWidth()) { close(); actions.dspSettings() } }
                item(key = "android") { MenuTile(stringResource(R.string.launcher_android_settings), Modifier.fillMaxWidth()) { close(); actions.androidSettings() } }
                item(key = "home") { MenuTile(stringResource(R.string.launcher_home_app), Modifier.fillMaxWidth()) { close(); actions.homeAppSettings() } }
            }
        }
    }
}

/** The apps hidden from the drawer; tapping one puts it back. They can still be on the home grid. */
@Composable
internal fun HiddenApps(
    apps: List<AppEntry>?,
    look: LauncherCustomisation.Look,
    start: Dp,
    top: Dp,
    bottom: Dp,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    OverlayFrame(
        title = stringResource(R.string.launcher_hidden_apps),
        start = start,
        top = top,
        bottom = bottom,
        onClose = { onOverlayChange(LauncherOverlay.CarMenu()) },
    ) {
        val hidden = apps?.filter { SlotTarget.App(it.component).encode() in look.hidden }
        when {
            hidden == null -> Message(stringResource(R.string.launcher_loading))
            hidden.isEmpty() -> Message(stringResource(R.string.launcher_no_hidden))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(hidden, key = { it.key }) { app ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .knobFocusRing(12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.launcher_unhide)) { actions.setHidden(app, false) }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val icon = look.icons[SlotTarget.App(app.component).encode()] ?: app.icon
                        if (icon != null) {
                            Image(icon, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)).alpha(0.6f))
                        } else {
                            Spacer(Modifier.size(64.dp))
                        }
                        Text(
                            app.label, color = HomePalette.Caption, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(stringResource(R.string.launcher_unhide), color = HomePalette.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * A full-screen panel over the blurred home screen: a title row with Close, then [content]. It
 * starts [start] in from the left, clear of the volume column, [top] down, clear of the head unit's
 * top banner, and stops [bottom] above the bottom edge, clear of the DSP bar.
 */
@Composable
private fun OverlayFrame(
    title: String,
    start: Dp,
    top: Dp,
    bottom: Dp,
    onClose: () -> Unit,
    header: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            // Clear of the volume column, which stays in use underneath.
            .padding(start = start)
            // Swallows the rest of the taps so nothing reaches the home screen underneath.
            .clickable(remember { MutableInteractionSource() }, null) {}
            .padding(end = 32.dp, top = top, bottom = bottom),
    ) {
        // The header (search) takes whatever width is left between the title and Close, so the row
        // fits a narrow screen too.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { header() }
            MenuTile(stringResource(R.string.launcher_close), Modifier.widthIn(min = 96.dp), onClick = onClose)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/**
 * A floating screen with one line of text, used for menu entries and Close. With no [onClick] it
 * is disabled: grey, with [hint] under the label saying why.
 */
@Composable
private fun MenuTile(label: String, modifier: Modifier = Modifier, hint: String? = null, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    val enabled = onClick != null
    Column(
        modifier
            .height(56.dp)
            .knobFocusRing(12.dp)
            .clip(shape)
            .background(HomePalette.Panel)
            .border(1.5.dp, if (enabled) BmwTheme.colors.sliderHeadroom.copy(alpha = 0.55f) else HomePalette.IdleEdge, shape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (enabled) Color.White else HomePalette.Dim, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (hint != null) Text(hint, color = HomePalette.Dim, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = HomePalette.Caption, fontSize = 18.sp)
    }
}
