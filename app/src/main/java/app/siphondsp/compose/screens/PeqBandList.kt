package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.model.BmwPeqState

/** One aligned line per filter, within the viewport. Only additional rows scroll. */
@Composable
fun PeqBandList(
    holder: PeqStateHolder,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val bands = holder.visibleBands
    LaunchedEffect(holder.selectedScope, holder.selectedUuid) {
        val selected = bands.indexOfFirst { it.uuid == holder.selectedUuid }
        if (selected >= 0) listState.animateScrollToItem(selected)
    }
    BoxWithConstraints(modifier.fillMaxWidth().testTag("peq-filter-table")) {
        val compact = maxWidth < 640.dp
        val showSteppers = maxWidth >= 480.dp
        val numberWidth = if (compact) 20.dp else 24.dp
        val buttonWidth = if (compact) 32.dp else 44.dp
        val parameterGap = if (compact) 8.dp else PeqParameterGap
        val fontSize = if (compact) 14.sp else if (maxWidth < 900.dp) 16.sp else 20.sp
        val parameterWeights = if (compact) listOf(1f, 1f, 1f) else listOf(1.15f, 1f, .85f)
        val row: @Composable (Modifier, @Composable RowScope.() -> Unit) -> Unit = { rowModifier, cells ->
            Row(rowModifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically, content = cells)
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            row(Modifier.height(32.dp)) {
                TableHeading("#", Modifier.width(numberWidth))
                TableHeading("CH", Modifier.width(buttonWidth))
                TableHeading("TYPE", Modifier.width(buttonWidth))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(parameterGap)) {
                    listOf(if (compact) "Hz" else "FREQUENCY", if (compact) "dB" else "GAIN", "Q").forEachIndexed { i, label ->
                        TableHeading(label, Modifier.weight(parameterWeights[i]))
                    }
                }
                TableHeading("", Modifier.width(buttonWidth))
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(bands, key = { _, band -> band.uuid.toString() }) { index, band ->
                    val actions = rememberPeqFilterActions(holder, index)
                    val number = holder.filterNumberOffset + index + 1
                    row(Modifier.background(if (band.uuid == holder.selectedUuid) PeqPanelFill else Color.Transparent)
                        .padding(vertical = 6.dp).testTag("peq-filter-row-$number")) {
                        Text("$number", color = Color(peqFilterColor(number - 1)),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.width(numberWidth))
                        PeqActionButton(band.channel.displayLabel, actions::channel, Modifier.width(buttonWidth), contentPadding = 2.dp)
                        PeqActionButton(band.filterType.displayLabel, actions::type, Modifier.width(buttonWidth), contentPadding = 2.dp)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(parameterGap)) {
                            val frequency = if (compact && band.frequency >= 1000.0) "${formatPeqValue(band.frequency / 1000.0)}k"
                                else formatPeqValue(band.frequency)
                            PeqNumericControl("Filter $number frequency", if (compact) frequency else "$frequency Hz",
                                actions::frequency, actions::stepFrequency, actions::commit, Modifier.weight(parameterWeights[0]),
                                showSteppers = showSteppers, stepWidth = if (compact) 32.dp else 44.dp, valueFontSize = fontSize)
                            PeqNumericControl("Filter $number gain", formatPeqValue(band.gain) + if (compact) "" else " dB",
                                actions::gain, actions::stepGain, actions::commit, Modifier.weight(parameterWeights[1]),
                                showSteppers = showSteppers, stepWidth = if (compact) 32.dp else 44.dp, valueFontSize = fontSize)
                            PeqNumericControl("Filter $number Q", formatPeqValue(band.q),
                                actions::q, actions::stepQ, actions::commit, Modifier.weight(parameterWeights[2]),
                                showSteppers = showSteppers, stepWidth = if (compact) 32.dp else 44.dp, valueFontSize = fontSize)
                        }
                        PeqActionButton("×", { holder.deleteBand(index) }, Modifier.width(buttonWidth), contentPadding = 2.dp)
                    }
                }
                if (bands.size < BmwPeqState.MAX_BANDS) {
                    item(key = "add-filter") {
                        PeqActionButton("+ Add filter", holder::addBand, Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeading(label: String, modifier: Modifier) {
    Text(label, color = PeqMutedText, fontSize = 12.sp, maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = modifier)
}
