package app.siphondsp.compose.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Sampled from James's page-finder mockup (Workspace_v4_low_finder.png).
private val FinderPurple = Color(0xFF7327A5)
private val FinderFill = Color(0xFF0E0F11)
private val FinderText = Color(0xFFBDDFF1)
private val FinderSelectedFill = Color(0x597327A5)
private val FinderBorder = 2.5.dp
private val FinderShape = RoundedCornerShape(3.dp)

/**
 * The v4 head-unit page finder: one labelled segment per sub-page, replacing the generic prev/next
 * arrows ([DspPagerArrows]) so any sub-page is one tap away. Purple outline, dividers and label
 * colour follow James's mockup; the current segment gets a purple tint. Fills the box it is given
 * (placed over the art by [WorkspaceArtBox]) and splits it evenly between [labels].
 */
@Composable
fun DspPageFinder(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxSize()
            .clip(FinderShape)
            .background(FinderFill)
            .border(FinderBorder, FinderPurple, FinderShape),
    ) {
        labels.forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(FinderBorder).fillMaxHeight().background(FinderPurple))
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) FinderSelectedFill else Color.Transparent)
                    .semantics { this.selected = isSelected }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FinderPurple),
                        role = Role.Tab,
                        onClick = { if (!isSelected) onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else FinderText,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 0.16.em,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A [DspPageFinder] over [frac] of the workspace art, driving [pagerState] one segment per page.
 * Jumps are instant (`scrollToPage`), not animated: a long jump would otherwise flip through
 * every page in between, and swiping is what reads as paging.
 */
@Composable
fun ArtPagerFinder(
    pagerState: PagerState,
    labels: List<String>,
    frac: WorkspaceArt.Frac,
    selected: Int = pagerState.currentPage,
    onSelect: ((Int) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val select: (Int) -> Unit = onSelect ?: { page -> scope.launch { pagerState.scrollToPage(page) } }
    WorkspaceArtBox(Modifier.fillMaxSize()) {
        DspPageFinder(
            labels = labels,
            selected = selected,
            onSelect = select,
            modifier = Modifier.artRect(frac),
        )
    }
}
