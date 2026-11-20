package app.siphondsp.compose.launcher

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.knobFocusRing
import app.siphondsp.compose.home.HomePalette
import app.siphondsp.compose.home.neonEdge
import app.siphondsp.launcher.AppEntry
import app.siphondsp.launcher.CarFunction
import app.siphondsp.launcher.LauncherCustomisation
import app.siphondsp.launcher.SlotTarget
import java.util.Locale

/**
 * Every installed app not hidden in [look], with a search box, in the glass panel over the blurred
 * home screen. Tapping an app opens it, or with [pickFor] puts it in that home slot. Hold for Add to
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
    knobEntry: FocusRequester,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val close = { onOverlayChange(LauncherOverlay.None) }
    OverlayFrame(
        title = stringResource(if (pickFor != null) R.string.launcher_pick_app else R.string.launcher_drawer),
        icon = R.drawable.ic_launcher_apps_24dp,
        start = start,
        top = top,
        bottom = bottom,
        knobEntry = knobEntry,
        onClose = close,
        header = { GlassSearch(query, { query = it }, Modifier.widthIn(max = 380.dp).fillMaxWidth()) },
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
                columns = GridCells.Adaptive(AppTileWidth),
                horizontalArrangement = Arrangement.spacedBy(TileGap),
                verticalArrangement = Arrangement.spacedBy(TileGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.key }) { app ->
                    var menu by remember { mutableStateOf(false) }
                    val key = SlotTarget.App(app.component).encode()
                    Box {
                        AppTile(
                            app, look.icons[key] ?: app.icon, dimmed = false, caption = null,
                            onClick = {
                                if (pickFor != null) actions.assignSlot(pickFor, SlotTarget.App(app.component))
                                else actions.launchApp(app)
                                close()
                            },
                            onLongClick = { if (pickFor == null) menu = true },
                        )
                        GlassMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            GlassItem(R.string.launcher_add_to_home, R.drawable.ic_twotone_add_24dp) {
                                menu = false
                                val free = slots.indexOfFirst { it == SlotTarget.Empty }
                                if (free >= 0) {
                                    actions.assignSlot(free, SlotTarget.App(app.component))
                                    close()
                                } else {
                                    actions.homeFull()
                                }
                            }
                            GlassItem(R.string.launcher_change_icon, R.drawable.ic_twotone_edit_24dp) { menu = false; actions.pickIcon(key) }
                            if (key in look.icons) {
                                GlassItem(R.string.launcher_reset_icon, R.drawable.ic_twotone_history_24dp) { menu = false; actions.resetIcon(key) }
                            }
                            GlassDivider()
                            GlassItem(R.string.launcher_hide, R.drawable.ic_twotone_app_blocking_24dp) { menu = false; actions.setHidden(app, true) }
                            GlassItem(R.string.launcher_app_info, R.drawable.ic_twotone_info_24dp) { menu = false; actions.appInfo(app) }
                        }
                    }
                }
            }
        }
    }
}

/** An app in the drawer or the hidden list: its icon on a dark tile, its name under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppEntry,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    dimmed: Boolean,
    caption: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(TileCorner)
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (caption != null) AppTileHeight + 18.dp else AppTileHeight)
            .knobFocusRing(TileCorner, grow = true)
            .clip(shape)
            .background(HomePalette.Cell)
            .border(1.dp, HomePalette.IdleEdge, shape)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).alpha(if (dimmed) 0.55f else 1f))
        } else {
            Spacer(Modifier.size(58.dp))
        }
        Text(
            app.label, color = if (dimmed) HomePalette.Caption else Color.White, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
        if (caption != null) Text(caption, color = HomePalette.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** The car menu's sections, down its rail like the DSP pages' sidebar, each in its own colour. */
enum class CarSection(val label: Int, @DrawableRes val icon: Int, val accent: Color) {
    CAR(R.string.launcher_section_car, R.drawable.ic_launcher_car_24dp, Color(0xFF38BDF8)),
    HOME(R.string.launcher_section_home, R.drawable.ic_launcher_home_24dp, Color(0xFF34D399)),
    PERMISSIONS(R.string.launcher_section_permissions, R.drawable.ic_twotone_security_24dp, Color(0xFFFBBF24)),
    SETTINGS(R.string.launcher_section_settings, R.drawable.ic_twotone_settings_24dp, Color(0xFFA855F7)),
}

/** One tile in the car menu: its glyph and label, and with no [onClick] greyed out with [hint] saying why. */
private class MenuEntry(val key: String, val label: String, @DrawableRes val icon: Int, val hint: String? = null, val onClick: (() -> Unit)?)

/**
 * The car menu, in sections down a rail on the left: Car (the head unit's own screens and switches,
 * the blocked KSW equaliser last), Home screen (wallpaper, hidden apps and tiles, layout backup),
 * Permissions (only while now playing or the app swipe still needs allowing, with a count), and
 * Settings (J_DSP's, Android's and the default home app). With [pickFor], only the car functions are
 * listed, without the rail, and tapping one fills that home slot.
 */
@Composable
internal fun CarMenu(
    functions: List<CarFunction>,
    pickFor: Int?,
    section: CarSection,
    customWallpaper: Boolean,
    tilesHidden: Boolean,
    nowPlayingAllowed: Boolean,
    appSwipeAllowed: Boolean,
    start: Dp,
    top: Dp,
    bottom: Dp,
    knobEntry: FocusRequester,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    val close = { onOverlayChange(LauncherOverlay.None) }
    val permissionsNeeded = listOf(!nowPlayingAllowed, !appSwipeAllowed).count { it }
    val sections = CarSection.entries.filter { it != CarSection.PERMISSIONS || permissionsNeeded > 0 }
    var shown by remember(section) { mutableStateOf(section) }
    val current = if (pickFor != null || shown !in sections) CarSection.CAR else shown

    val entries = when (current) {
        CarSection.CAR -> functions
            .filter { pickFor == null || !it.blocked }
            .sortedBy { it.blocked }
            .map { function ->
                MenuEntry(
                    function.id, stringResource(function.label), function.icon(),
                    hint = if (function.blocked) stringResource(R.string.car_blocked_hint) else null,
                    onClick = if (function.blocked) null else {
                        {
                            if (pickFor != null) actions.assignSlot(pickFor, SlotTarget.Car(function)) else actions.runCar(function)
                            close()
                        }
                    },
                )
            }
        CarSection.HOME -> buildList {
            add(MenuEntry("wallpaper", stringResource(R.string.launcher_wallpaper), R.drawable.ic_launcher_image_24dp) { close(); actions.pickWallpaper() })
            if (customWallpaper) {
                add(MenuEntry("wallpaper_reset", stringResource(R.string.launcher_wallpaper_reset), R.drawable.ic_twotone_history_24dp) { actions.resetWallpaper() })
            }
            add(MenuEntry("hidden", stringResource(R.string.launcher_hidden_apps), R.drawable.ic_twotone_app_blocking_24dp) { onOverlayChange(LauncherOverlay.HiddenApps) })
            if (tilesHidden) {
                add(MenuEntry("hidden_tiles", stringResource(R.string.launcher_show_tiles), R.drawable.ic_launcher_apps_24dp) { close(); actions.showHiddenTiles() })
            }
            add(MenuEntry("backup", stringResource(R.string.launcher_backup_layout), R.drawable.ic_twotone_save_24dp) { close(); actions.backUpLayout() })
            add(MenuEntry("restore", stringResource(R.string.launcher_restore_layout), R.drawable.ic_twotone_settings_backup_restore_24dp) { close(); actions.restoreLayout() })
        }
        CarSection.PERMISSIONS -> buildList {
            if (!nowPlayingAllowed) {
                add(
                    MenuEntry(
                        "now_playing", stringResource(R.string.launcher_allow_now_playing), R.drawable.ic_twotone_notifications_24dp,
                        stringResource(R.string.launcher_allow_now_playing_hint),
                    ) { close(); actions.allowNowPlaying() },
                )
            }
            if (!appSwipeAllowed) {
                add(
                    MenuEntry(
                        "app_swipe", stringResource(R.string.launcher_allow_app_swipe), R.drawable.ic_twotone_security_24dp,
                        stringResource(R.string.launcher_allow_app_swipe_hint),
                    ) { close(); actions.allowAppSwipe() },
                )
            }
        }
        CarSection.SETTINGS -> listOf(
            MenuEntry("dsp", stringResource(R.string.launcher_dsp_settings), R.drawable.ic_tune_vertical_variant_24dp) { close(); actions.dspSettings() },
            MenuEntry("android", stringResource(R.string.launcher_android_settings), R.drawable.ic_twotone_settings_24dp) { close(); actions.androidSettings() },
            MenuEntry("home", stringResource(R.string.launcher_home_app), R.drawable.ic_launcher_home_24dp) { close(); actions.homeAppSettings() },
        )
    }

    OverlayFrame(
        title = stringResource(if (pickFor != null) R.string.launcher_pick_car else R.string.launcher_car_menu),
        icon = R.drawable.ic_launcher_car_24dp,
        start = start,
        top = top,
        bottom = bottom,
        knobEntry = knobEntry,
        onClose = close,
    ) {
        Row(Modifier.fillMaxSize()) {
            if (pickFor == null) {
                Column(Modifier.width(RailWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TileGap)) {
                    for (s in sections) {
                        RailItem(s, selected = s == current, badge = if (s == CarSection.PERMISSIONS) permissionsNeeded else 0) { shown = s }
                    }
                }
                Spacer(Modifier.width(20.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(MenuTileWidth),
                horizontalArrangement = Arrangement.spacedBy(TileGap),
                verticalArrangement = Arrangement.spacedBy(TileGap),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                items(entries, key = { it.key }) { MenuTile(it, current.accent) }
            }
        }
    }
}

/** Each car function's glyph. */
@DrawableRes
private fun CarFunction.icon(): Int = when (this) {
    CarFunction.SETTINGS -> R.drawable.ic_twotone_settings_24dp
    CarFunction.ORIGINAL_CAR -> R.drawable.ic_launcher_car_24dp
    CarFunction.DASHBOARD -> R.drawable.ic_launcher_speed_24dp
    CarFunction.EQ -> R.drawable.ic_twotone_peq_sliders_28dp
    CarFunction.PHONE -> R.drawable.ic_launcher_phone_24dp
    CarFunction.AUX -> R.drawable.ic_launcher_input_24dp
    CarFunction.DVR -> R.drawable.ic_launcher_videocam_24dp
    CarFunction.FRONT_CAMERA -> R.drawable.ic_launcher_camera_24dp
}

/** A section on the car menu's rail: lit in its colour while showing; [badge] counts what needs doing. */
@Composable
private fun RailItem(section: CarSection, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(TileCorner)
    val label = stringResource(section.label)
    Row(
        Modifier
            .fillMaxWidth()
            .height(RailItemHeight)
            .knobFocusRing(TileCorner, grow = true)
            .then(if (selected) Modifier.neonEdge(TileCorner.value, section.accent) else Modifier.border(1.dp, HomePalette.IdleEdge, shape))
            .clip(shape)
            .background(if (selected) section.accent.copy(alpha = 0.16f) else HomePalette.Panel)
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(section.icon), contentDescription = null, tint = if (selected) section.accent else HomePalette.Caption, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label, color = if (selected) Color.White else HomePalette.Subtitle, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (badge > 0) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(section.accent), contentAlignment = Alignment.Center) {
                Text(badge.toString(), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * A car-menu tile: the glyph in [accent] over the label, edged in [accent] like the DSP's chain
 * tiles. Without an onClick it is greyed out, with its hint under the label saying why.
 */
@Composable
private fun MenuTile(entry: MenuEntry, accent: Color) {
    val shape = RoundedCornerShape(TileCorner)
    val onClick = entry.onClick
    val enabled = onClick != null
    Column(
        Modifier
            .fillMaxWidth()
            .height(MenuTileHeight)
            .knobFocusRing(TileCorner, grow = true)
            .then(if (enabled) Modifier.neonEdge(TileCorner.value, accent, glow = 0.6f) else Modifier.border(1.dp, HomePalette.IdleEdge, shape))
            .clip(shape)
            .background(HomePalette.Cell)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick!!) else Modifier)
            .semantics { contentDescription = listOfNotNull(entry.label, entry.hint).joinToString(": ") }
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(entry.icon), contentDescription = null, tint = if (enabled) accent else HomePalette.Dim, modifier = Modifier.size(32.dp))
        Text(
            entry.label, color = if (enabled) Color.White else HomePalette.Dim, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            lineHeight = 19.sp, maxLines = if (entry.hint != null) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (entry.hint != null) {
            Text(
                entry.hint, color = HomePalette.Muted, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp),
            )
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
    knobEntry: FocusRequester,
    onOverlayChange: (LauncherOverlay) -> Unit,
    actions: LauncherActions,
) {
    OverlayFrame(
        title = stringResource(R.string.launcher_hidden_apps),
        icon = R.drawable.ic_twotone_app_blocking_24dp,
        start = start,
        top = top,
        bottom = bottom,
        knobEntry = knobEntry,
        // Back to the car menu's home-screen section, where Hidden apps lives.
        onClose = { onOverlayChange(LauncherOverlay.CarMenu(section = CarSection.HOME)) },
    ) {
        val hidden = apps?.filter { SlotTarget.App(it.component).encode() in look.hidden }
        when {
            hidden == null -> Message(stringResource(R.string.launcher_loading))
            hidden.isEmpty() -> Message(stringResource(R.string.launcher_no_hidden))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(AppTileWidth),
                horizontalArrangement = Arrangement.spacedBy(TileGap),
                verticalArrangement = Arrangement.spacedBy(TileGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(hidden, key = { it.key }) { app ->
                    AppTile(
                        app, look.icons[SlotTarget.App(app.component).encode()] ?: app.icon, dimmed = true,
                        caption = stringResource(R.string.launcher_unhide), onClick = { actions.setHidden(app, false) }, onLongClick = null,
                    )
                }
            }
        }
    }
}

/**
 * A full-screen black glass panel over the blurred home screen, edged in the DSP's purple: its glyph
 * and [title], [header] (the drawer's search) and Close along the top, the purple signal line under
 * them, then [content]. It sits [start] in from both sides, [top] down, clear of the head unit's top
 * banner, and stops [bottom] above the bottom edge. The home controls and DSP bar are hidden. The
 * iDrive knob's focus enters at Close ([knobEntry]).
 */
@Composable
private fun OverlayFrame(
    title: String,
    @DrawableRes icon: Int,
    start: Dp,
    top: Dp,
    bottom: Dp,
    knobEntry: FocusRequester,
    onClose: () -> Unit,
    header: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val accent = glassAccent()
    val shape = RoundedCornerShape(PanelCorner)
    Column(
        Modifier
            .fillMaxSize()
            // The menu includes the space normally occupied by the DSP bar.
            .padding(start = start, end = start, top = top, bottom = bottom)
            .neonEdge(PanelCorner.value, accent, glow = 0.7f)
            .clip(shape)
            .background(HomePalette.Panel.copy(alpha = 0.88f))
            // Swallows the rest of the taps so nothing reaches the home screen underneath.
            .clickable(remember { MutableInteractionSource() }, null) {}
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        // The header (search) takes whatever width is left between the title and Close, so the row
        // fits a narrow screen too.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { header() }
            // Where the iDrive knob's focus enters an open drawer or menu.
            GlassClose(onClose, Modifier.focusRequester(knobEntry))
        }
        // The DSP's signal line under the header.
        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(2.dp).background(accent.copy(alpha = 0.8f)))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = HomePalette.Caption, fontSize = 18.sp)
    }
}

private val PanelCorner = 18.dp
private val TileCorner = 14.dp
private val TileGap = 12.dp
private val AppTileWidth = 132.dp
private val AppTileHeight = 120.dp
private val RailWidth = 210.dp
private val RailItemHeight = 58.dp
private val MenuTileWidth = 180.dp
private val MenuTileHeight = 112.dp
