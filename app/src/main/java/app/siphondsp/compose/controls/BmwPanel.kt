package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.view.BmwDashboardSkin

/**
 * Compose equivalent of `CrossoverDashboardBuilder.dashboardPanel` in `lean` mode -- the
 * no-card glass panel every DSP workspace page uses on the head unit. A thin bold header (with
 * an optional [BmwSwitch] gating the whole panel, aligned to where the slider rows begin), a
 * configurable gap, then the content [Column].
 *
 * Layout metrics ([RowTitleColumnWidth], [RowToggleZoneWidth], etc.) are shared with
 * [BmwSliderRow] so header switch and slider starts line up. [RowTitleColumnWidth] is a fixed
 * value for now (fits the Tonality Tilt labels); Phase 5 generalises it to auto-size against the
 * panel's actual labels the way the View builder does via `pendingTitleBoxes`.
 */
@Composable
fun BmwPanel(
    title: String,
    modifier: Modifier = Modifier,
    toggleChecked: Boolean? = null,
    onToggleChange: ((Boolean) -> Unit)? = null,
    titleColor: Color = Color.White,
    leanStart: Dp = 80.dp,
    topContentGap: Dp = 40.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = leanStart, top = 6.dp, end = 12.dp, bottom = 8.dp),
    ) {
        if (title.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(RowTitleColumnWidth),
                )
                if (toggleChecked != null && onToggleChange != null) {
                    Spacer(Modifier.width(RowToggleZoneWidth))
                    BmwSwitch(
                        checked = toggleChecked,
                        onCheckedChange = onToggleChange,
                        contentDescription = title,
                    )
                }
            }
        }
        Spacer(Modifier.height(topContentGap))
        content()
    }
}

// Shared slider-row metrics, 1:1 with CrossoverDashboardBuilder / BmwDashboardSkin.
internal val RowTitleColumnWidth = 150.dp
internal val RowToggleZoneWidth = 120.dp // TOGGLE_ZONE_WIDTH_DP
internal val RowValueWidth = 88.dp // VALUE_WIDTH_DP
internal val RowValueGap = BmwDashboardSkin.SLIDER_VALUE_GAP_DP.dp
internal val RowBoxHeight = BmwDashboardSkin.SLIDER_TITLE_HEIGHT_DP.dp // == SLIDER_VALUE_HEIGHT_DP (30)
internal val RowMinHeight = BmwDashboardSkin.SLIDER_ROW_MIN_HEIGHT_DP.dp

/** Boxed slider-row title -- bold white text, glass box tinted to the row's slider colour. */
@Composable
internal fun BoxedTitle(text: String, accentColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.bmwGlassBox(accentColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

/** Boxed value readout -- centred bold number + right-pinned unit in the slider colour. */
@Composable
internal fun BoxedValue(text: String, unit: String, accentColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.bmwGlassBox(accentColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 2.dp, end = 8.dp),
            )
        }
    }
}
