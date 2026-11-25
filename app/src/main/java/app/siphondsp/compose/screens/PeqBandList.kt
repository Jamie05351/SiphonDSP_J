package app.siphondsp.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.model.BmwPeqState

private val TableWeights = listOf(44f, 88f, 116f, 242f, 242f, 222f, 54f)

/** The second, full-size page. Narrow displays scroll horizontally rather than shrink targets. */
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
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val tableWidth = maxOf(maxWidth, 1040.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth).fillMaxHeight()) {
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
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(bands, key = { _, band -> band.uuid.toString() }) { index, band ->
                    val actions = rememberPeqFilterActions(holder, index)
                    val filterNumber = holder.filterNumberOffset + index + 1
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (band.uuid == holder.selectedUuid) PeqPanelFill else Color.Transparent)
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(PeqParameterGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$filterNumber", color = Color(peqFilterColor(filterNumber - 1)),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(TableWeights[0]))
                        PeqActionButton(band.channel.displayLabel, actions::channel, Modifier.weight(TableWeights[1]))
                        PeqActionButton(band.filterType.displayLabel, actions::type, Modifier.weight(TableWeights[2]))
                        PeqNumericControl("Filter $filterNumber frequency", "${formatPeqValue(band.frequency)} Hz",
                            actions::frequency, actions::stepFrequency, actions::commit, Modifier.weight(TableWeights[3]))
                        PeqNumericControl("Filter $filterNumber gain", "${formatPeqValue(band.gain)} dB",
                            actions::gain, actions::stepGain, actions::commit, Modifier.weight(TableWeights[4]))
                        PeqNumericControl("Filter $filterNumber Q", formatPeqValue(band.q),
                            actions::q, actions::stepQ, actions::commit, Modifier.weight(TableWeights[5]))
                        PeqActionButton("×", { holder.deleteBand(index) }, Modifier.weight(TableWeights[6]))
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
