package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.siphondsp.model.ParametricEqBand

private val TableWeights = listOf(44f, 88f, 116f, 242f, 242f, 222f, 54f)

/** All controls fit the viewport width. Additional filter rows scroll vertically only. */
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
        val compact = maxWidth < 1040.dp
        val stacked = maxWidth < 640.dp
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            if (!compact) {
                Row(
                    Modifier.fillMaxWidth().height(32.dp),
                    horizontalArrangement = Arrangement.spacedBy(PeqParameterGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("#", "CHANNEL", "TYPE", "FREQUENCY", "GAIN", "Q", "").forEachIndexed { i, label ->
                        Text(label, color = PeqMutedText, fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(TableWeights[i]))
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(bands, key = { _, band -> band.uuid.toString() }) { index, band ->
                    val actions = rememberPeqFilterActions(holder, index)
                    val filterNumber = holder.filterNumberOffset + index + 1
                    val rowModifier = Modifier.fillMaxWidth()
                        .background(if (band.uuid == holder.selectedUuid) PeqPanelFill else Color.Transparent)
                        .padding(vertical = 6.dp)
                    if (compact) {
                        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                PeqFilterNumber(filterNumber, Modifier.width(32.dp))
                                PeqActionButton(band.channel.displayLabel, actions::channel, Modifier.weight(1f))
                                PeqActionButton(band.filterType.displayLabel, actions::type, Modifier.weight(1f))
                                PeqActionButton("×", { holder.deleteBand(index) }, Modifier.width(44.dp))
                            }
                            if (stacked) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PeqParameterGap)) {
                                    repeat(3) { parameter ->
                                        PeqTableParameter(band, filterNumber, parameter, actions, true, Modifier.fillMaxWidth())
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PeqParameterGap)) {
                                    repeat(3) { parameter ->
                                        PeqTableParameter(band, filterNumber, parameter, actions, true, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(PeqParameterGap),
                            verticalAlignment = Alignment.CenterVertically) {
                            PeqFilterNumber(filterNumber, Modifier.weight(TableWeights[0]))
                            PeqActionButton(band.channel.displayLabel, actions::channel, Modifier.weight(TableWeights[1]))
                            PeqActionButton(band.filterType.displayLabel, actions::type, Modifier.weight(TableWeights[2]))
                            repeat(3) { parameter ->
                                PeqTableParameter(band, filterNumber, parameter, actions, false, Modifier.weight(TableWeights[parameter + 3]))
                            }
                            PeqActionButton("×", { holder.deleteBand(index) }, Modifier.weight(TableWeights[6]))
                        }
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
private fun PeqFilterNumber(number: Int, modifier: Modifier) {
    Text("$number", color = Color(peqFilterColor(number - 1)),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = modifier)
}

@Composable
private fun PeqTableParameter(
    band: ParametricEqBand,
    number: Int,
    parameter: Int,
    actions: PeqFilterActions,
    showLabel: Boolean,
    modifier: Modifier,
) {
    val label = when (parameter) { 0 -> "frequency"; 1 -> "gain"; else -> "Q" }
    val value = when (parameter) {
        0 -> "${formatPeqValue(band.frequency)} Hz"
        1 -> "${formatPeqValue(band.gain)} dB"
        else -> formatPeqValue(band.q)
    }
    val edit: () -> Unit = when (parameter) { 0 -> actions::frequency; 1 -> actions::gain; else -> actions::q }
    val step: (Int, Int) -> Unit = when (parameter) { 0 -> actions::stepFrequency; 1 -> actions::stepGain; else -> actions::stepQ }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showLabel) Text(label.uppercase(java.util.Locale.ROOT), color = PeqMutedText, fontSize = 12.sp)
        PeqNumericControl("Filter $number $label", value, edit, step, actions::commit, Modifier.fillMaxWidth())
    }
}
