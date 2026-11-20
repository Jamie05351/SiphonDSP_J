package app.siphondsp.compose.launcher

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.knobFocusRing
import app.siphondsp.compose.home.HomePalette
import app.siphondsp.compose.home.neonEdge
import app.siphondsp.compose.theme.BmwTheme

/*
 * The launcher's menus in the DSP's own look: black glass with the chain tiles' neon edge, in place
 * of Android's grey dropdowns and plain buttons. Used by the drawer, the car menu, the More menu and
 * every hold menu, so they all read as one piece with the DSP pages.
 */

/** The DSP's purple: the signal line and the edge of the menus that aren't a car-menu section. */
@Composable
internal fun glassAccent(): Color = BmwTheme.colors.sliderHeadroom

/**
 * A hold or More menu: a black glass popup edged in the DSP's purple, its items [GlassItem]s with a
 * glyph, and [GlassDivider]s between groups.
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
        modifier = Modifier.width(GlassMenuWidth),
        content = content,
    )
}

/** One line of a [GlassMenu]: its glyph, then its label; [danger] for one that throws work away. */
@Composable
internal fun GlassItem(label: Int, @DrawableRes icon: Int, danger: Boolean = false, onClick: () -> Unit) {
    val colour = if (danger) HomePalette.Ceiling else Color.White
    DropdownMenuItem(
        text = { Text(stringResource(label), fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp)) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = colour,
            leadingIconColor = if (danger) colour else HomePalette.Caption,
        ),
        modifier = Modifier.height(GlassItemHeight),
    )
}

/** A hairline between groups in a [GlassMenu]. */
@Composable
internal fun GlassDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), thickness = 1.dp, color = HomePalette.IdleEdge)
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
internal val GlassControlSize = 58.dp
