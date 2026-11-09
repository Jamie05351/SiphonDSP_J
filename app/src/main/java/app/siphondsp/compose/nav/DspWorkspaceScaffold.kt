package app.siphondsp.compose.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.siphondsp.R
import app.siphondsp.compose.controls.DspSidebarRail
import app.siphondsp.compose.controls.DspWorkspaceBackdrop
import app.siphondsp.view.DspDestination
import app.siphondsp.view.DspRail
import app.siphondsp.view.DspStatusStrip

/**
 * The chrome every DSP workspace shares (was `activity_parametric_eq.xml`), full screen on the
 * head unit's 1280x480:
 * - the faceplate (facia with the content window cut into it) behind everything;
 * - the sidebar rail on the left, its [current] tile lit; another tile switches workspace;
 * - a transparent toolbar line over the top of the content window: the back arrow, then the
 *   bypass-state strip, or this workspace's own [toolbarActions] (page finder / arrows, or the
 *   PEQ's scope row) at the same x;
 * - [content] in the window below the toolbar line, short of the bezel on the right and bottom.
 *
 * @param showStatusStrip false on the PEQ, whose scope row takes the whole toolbar line.
 */
@Composable
fun DspWorkspaceScaffold(
    current: DspDestination,
    onNavigate: (DspDestination) -> Unit,
    onBack: () -> Unit,
    showStatusStrip: Boolean = true,
    toolbarActions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val railWidth = remember(context) { DspRail.sidebarWidthDp(context).dp }
    val actionsStart = remember(context) { DspRail.toolbarActionsStartDp(context).dp }
    val toolbarHeight = dimensionResource(R.dimen.dsp_workspace_toolbar_height)
    val navInset = dimensionResource(R.dimen.dsp_toolbar_nav_inset)
    val bezel = dimensionResource(R.dimen.dsp_workspace_bezel_inset)
    val destinations = remember { DspDestination.entries.filter { it.showInPrimaryNav } }

    Box(Modifier.fillMaxSize()) {
        DspWorkspaceBackdrop(railWidth)

        DspSidebarRail(
            destinations = destinations,
            current = current,
            canNavigate = { true },
            onNavigate = { if (it != current) onNavigate(it) },
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .padding(start = railWidth, top = toolbarHeight, end = bezel, bottom = bezel),
        ) { content() }

        // The toolbar line, drawn last so it sits above the content.
        Box(
            Modifier
                .fillMaxWidth()
                .height(toolbarHeight),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .offset(x = railWidth + navInset - 4.dp, y = 25.dp)
                    .size(44.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_workspace_back_32dp),
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            if (showStatusStrip) {
                AndroidView(
                    factory = { ctx -> DspStatusStrip(ctx).apply { onOpen = { navigator.openWorkspace(it) } } },
                    update = { it.onOpen = { dest -> navigator.openWorkspace(dest) } },
                    modifier = Modifier
                        .padding(start = actionsStart)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart),
                )
            }
            if (toolbarActions != null) {
                Box(
                    Modifier
                        .padding(start = actionsStart)
                        .fillMaxSize(),
                ) { toolbarActions() }
            }
        }
    }
}
