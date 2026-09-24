package app.siphondsp.compose.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.BmwSlider
import app.siphondsp.compose.controls.BmwSwitch
import app.siphondsp.compose.controls.BoxedValue
import app.siphondsp.compose.controls.WorkspaceArt
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.bmwFocusRing
import app.siphondsp.compose.controls.showBmwNumberInput
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ThreeWayCrossover
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.roundToInt

/**
 * One Gains & Delay page per crossover band (Phase 5 of the 3-way crossover,
 * docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md): a full-height Left and Right [BandSidePanel] for
 * that band -- Delay, Polarity, Gain (+ slider) and the shared Stage Alignment, one per row --
 * with the global STEREO LINK under the car.
 *
 * The car itself isn't drawn here: each band has its own full-screen workspace backdrop
 * ([GainsBand.backdrop], swapped in by `GainLimiterFragment` as the pager moves) with the car and
 * that band's speakers + leader lines baked in. The car fills the middle of the art, so the
 * panels are width-limited but get the whole height; on a screen too short for every row (a
 * landscape phone) each panel scrolls rather than clipping.
 *
 * High only makes sound with the Crossovers page's 3-way switch on; with it off the High page
 * stays in the pager (so the page count never changes) but greyed out and inert, with a pointer
 * to where 3-way is turned on.
 */
enum class GainsBand(
    val title: String,
    val leftOutput: Int,
    val rightOutput: Int,
    val gainL: Int,
    val gainR: Int,
    val delayL: Int,
    val delayR: Int,
    val accent: Int,
    val stroke: Int,
    val slider: Int,
    val backdrop: Int,
    val backdropPhone: Int,
) {
    HIGH(
        "High", NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.OUTPUT_HIGH_RIGHT,
        NativeBmwDspValues.INDEX_HIGH_GAIN_L, NativeBmwDspValues.INDEX_HIGH_GAIN_R,
        NativeBmwDspValues.INDEX_HIGH_DELAY_L, NativeBmwDspValues.INDEX_HIGH_DELAY_R,
        BmwDashboardSkin.HIGH_BAND_PINK, BmwDashboardSkin.HIGH_BAND_PINK, BmwDashboardSkin.SLIDER_HIGH_BAND_COLOR,
        R.drawable.dsp_workspace_backdrop_v4_high, R.drawable.dsp_workspace_backdrop_gains_tweeter_phone,
    ),
    MID(
        "Mid", NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.OUTPUT_MID_RIGHT,
        NativeBmwDspValues.INDEX_MID_GAIN_L, NativeBmwDspValues.INDEX_MID_GAIN_R,
        NativeBmwDspValues.INDEX_MID_DELAY_L, NativeBmwDspValues.INDEX_MID_DELAY_R,
        BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.MID_BAND_YELLOW, BmwDashboardSkin.SLIDER_MID_BAND_COLOR,
        R.drawable.dsp_workspace_backdrop_v4_mid, R.drawable.dsp_workspace_backdrop_gains_mid_phone,
    ),
    LOW(
        "Low", NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.OUTPUT_LOW_RIGHT,
        NativeBmwDspValues.INDEX_LOW_GAIN_L, NativeBmwDspValues.INDEX_LOW_GAIN_R,
        NativeBmwDspValues.INDEX_LOW_DELAY_L, NativeBmwDspValues.INDEX_LOW_DELAY_R,
        BmwDashboardSkin.LIGHT_BLUE, BmwDashboardSkin.M_BLUE, BmwDashboardSkin.SLIDER_LOW_BAND_COLOR,
        R.drawable.dsp_workspace_backdrop_v4_low, R.drawable.dsp_workspace_backdrop_gains_woofer_phone,
    );

    /** High's per-output config lives in the schema tail, not the legacy 4-output block. */
    fun polarityIndex(output: Int): Int =
        if (this == HIGH) NativeBmwDspValues.highOutputIndex(output, NativeBmwDspValues.FIELD_INVERT)
        else NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)
}

/**
 * [onSelectBand] is called when a band label in the head-unit car art is tapped (not for the band
 * already shown).
 */
@Composable
fun GainsDelayScreen(band: GainsBand, modifier: Modifier = Modifier, onSelectBand: (GainsBand) -> Unit = {}) {
    val dsp = rememberBmwDspState()
    val linked = dsp.isOn(NativeBmwDspValues.INDEX_DELAY_LINKED)
    val inactive = band == GainsBand.HIGH && !ThreeWayCrossover.isEnabled(dsp.values)

    if (LocalContext.current.isHeadUnitDisplay()) {
        BmwDspTheme { HeadUnitBandPage(dsp, linked, band, inactive, onSelectBand, modifier) }
        return
    }

    BmwDspTheme {
        Box(modifier = modifier.fillMaxSize()) {
            BandSidePanel(
                dsp, linked, band, inactive,
                title = "Left ${band.title}", output = band.leftOutput,
                delayIndex = band.delayL, delaySibling = band.delayR, gainIndex = band.gainL,
                stageIndex = NativeBmwDspValues.INDEX_STAGE_DELAY_L,
                mirrored = false,
                modifier = Modifier.align(Alignment.TopStart).padding(start = StartInset, top = PanelTop, bottom = BottomInset),
            )
            BandSidePanel(
                dsp, linked, band, inactive,
                title = "Right ${band.title}", output = band.rightOutput,
                delayIndex = band.delayR, delaySibling = band.delayL, gainIndex = band.gainR,
                stageIndex = NativeBmwDspValues.INDEX_STAGE_DELAY_R,
                mirrored = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = EndInset, top = PanelTop, bottom = BottomInset),
            )
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = BottomInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "STEREO LINK",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp),
                )
                BmwSwitch(
                    checked = linked,
                    onCheckedChange = {
                        dsp.commit(NativeBmwDspValues.INDEX_DELAY_LINKED, if (it) 1f else 0f)
                    },
                    contentDescription = "Link left and right speaker delay",
                )
            }
            if (inactive) {
                Text(
                    text = "3-way is off: turn it on in Crossovers",
                    color = Color(band.accent),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = InactiveNoteBottom),
                )
            }
        }
    }
}

/**
 * One side of a band page: a full-height panel with a row each for Delay, Polarity, Gain (value
 * + full-width slider) and Stage Alignment. [mirrored] (the Right side) puts labels on the outer
 * right edge and controls toward the car, matching the Left side's reflection.
 *
 * When [inactive] it's dimmed, swallows taps (a swipe still reaches the pager, since a click
 * detector doesn't consume drags) and can't take D-pad/rotary focus.
 */
@Composable
private fun BandSidePanel(
    dsp: BmwDspState,
    linked: Boolean,
    band: GainsBand,
    inactive: Boolean,
    title: String,
    output: Int,
    delayIndex: Int,
    delaySibling: Int,
    gainIndex: Int,
    stageIndex: Int,
    mirrored: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val accent = Color(band.accent)
    val stageAccent = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)
    val polarityIndex = band.polarityIndex(output)
    val delayMirror = if (linked) intArrayOf(delaySibling) else IntArray(0)

    Box(modifier.width(PanelWidth).fillMaxHeight()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(PanelBackground, PanelShape)
                .border(1.dp, Color(band.stroke), PanelShape)
                .then(if (inactive) Modifier.alpha(InactiveAlpha) else Modifier)
                .focusProperties { onEnter = { if (inactive) cancelFocusChange() } }
                .focusGroup(),
        ) {
            val panelHeight = maxHeight
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = panelHeight)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Text(
                    text = title,
                    color = accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = if (mirrored) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                PanelRow("DELAY", mirrored) {
                    TapValue(
                        text = DelayFormat.format(dsp.get(delayIndex)), unit = "ms", accent = accent,
                    ) {
                        context.showBmwNumberInput(
                            "DELAY", DelayRange.start, DelayRange.endInclusive, dsp.get(delayIndex), 0f, "ms",
                        ) { dsp.commit(delayIndex, it, delayMirror) }
                    }
                }
                PanelRow("POLARITY", mirrored) {
                    BmwSwitch(
                        checked = dsp.isOn(polarityIndex),
                        onCheckedChange = { dsp.commit(polarityIndex, if (it) 1f else 0f) },
                        contentDescription = "$title polarity",
                        onColor = PolInvertPink,
                        offColor = PolNormalGreen,
                        onLabel = "INVERT",
                        offLabel = "NORMAL",
                        width = ValueWidth,
                    )
                }
                GainRows(dsp, gainIndex, accent, Color(band.slider), mirrored)
                PanelRow("STAGE ALIGN", mirrored) {
                    TapValue(
                        text = DelayFormat.format(dsp.get(stageIndex)), unit = "ms", accent = stageAccent,
                    ) {
                        context.showBmwNumberInput(
                            "STAGE ALIGNMENT", 0f, NativeBmwDspValues.STAGE_DELAY_MAX_MS, dsp.get(stageIndex), 0.05f, "ms",
                        ) { dsp.commit(stageIndex, it) }
                    }
                }
            }
        }
        if (inactive) {
            Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            )
        }
    }
}

/**
 * Head unit, v4 art: no panels of its own -- the art's colour-coded band frame is the box. Each
 * group sits over its [BandArtLayout] rect: per side, a column (Delay, Polarity, Stage align,
 * label above control) beside the car and the Gain row + slider in the wide corner under the door;
 * Stereo link under the left column. The band labels baked into the car art are tap targets.
 */
@Composable
private fun HeadUnitBandPage(
    dsp: BmwDspState,
    linked: Boolean,
    band: GainsBand,
    inactive: Boolean,
    onSelectBand: (GainsBand) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val accent = Color(band.accent)
    val stageAccent = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)
    val art = band.artLayout
    val dim = if (inactive) {
        Modifier.alpha(InactiveAlpha).focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()
    } else {
        Modifier
    }

    WorkspaceArtBox(modifier.fillMaxSize()) {
        for (mirrored in listOf(false, true)) {
            val output = if (mirrored) band.rightOutput else band.leftOutput
            val delayIndex = if (mirrored) band.delayR else band.delayL
            val delaySibling = if (mirrored) band.delayL else band.delayR
            val stageIndex = if (mirrored) NativeBmwDspValues.INDEX_STAGE_DELAY_R else NativeBmwDspValues.INDEX_STAGE_DELAY_L
            val polarityIndex = band.polarityIndex(output)
            val delayMirror = if (linked) intArrayOf(delaySibling) else IntArray(0)
            val mainRect = if (mirrored) art.rightMain else art.leftMain
            val gainRect = if (mirrored) art.rightGain else art.leftGain

            Column(
                Modifier.artRect(mainRect).then(dim),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                StackedControl("DELAY", mirrored) {
                    TapValue(DelayFormat.format(dsp.get(delayIndex)), "ms", accent, Modifier.fillMaxWidth()) {
                        context.showBmwNumberInput(
                            "DELAY", DelayRange.start, DelayRange.endInclusive, dsp.get(delayIndex), 0f, "ms",
                        ) { dsp.commit(delayIndex, it, delayMirror) }
                    }
                }
                StackedControl("POLARITY", mirrored) {
                    BmwSwitch(
                        checked = dsp.isOn(polarityIndex),
                        onCheckedChange = { dsp.commit(polarityIndex, if (it) 1f else 0f) },
                        contentDescription = "${if (mirrored) "Right" else "Left"} ${band.title} polarity",
                        onColor = PolInvertPink,
                        offColor = PolNormalGreen,
                        onLabel = "INVERT",
                        offLabel = "NORMAL",
                        width = ValueWidth,
                    )
                }
                StackedControl("STAGE ALIGN", mirrored) {
                    TapValue(DelayFormat.format(dsp.get(stageIndex)), "ms", stageAccent, Modifier.fillMaxWidth()) {
                        context.showBmwNumberInput(
                            "STAGE ALIGNMENT", 0f, NativeBmwDspValues.STAGE_DELAY_MAX_MS, dsp.get(stageIndex), 0.05f, "ms",
                        ) { dsp.commit(stageIndex, it) }
                    }
                }
            }
            Box(Modifier.artRect(gainRect).then(dim), contentAlignment = Alignment.Center) {
                GainRows(dsp, if (mirrored) band.gainR else band.gainL, accent, Color(band.slider), mirrored)
            }
            if (inactive) {
                // Swallow taps on the dimmed controls; a swipe still reaches the pager.
                for (rect in listOf(mainRect, gainRect)) {
                    Box(
                        Modifier.artRect(rect).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {},
                    )
                }
            }
        }
        Row(Modifier.artRect(art.stereoLink), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "STEREO LINK",
                color = LabelColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.03.em,
            )
            Spacer(Modifier.weight(1f))
            BmwSwitch(
                checked = linked,
                onCheckedChange = { dsp.commit(NativeBmwDspValues.INDEX_DELAY_LINKED, if (it) 1f else 0f) },
                contentDescription = "Link left and right speaker delay",
            )
        }
        if (inactive) {
            Text(
                text = "3-way is off: turn it on in Crossovers",
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.artRect(InactiveNoteRect),
            )
        }
        for (target in GainsBand.entries) {
            Box(
                Modifier.artRect(art.tapTargets.getValue(target)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Tab,
                    onClickLabel = "${target.title} band",
                ) { if (target != band) onSelectBand(target) },
            )
        }
    }
}

/** A small label with its control under it at the column's full width; [mirrored] right-aligns
 *  the label (the Right side, labels on the outer edge). */
@Composable
private fun StackedControl(label: String, mirrored: Boolean, control: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.03.em,
        )
        control()
    }
}

/**
 * Where a band page's controls and tap targets sit on its v4 car art, as art fractions (placed in
 * REW/_UI/workspace_layout_placer_v4.html; the band labels move between the three images).
 */
private class BandArtLayout(
    val leftMain: WorkspaceArt.Frac,
    val rightMain: WorkspaceArt.Frac,
    val leftGain: WorkspaceArt.Frac,
    val rightGain: WorkspaceArt.Frac,
    val stereoLink: WorkspaceArt.Frac,
    val tapTargets: Map<GainsBand, WorkspaceArt.Frac>,
)

private val LeftMain = WorkspaceArt.Frac(0.145f, 0.15f, 0.15f, 0.4f)
private val RightMain = WorkspaceArt.Frac(0.805f, 0.15f, 0.155f, 0.4f)
private val StereoLinkRect = WorkspaceArt.Frac(0.145f, 0.565f, 0.15f, 0.07f)
// High only: under the right column, clear of the right gain row.
private val InactiveNoteRect = WorkspaceArt.Frac(0.805f, 0.565f, 0.155f, 0.08f)

private val HighArtLayout = BandArtLayout(
    LeftMain, RightMain,
    leftGain = WorkspaceArt.Frac(0.145f, 0.655f, 0.245f, 0.2059f),
    rightGain = WorkspaceArt.Frac(0.6974f, 0.653f, 0.2626f, 0.212f),
    stereoLink = StereoLinkRect,
    tapTargets = mapOf(
        GainsBand.HIGH to WorkspaceArt.Frac(0.3905f, 0.1493f, 0.3185f, 0.1227f),
        GainsBand.MID to WorkspaceArt.Frac(0.4855f, 0.44f, 0.1215f, 0.0853f),
        GainsBand.LOW to WorkspaceArt.Frac(0.484f, 0.776f, 0.121f, 0.0853f),
    ),
)
private val MidArtLayout = BandArtLayout(
    LeftMain, RightMain,
    leftGain = WorkspaceArt.Frac(0.145f, 0.655f, 0.2458f, 0.212f),
    rightGain = WorkspaceArt.Frac(0.6981f, 0.655f, 0.2611f, 0.21f),
    stereoLink = StereoLinkRect,
    tapTargets = mapOf(
        GainsBand.HIGH to WorkspaceArt.Frac(0.49f, 0.2107f, 0.1135f, 0.0853f),
        GainsBand.MID to WorkspaceArt.Frac(0.38f, 0.4027f, 0.336f, 0.16f),
        GainsBand.LOW to WorkspaceArt.Frac(0.484f, 0.7773f, 0.121f, 0.0867f),
    ),
)
private val LowArtLayout = BandArtLayout(
    LeftMain, RightMain,
    leftGain = WorkspaceArt.Frac(0.145f, 0.655f, 0.245f, 0.21f),
    rightGain = WorkspaceArt.Frac(0.6989f, 0.655f, 0.2619f, 0.21f),
    stereoLink = StereoLinkRect,
    tapTargets = mapOf(
        GainsBand.HIGH to WorkspaceArt.Frac(0.491f, 0.208f, 0.1115f, 0.0853f),
        GainsBand.MID to WorkspaceArt.Frac(0.488f, 0.444f, 0.1155f, 0.0813f),
        GainsBand.LOW to WorkspaceArt.Frac(0.3975f, 0.716f, 0.2925f, 0.1613f),
    ),
)

private val GainsBand.artLayout: BandArtLayout
    get() = when (this) {
        GainsBand.HIGH -> HighArtLayout
        GainsBand.MID -> MidArtLayout
        GainsBand.LOW -> LowArtLayout
    }

/** GAIN label + tap-to-type value on one row, then a full-width slider under it. */
@Composable
private fun GainRows(
    dsp: BmwDspState,
    gainIndex: Int,
    accent: Color,
    sliderAccent: Color,
    mirrored: Boolean,
) {
    val context = LocalContext.current
    val committed = dsp.get(gainIndex)
    var drag by remember(committed) { mutableFloatStateOf(committed) }
    val shown = drag.coerceIn(GainRange.start, GainRange.endInclusive)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelRow("GAIN", mirrored) {
            TapValue(text = DelayFormat.format(shown), unit = "dB", accent = accent) {
                context.showBmwNumberInput("GAIN", GainRange.start, GainRange.endInclusive, drag, GainStep, "dB") {
                    drag = it
                    dsp.commit(gainIndex, it)
                }
            }
        }
        BmwSlider(
            value = shown,
            valueRange = GainRange,
            steps = (((GainRange.endInclusive - GainRange.start) / GainStep).roundToInt() - 1).coerceAtLeast(0),
            accentColor = sliderAccent,
            onValueChange = {
                val snapped = snapGain(it)
                drag = snapped
                dsp.preview(gainIndex, snapped)
            },
            onValueChangeFinished = { dsp.commit(gainIndex, drag) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Label on the outer edge, [control] toward the car. */
@Composable
private fun PanelRow(label: String, mirrored: Boolean, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = RowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val labelText: @Composable () -> Unit = {
            Text(
                text = label,
                color = LabelColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.03.em,
            )
        }
        if (mirrored) {
            control()
            Spacer(Modifier.weight(1f))
            labelText()
        } else {
            labelText()
            Spacer(Modifier.weight(1f))
            control()
        }
    }
}

/** Boxed value that opens the number-input dialog on tap, with the app's focus ring. */
@Composable
private fun TapValue(
    text: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier.width(ValueWidth),
    onTap: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    BoxedValue(
        text = text,
        unit = unit,
        accentColor = accent,
        modifier = modifier
            .height(ValueHeight)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onTap)
            .bmwFocusRing(interactionSource),
    )
}

private fun snapGain(raw: Float): Float {
    val snapped = GainRange.start + ((raw - GainRange.start) / GainStep).roundToInt() * GainStep
    return snapped.coerceIn(GainRange.start, GainRange.endInclusive)
}

private const val InactiveAlpha = 0.35f
private val PanelShape = RoundedCornerShape(10.dp)
private val PanelBackground = Color(0x99100818)
private val LabelColor = Color(0xFF969EA8)
private val PolNormalGreen = Color(BmwDashboardSkin.M_GREEN)
// Pink for inverted, shared with the stage-alignment accent so "offset from normal" reads the same.
private val PolInvertPink = Color(BmwDashboardSkin.SLIDER_STAGE_COLOR)
// Narrowest the rows fit in (label + 132 dp control + padding). At the right-hand end inset this
// keeps the Right panel clear of the Mid art's right door speaker, the art's widest point.
private val PanelWidth = 264.dp
private val StartInset = 16.dp
// The head unit's bezel eats ~14 dp more on the right than the content frame suggests.
private val EndInset = 30.dp
private val PanelTop = 8.dp
private val BottomInset = 20.dp
// Stacks the High page's "3-way is off" note above the STEREO LINK row, still clear of the car.
private val InactiveNoteBottom = 60.dp
private val RowHeight = 44.dp
private val ValueWidth = 132.dp
private val ValueHeight = 40.dp
private val DelayFormat = java.text.DecimalFormat(
    "0.##",
    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH),
)
private val DelayRange = 0f..2.8f
private val GainRange = -6f..6f
private const val GainStep = 0.5f
