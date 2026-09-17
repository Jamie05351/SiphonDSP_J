package app.siphondsp.compose.screens

import android.view.LayoutInflater
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.draw.clip
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
import app.siphondsp.compose.controls.bmwFocusRing
import app.siphondsp.view.BmwDashboardSkin
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.math.pow

// 1/24-octave multiplicative Hz step -- matches ParametricEqBandAdapter.FREQ_STEP_FACTOR.
private val FreqStepFactor = 2.0.pow(1.0 / 24.0)
private val IndexColor = Color(0xFF9AA1AB)
private val AddGlassFill = Color(0xFF14171C)
private val TapCellFill = Color(0xFF1B1F26)
private val GlyphButtonFill = Color(0xFF23272F)
private val MRed = Color(BmwDashboardSkin.M_RED)

private val FreqFmt = DecimalFormat("0.#", DecimalFormatSymbols.getInstance())
private val GainFmt = DecimalFormat("0.##", DecimalFormatSymbols.getInstance())
private val QFmt = DecimalFormat("0.##", DecimalFormatSymbols.getInstance())

private const val WIndex = 0.5f
private const val WPicker = 1.1f
private const val WValue = 3.0f

private val CellGap = 6.dp
private val StepButtonSize = 48.dp
private val DeleteButtonSize = 40.dp
// Every tappable cell in a row (channel/type pickers, value boxes, +/- steppers) shares this
// height so the row reads as one aligned strip instead of boxes of varying heights.
private val RowControlHeight = StepButtonSize
// Extra breathing room between the Hz/dB/Q groups (and before the delete column), on top of the
// tight CellGap already used *within* a group (value box hugging its own -/+ pair).
private val GroupGap = 10.dp
// The +/- steppers are a neutral gray (not the band's accent) so the accent-colored value box
// reads as the thing to look at, not the buttons either side of it.
private val StepperGlyphColor = IndexColor

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
        Spacer(Modifier.width(DeleteButtonSize)) // delete column
    }
}

// Matches TapCell/StepperCell's own 13sp -- big enough to read at a glance (vs. the original
// 11sp) while still reliably fitting "FILTER", the longest header label, in its column.
private val HeaderFontSize = 13.sp

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        color = IndexColor,
        fontSize = HeaderFontSize,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
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
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CellGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$number",
            color = if (selected) accent else IndexColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(WIndex)
                .then(
                    if (selected) {
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.18f))
                            .border(1.dp, accent, RoundedCornerShape(6.dp))
                    } else Modifier,
                )
                .padding(vertical = 4.dp),
        )
        TapCell(band.channel.displayLabel, accent, WPicker, onChannel)
        TapCell(band.filterType.displayLabel, accent, WPicker, onType)
        StepperCell(FreqFmt.format(band.frequency), accent, onFreq, { onFreqStep(false) }, { onFreqStep(true) })
        StepperCell(GainFmt.format(band.gain), accent, onGain, { onGainStep(-0.5) }, { onGainStep(0.5) })
        StepperCell(QFmt.format(band.q), accent, onQ, { onQStep(-0.1) }, { onQStep(0.1) })
        Glyph("×", MRed, Modifier.padding(start = GroupGap).size(DeleteButtonSize), onDelete)
    }
}

@Composable
private fun RowScope.TapCell(text: String, accent: Color, weight: Float, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(weight)
            .height(RowControlHeight)
            .clip(RoundedCornerShape(6.dp))
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .bmwFocusRing(interactionSource)
            .background(TapCellFill)
            .wrapContentHeight(Alignment.CenterVertically),
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
    // Value box on the left; the −/+ pair grouped together on the right as big, obvious hit
    // targets so stepping a value isn't a guessing game between three near-touching controls.
    // Extra horizontal padding on the group as a whole (GroupGap) separates this Hz/dB/Q cluster
    // from its neighbors, while the tighter spacedBy(6.dp) below keeps value/−/+ visually one unit.
    Row(
        modifier = Modifier.weight(WValue).padding(horizontal = GroupGap / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val valueInteractionSource = remember { MutableInteractionSource() }
        Text(
            text = value,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable(interactionSource = valueInteractionSource, indication = LocalIndication.current, onClick = onValueClick)
                .bmwFocusRing(valueInteractionSource)
                .background(TapCellFill)
                .border(1.dp, accent, RoundedCornerShape(6.dp))
                .padding(vertical = 12.dp),
        )
        MinusPlusGroup(StepperGlyphColor, onMinus, onPlus)
    }
}

// Reclaims the width the old separate −/+ buttons (2x48dp + a gap between them) spent, so the
// value box -- the thing actually being read -- gets more of it: one pill-shaped control, split
// by a thin divider in the same gray as the glyphs, rather than two independent buttons.
private val StepperHalfWidth = 40.dp

@Composable
private fun MinusPlusGroup(glyphColor: Color, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier
            .height(RowControlHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(GlyphButtonFill),
    ) {
        GlyphHalf("−", glyphColor, Modifier.width(StepperHalfWidth).fillMaxHeight(), onMinus)
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight(0.6f)
                .align(Alignment.CenterVertically)
                .background(glyphColor),
        )
        GlyphHalf("+", glyphColor, Modifier.width(StepperHalfWidth).fillMaxHeight(), onPlus)
    }
}

@Composable
private fun GlyphHalf(text: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .bmwFocusRing(interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** A stepper / delete button: a filled rounded hit target with a large glyph so it's not an
 *  accidental touch next to the value box. */
@Composable
private fun Glyph(text: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GlyphButtonFill)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .bmwFocusRing(interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PeqAddRow(accent: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .background(AddGlassFill, RoundedCornerShape(6.dp))
            .border(1.dp, accent, RoundedCornerShape(6.dp))
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .bmwFocusRing(interactionSource)
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
