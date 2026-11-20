package app.siphondsp.compose.controls

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.siphondsp.R
import app.siphondsp.compose.home.HomePalette
import app.siphondsp.compose.home.neonEdge
import app.siphondsp.compose.theme.BmwTheme

/*
 * Black glass with the chain tiles' neon edge: the launcher's and the DSP pages' menus, dialogs and
 * menu buttons, in place of Android's grey dropdowns, white dialogs and chips, so every popup reads
 * as part of the DSP.
 */

/** The DSP's purple: the signal line and the edge of menus and of dialogs that don't throw work away. */
@Composable
internal fun glassAccent(): Color = BmwTheme.colors.sliderHeadroom

/**
 * A menu: a black glass popup edged in the DSP's purple, its lines [GlassItem]s, with [GlassDivider]s
 * between groups and [GlassNote]s for headings and read-only facts.
 */
@Composable
internal fun GlassMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(GlassCorner),
        containerColor = HomePalette.Panel,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.5.dp, glassAccent()),
        modifier = Modifier.widthIn(min = GlassMenuWidth),
        content = content,
    )
}

/** A [GlassMenu] line with a glyph: [label] and [icon] are resources; [danger] for one that throws work away. */
@Composable
internal fun GlassItem(label: Int, @DrawableRes icon: Int, danger: Boolean = false, onClick: () -> Unit) =
    GlassItem(stringResource(label), icon = icon, danger = danger, onClick = onClick)

/**
 * A [GlassMenu] line: [text], with [icon] before it if given and a tick after it while [checked] (the
 * option in use, or a setting that's on). [colour] overrides the text's colour (a page's accent for its
 * current option); [danger] makes it red. Greyed out and inert unless [enabled].
 */
@Composable
internal fun GlassItem(
    text: String,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    checked: Boolean = false,
    colour: Color? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val textColour = when {
        danger -> HomePalette.Ceiling
        colour != null -> colour
        else -> Color.White
    }
    DropdownMenuItem(
        text = { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = icon?.let { res -> { Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(22.dp)) } },
        trailingIcon = if (checked) {
            { Icon(painterResource(R.drawable.ic_twotone_check_circle_24dp), contentDescription = null, tint = glassAccent(), modifier = Modifier.size(22.dp)) }
        } else null,
        onClick = onClick,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = textColour,
            leadingIconColor = if (danger) textColour else HomePalette.Caption,
            disabledTextColor = HomePalette.Dim,
            disabledLeadingIconColor = HomePalette.Dim,
        ),
        modifier = Modifier.heightIn(min = GlassItemHeight),
    )
}

/** A hairline between groups in a [GlassMenu]. */
@Composable
internal fun GlassDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), thickness = 1.dp, color = HomePalette.IdleEdge)
}

/** A heading or a read-only fact in a [GlassMenu] ("Display channel", "Left: none"). */
@Composable
internal fun GlassNote(text: String) {
    Text(
        text, color = HomePalette.Caption, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

/**
 * A page's small action button: a dark pill, its edge purple while the knob is on it. With [arrow]
 * (the default) it opens a [GlassMenu] (Backup, File, Tools, Graph) and shows a down arrow. [accent]
 * edges and colours the one that matters most on its line (the whole-DSP Backup).
 */
@Composable
internal fun GlassChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    arrow: Boolean = true,
    accent: Color? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(HomePalette.Cell)
            .border(1.5.dp, if (focused) glassAccent() else accent?.takeIf { enabled } ?: HomePalette.IdleEdge, shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .bmwFocusRing(source, cornerRadius = 12.dp)
            .padding(start = 16.dp, end = if (arrow) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (!enabled) HomePalette.Dim else accent ?: Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (arrow) {
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_down_24dp), contentDescription = null, tint = HomePalette.Caption, modifier = Modifier.size(22.dp))
        }
    }
}

/** What a [GlassDialog] button does: back out, go ahead, or go ahead and throw work away. */
internal enum class GlassButtonKind { Cancel, Primary, Danger }

internal class GlassButton(val label: String, val kind: GlassButtonKind, @DrawableRes val icon: Int? = null, val onClick: () -> Unit)

/**
 * A confirm dialog in black glass: [icon] and [title], the signal line, [content], then [buttons] along
 * the bottom right. Edged red when [danger] (it throws work away), else purple. The first button
 * (Cancel or Close) takes the iDrive knob's focus, so a stray first press confirms nothing.
 */
@Composable
internal fun GlassDialog(
    title: String,
    @DrawableRes icon: Int,
    onDismissRequest: () -> Unit,
    buttons: List<GlassButton>,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        LaunchedEffect(Unit) { if (!view.isInTouchMode) runCatching { first.requestFocus() } }
        GlassDialogPanel(title, icon, buttons, danger, first, content)
    }
}

/** What [GlassDialog] draws inside its window; [first] goes on the first button. */
@Composable
private fun GlassDialogPanel(
    title: String,
    @DrawableRes icon: Int,
    buttons: List<GlassButton>,
    danger: Boolean,
    first: FocusRequester,
    content: @Composable ColumnScope.() -> Unit,
) {
    val edge = if (danger) HomePalette.Ceiling else glassAccent()
    val shape = RoundedCornerShape(GlassDialogCorner)
    // The body takes only the height the title and buttons leave (it scrolls inside that), so the
    // buttons stay on screen and in the knob's reach on the 480 dp head unit.
    Column(
        Modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .widthIn(max = GlassDialogWidth)
            .fillMaxWidth()
            .neonEdge(GlassDialogCorner.value, edge, glow = 0.8f)
            .clip(shape)
            .background(HomePalette.Panel)
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = edge, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(2.dp).background(edge.copy(alpha = 0.8f)))
        Column(Modifier.weight(1f, fill = false), content = content)
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            buttons.forEachIndexed { i, button ->
                DialogButton(button, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

/** A [GlassDialog]'s body text, scrolling when it's longer than the space the dialog leaves it. */
@Composable
internal fun GlassDialogText(text: String) {
    Text(text, color = HomePalette.Subtitle, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
}

@Composable
private fun DialogButton(button: GlassButton, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val accent = glassAccent()
    val (fill, edge, text) = when (button.kind) {
        GlassButtonKind.Cancel -> Triple(HomePalette.Cell, HomePalette.IdleEdge, Color.White)
        GlassButtonKind.Primary -> Triple(accent.copy(alpha = 0.22f), accent, Color.White)
        GlassButtonKind.Danger -> Triple(HomePalette.Ceiling.copy(alpha = 0.18f), HomePalette.Ceiling, DangerText)
    }
    Row(
        modifier
            .height(GlassButtonHeight)
            .widthIn(min = 112.dp)
            .knobFocusRing(14.dp, grow = true)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, edge, shape)
            .clickable(role = Role.Button, onClick = button.onClick)
            .semantics { contentDescription = button.label }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        button.icon?.let {
            Icon(painterResource(it), contentDescription = null, tint = text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(button.label, color = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Close, as a glyph button like the home screen's corner buttons. */
@Composable
internal fun GlassClose(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val label = stringResource(R.string.launcher_close)
    Box(
        modifier
            .size(GlassControlSize)
            .knobFocusRing(14.dp)
            .neonEdge(14f, HomePalette.PanelEdge, glow = 0f)
            .clip(shape)
            .background(HomePalette.Panel)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_close_24dp), contentDescription = null, tint = HomePalette.Button, modifier = Modifier.size(26.dp))
    }
}

/** The drawer's search: a dark field with the search glyph, edged in purple while it has focus. */
@Composable
internal fun GlassSearch(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        interactionSource = source,
        textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
        cursorBrush = SolidColor(glassAccent()),
        modifier = modifier.height(GlassControlSize),
        decorationBox = { field ->
            Row(
                Modifier
                    .clip(shape)
                    .background(HomePalette.Cell)
                    .border(1.5.dp, if (focused) glassAccent() else HomePalette.IdleEdge, shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null, tint = HomePalette.Caption, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.fillMaxWidth()) {
                    if (query.isEmpty()) Text(stringResource(R.string.launcher_search), color = HomePalette.Muted, fontSize = 17.sp)
                    field()
                }
            }
        },
    )
}

private val GlassCorner = 16.dp
private val GlassMenuWidth = 260.dp
private val GlassItemHeight = 52.dp
private val GlassDialogCorner = 18.dp
private val GlassDialogWidth = 600.dp
private val GlassButtonHeight = 56.dp
private val DangerText = Color(0xFFFCA5A5)
internal val GlassControlSize = 58.dp
