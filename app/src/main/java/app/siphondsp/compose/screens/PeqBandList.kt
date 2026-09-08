package app.siphondsp.compose.screens

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.fragment.PeqDialogs
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.view.BmwDashboardSkin
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.math.pow

// 1/24-octave multiplicative Hz step -- matches ParametricEqBandAdapter.FREQ_STEP_FACTOR.
private val FreqStepFactor = 2.0.pow(1.0 / 24.0)
private val IndexColor = Color(0xFF9AA1AB)
private val AddGlassFill = Color(0xFF14171C)
private val MRed = Color(BmwDashboardSkin.M_RED)

private val FreqFmt = DecimalFormat("0.#", DecimalFormatSymbols.getInstance())
private val GainFmt = DecimalFormat("0.##", DecimalFormatSymbols.getInstance())
private val QFmt = DecimalFormat("0.##", DecimalFormatSymbols.getInstance())

private const val WIndex = 0.5f
private const val WPicker = 1f
private const val WValue = 2.2f

/**
 * Compose port of the Parametric EQ filter list (roadmap Phase 10b) -- a keyed [LazyColumn] of
 * band rows under a fixed column header, plus a trailing "Add filter" row while the scope has
 * room. Channel / Type open [PeqDialogs] choice pickers (interop); Hz / dB / Q each open the
 * numeric dialog and carry inline -/+ steppers; every edit funnels through [PeqStateHolder].
 *
 * No drag-to-reorder (filter tools' Move up/down is the reorder path -- see
 * docs/PEQ_COMPOSE_PLAN.md). Not wired into the fragment here -- that's 10d.
 */
@Composable
fun PeqBandList(
    holder: PeqStateHolder,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val dialogs = remember(context) { PeqDialogs(context, LayoutInflater.from(context)) }
    val accent = Color(holder.scopeAccentArgb)
    val bands = holder.visibleBands
    val selectedUuid = holder.selectedUuid

    Column(modifier.fillMaxWidth()) {
        PeqListHeader()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(bands, key = { _, band -> band.uuid.toString() }) { index, band ->
                PeqBandRow(
                    number = index + 1,
                    band = band,
                    accent = accent,
                    selected = band.uuid == selectedUuid,
                    onChannel = {
                        val channels = ParametricEqChannel.entries
                        dialogs.showChoice(
                            titleRes = R.string.peq_channel,
                            labels = channels.map { it.displayLabel },
                            currentIndex = channels.indexOf(band.channel),
                            accentColor = holder.scopeAccentArgb,
                        ) { picked ->
                            channels.getOrNull(picked)?.let { c ->
                                holder.commitBandEdit(index, "edit_channel") { it.withChannel(c) }
                            }
                        }
                    },
                    onType = {
                        val types = ParametricEqFilterType.entries
                        dialogs.showChoice(
                            titleRes = R.string.peq_filter_type,
                            labels = listOf(
                                context.getString(R.string.peq_filter_type_peaking),
                                context.getString(R.string.peq_filter_type_low_shelf),
                                context.getString(R.string.peq_filter_type_high_shelf),
                                context.getString(R.string.peq_filter_type_notch),
                            ),
                            currentIndex = types.indexOf(band.filterType),
                            accentColor = holder.scopeAccentArgb,
                        ) { picked ->
                            types.getOrNull(picked)?.let { t ->
                                holder.commitBandEdit(index, "edit_filter_type") { it.withType(t) }
                            }
                        }
                    },
                    onFreq = {
                        dialogs.showValueInput("Frequency", band.frequency, 20.0, 20000.0, "Hz") { v ->
                            holder.commitBandEdit(index, "edit_frequency") { it.withFrequency(v) }
                        }
                    },
                    onFreqStep = { up ->
                        val factor = if (up) FreqStepFactor else 1.0 / FreqStepFactor
                        holder.commitBandEdit(index, "edit_frequency_step") {
                            it.withFrequency((it.frequency * factor).coerceIn(20.0, 20000.0))
                        }
                    },
                    onGain = {
                        dialogs.showValueInput("Gain", band.gain, -30.0, 30.0, "dB") { v ->
                            holder.commitBandEdit(index, "edit_gain") { it.withGain(v) }
                        }
                    },
                    onGainStep = { delta ->
                        holder.commitBandEdit(index, "edit_gain_step") {
                            it.withGain((it.gain + delta).coerceIn(-30.0, 30.0))
                        }
                    },
                    onQ = {
                        dialogs.showValueInput("Q", band.q, 0.1, 30.0, null) { v ->
                            holder.commitBandEdit(index, "edit_q") { it.withQ(v) }
                        }
                    },
                    onQStep = { delta ->
                        holder.commitBandEdit(index, "edit_q_step") {
                            it.withQ((it.q + delta).coerceIn(0.1, 30.0))
                        }
                    },
                    onDelete = { holder.deleteBand(index) },
                )
            }
            if (bands.size < BmwPeqState.MAX_BANDS) {
                item(key = "add-row") { PeqAddRow(accent = accent, onClick = { holder.addBand() }) }
            }
        }
    }
}

@Composable
private fun PeqListHeader() {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("#", WIndex)
        HeaderCell(context.getString(R.string.peq_col_ch), WPicker)
        HeaderCell(context.getString(R.string.peq_col_filter), WPicker)
        HeaderCell("Hz", WValue)
        HeaderCell("dB", WValue)
        HeaderCell("Q", WValue)
        Spacer(Modifier.width(28.dp)) // delete column
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        color = IndexColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun PeqBandRow(
    number: Int,
    band: ParametricEqBand,
    accent: Color,
    selected: Boolean,
    onChannel: () -> Unit,
    onType: () -> Unit,
    onFreq: () -> Unit,
    onFreqStep: (Boolean) -> Unit,
    onGain: () -> Unit,
    onGainStep: (Double) -> Unit,
    onQ: () -> Unit,
    onQStep: (Double) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .then(if (selected) Modifier.border(1.dp, accent, RoundedCornerShape(6.dp)) else Modifier)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$number",
            color = if (selected) accent else IndexColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(WIndex),
        )
        TapCell(band.channel.displayLabel, accent, WPicker, onChannel)
        TapCell(band.filterType.displayLabel, accent, WPicker, onType)
        StepperCell(FreqFmt.format(band.frequency), accent, onFreq, { onFreqStep(false) }, { onFreqStep(true) })
        StepperCell(GainFmt.format(band.gain), accent, onGain, { onGainStep(-0.5) }, { onGainStep(0.5) })
        StepperCell(QFmt.format(band.q), accent, onQ, { onQStep(-0.1) }, { onQStep(0.1) })
        Glyph("×", MRed, Modifier.size(28.dp), onDelete)
    }
}

@Composable
private fun RowScope.TapCell(text: String, accent: Color, weight: Float, onClick: () -> Unit) {
    Text(
        text = text,
        color = accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(weight).clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

@Composable
private fun RowScope.StepperCell(
    value: String,
    accent: Color,
    onValueClick: () -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.weight(WValue),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Glyph("−", accent, Modifier.size(24.dp), onMinus)
        Text(
            text = value,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clickable(onClick = onValueClick).padding(vertical = 6.dp),
        )
        Glyph("+", accent, Modifier.size(24.dp), onPlus)
    }
}

@Composable
private fun Glyph(text: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text = text, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PeqAddRow(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .background(AddGlassFill, RoundedCornerShape(6.dp))
            .border(1.dp, accent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+  Add filter", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ParametricEqBand is not a data class -- small copy helpers for single-field edits.
private fun ParametricEqBand.withFrequency(f: Double) = ParametricEqBand(f, gain, q, filterType, channel, uuid)
private fun ParametricEqBand.withGain(g: Double) = ParametricEqBand(frequency, g, q, filterType, channel, uuid)
private fun ParametricEqBand.withQ(v: Double) = ParametricEqBand(frequency, gain, v, filterType, channel, uuid)
private fun ParametricEqBand.withType(t: ParametricEqFilterType) = ParametricEqBand(frequency, gain, q, t, channel, uuid)
private fun ParametricEqBand.withChannel(c: ParametricEqChannel) = ParametricEqBand(frequency, gain, q, filterType, c, uuid)
