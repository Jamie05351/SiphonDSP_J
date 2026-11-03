package app.siphondsp.compose.launcher

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.siphondsp.R
import app.siphondsp.compose.controls.knobFocusRing
import app.siphondsp.compose.home.HomePalette
import app.siphondsp.compose.home.LocalHomeScale
import app.siphondsp.compose.home.neonEdge

/**
 * The home screen's tiles. Each keeps its [title] and its [image] whatever app is attached to it;
 * the attached app only shows as the tile's subtitle. In row order, which is also the slot each
 * one's app is kept in.
 */
internal enum class HomeTile(@StringRes val title: Int, val accent: Color, @DrawableRes val image: Int) {
    NAVIGATION(R.string.launcher_tile_navigation, Color(0xFF38BDF8), R.drawable.launcher_tile_navigation),
    MUSIC(R.string.launcher_tile_music, Color(0xFFF472B6), R.drawable.launcher_tile_music),
    FILES(R.string.launcher_tile_files, Color(0xFFFBBF24), R.drawable.launcher_tile_files),
    DRIVE(R.string.launcher_tile_drive, Color(0xFF34D399), R.drawable.launcher_tile_drive),
    TOOLKIT(R.string.launcher_tile_toolkit, Color(0xFF60A5FA), R.drawable.launcher_tile_toolkit),
    SETTINGS(R.string.launcher_tile_settings, Color(0xFFA5B4FC), R.drawable.launcher_tile_settings),
    DSP(R.string.launcher_tile_dsp, Color(0xFFA855F7), R.drawable.launcher_tile_dsp),
    ;

    /** The tile's key in [LauncherCustomisation.Look.hidden] when it's hidden. */
    val key: String get() = KeyPrefix + name

    companion object {
        const val KeyPrefix = "tile:"
    }
}

/**
 * A home tile: its picture over the whole face, the title and the attached app's name along the
 * bottom, and the launcher's edge ([neonEdge]) in [HomeTile.accent]. The picture is opaque.
 * [art] is the album art, which replaces
 * the picture on the music tile while something is playing; [extra] sits just above the title (the media controls).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeTileFace(
    tile: HomeTile,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    art: ImageBitmap? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val s = LocalHomeScale.current
    val corner = s.dp(TileCorner)
    val shape = RoundedCornerShape(corner)
    val accent = tile.accent
    val title = stringResource(tile.title)
    Box(
        Modifier
            .fillMaxSize()
            .knobFocusRing(corner, grow = true)
            .neonEdge(TileCorner, accent)
            .clip(shape)
            .background(HomePalette.TileBase)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "$title: $subtitle" },
    ) {
        // The tile's render, or the album art while the music tile is playing, then a shade
        // along the bottom so the title reads over it.
        val picture = Modifier.fillMaxSize()
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = picture)
        } else {
            Image(painterResource(tile.image), contentDescription = null, contentScale = ContentScale.Crop, modifier = picture)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to HomePalette.TileBase.copy(alpha = 0.8f)),
            ),
        )
        Column(Modifier.fillMaxSize().padding(s.dp(14f))) {
            Spacer(Modifier.weight(1f))
            extra?.invoke()
            Text(
                title, color = Color.White, fontSize = s.sp(19f), fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = s.dp(2f)),
            )
            Text(
                subtitle, color = HomePalette.Subtitle, fontSize = s.sp(13f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = s.dp(2f), top = s.dp(2f)),
            )
        }
    }
}

private const val TileCorner = 16f
