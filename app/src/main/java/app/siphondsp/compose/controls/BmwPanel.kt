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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.view.BmwDashboardSkin

/**
 * Compose equivalent of `CrossoverDashboardBuilder.dashboardPanel` in `lean` mode -- the
 * no-card glass panel every DSP workspace page uses on the head unit. A thin bold header (with
 * an optional [BmwSwitch] gating the whole panel, aligned to where the slider rows begin), a
 * configurable gap, then the content [Column].
 *
 * Pass [sliderLabels] (every [BmwSliderRow] / [BmwSectionHeader] label in [content], in any
 * order) and the panel measures them and sizes the title column to the widest -- the Compose
 * equivalent of the View builder's `pendingTitleBoxes` pass -- publishing the result via
 * [LocalRowTitleColumnWidth] so every row and the header switch line up. Omit it and the column
 * falls back to [FallbackTitleColumnWidth].
 */
@Composable
fun BmwPanel(
    title: String,
    modifier: Modifier = Modifier,
    toggleChecked: Boolean? = null,
    onToggleChange: ((Boolean) -> Unit)? = null,
    titleColor: Color = Color.White,
    subtitle: String? = null,
    leanStart: Dp = 80.dp,
    topContentGap: Dp = 40.dp,
    sliderLabels: List<String> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val titleColumnWidth: Dp = remember(sliderLabels, textMeasurer, density) {
        if (sliderLabels.isEmpty()) {
            FallbackTitleColumnWidth
        } else {
            val style = TextStyle(fontSize = BoxTextSize, fontWeight = FontWeight.Bold)
            val widest = sliderLabels.maxOf { textMeasurer.measure(it, style).size.width }
            // +2dp slack so a label exactly as wide as the measured text doesn't hit the
            // maxLines=1 ellipsis on a sub-pixel rounding difference between measure and layout.
            with(density) { widest.toDp() + BoxTitleHorizontalPadding * 2 + 2.dp }
        }
    }

    CompositionLocalProvider(LocalRowTitleColumnWidth provides titleColumnWidth) {
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
                        modifier = Modifier.width(titleColumnWidth),
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
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = SubtitleColor,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
            } else {
                Spacer(Modifier.height(topContentGap))
            }
            content()
        }
    }
}

/**
 * The View builder's `titleRowWithSwitches` -- a big (18sp) white title in the title column,
 * the primary enable [BmwSwitch] aligned to where the slider rows start, and (optionally) a
 * second labelled switch pinned to the right edge. Used by the compressor band pages.
 */
@Composable
fun BmwTitleRowWithSwitches(
    title: String,
    enabledChecked: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    secondLabel: String? = null,
    secondChecked: Boolean? = null,
    onSecondChange: ((Boolean) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LocalRowTitleColumnWidth.current),
        )
        Spacer(Modifier.width(RowToggleZoneWidth))
        BmwSwitch(checked = enabledChecked, onCheckedChange = onEnabledChange, contentDescription = title)
        if (secondLabel != null && secondChecked != null && onSecondChange != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = secondLabel,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.padding(end = 10.dp),
            )
            BmwSwitch(checked = secondChecked, onCheckedChange = onSecondChange, contentDescription = secondLabel)
        }
    }
}

/**
 * A named sub-group header inside a [BmwPanel] (the View builder's `sectionHeader`) -- small
 * accent-coloured bold label, optionally carrying its own [BmwSwitch]. No divider rule (the
 * View's `showDivider=false` case, which is the only one the ported screens use so far).
 */
@Composable
fun BmwSectionHeader(
    title: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.5.sp,
    toggleChecked: Boolean? = null,
    onToggleChange: ((Boolean) -> Unit)? = null,
) {
    Spacer(Modifier.height(8.dp))
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = accentColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .width(LocalRowTitleColumnWidth.current)
                .padding(top = 2.dp, bottom = 7.dp),
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

// Shared slider-row metrics, 1:1 with CrossoverDashboardBuilder / BmwDashboardSkin.
internal val FallbackTitleColumnWidth = 150.dp
internal val RowToggleZoneWidth = 120.dp // TOGGLE_ZONE_WIDTH_DP
internal val RowValueWidth = 88.dp // VALUE_WIDTH_DP
internal val RowValueGap = BmwDashboardSkin.SLIDER_VALUE_GAP_DP.dp
internal val RowBoxHeight = BmwDashboardSkin.SLIDER_TITLE_HEIGHT_DP.dp // == SLIDER_VALUE_HEIGHT_DP (30)
internal val RowMinHeight = BmwDashboardSkin.SLIDER_ROW_MIN_HEIGHT_DP.dp
private val BoxTextSize = 14.sp
private val SubtitleColor = Color(0xFFB2BBC6) // rgb(178, 187, 198)
private val BoxTitleHorizontalPadding = 18.dp // createBoxedTitleText's setPadding(dp(18), .., dp(18), ..)

/** Width of every boxed row title in the enclosing [BmwPanel], measured from its `sliderLabels`;
 *  [FallbackTitleColumnWidth] outside a panel or when labels aren't supplied. */
internal val LocalRowTitleColumnWidth = compositionLocalOf { FallbackTitleColumnWidth }

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
            fontSize = BoxTextSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = BoxTitleHorizontalPadding),
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
            fontSize = BoxTextSize,
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
