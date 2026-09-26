package app.siphondsp.compose.screens

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.compose.controls.ArtKnob
import app.siphondsp.compose.controls.ArtRow
import app.siphondsp.compose.controls.ArtSlider
import app.siphondsp.compose.state.BmwDspState

private val NoMirrors = IntArray(0)

/** An [ArtSlider] bound to one DSP value (plus [mirrors], written alongside it). */
@Composable
internal fun DspArtSlider(
    dsp: BmwDspState,
    label: String,
    index: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accent: Color,
    modifier: Modifier,
    labelAbove: Boolean = false,
    mirrors: IntArray = NoMirrors,
    valueWidth: Dp = 96.dp,
    sliderMinTouchHeight: Dp = 48.dp,
) {
    ArtSlider(
        label = label,
        value = dsp.get(index),
        valueRange = range,
        step = step,
        unit = unit,
        accentColor = accent,
        onPreview = { dsp.preview(index, it, mirrors) },
        onCommit = { dsp.commit(index, it, mirrors) },
        labelAbove = labelAbove,
        valueWidth = valueWidth,
        sliderMinTouchHeight = sliderMinTouchHeight,
        modifier = modifier,
    )
}

/** An [ArtKnob] bound to one DSP value (plus [mirrors], written alongside it). */
@Composable
internal fun DspArtKnob(
    dsp: BmwDspState,
    label: String,
    index: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accent: Color,
    modifier: Modifier,
    mirrors: IntArray = NoMirrors,
    diameter: Dp = 104.dp,
    valueWidth: Dp = 96.dp,
) {
    ArtKnob(
        label = label,
        value = dsp.get(index),
        valueRange = range,
        step = step,
        unit = unit,
        accentColor = accent,
        onPreview = { dsp.preview(index, it, mirrors) },
        onCommit = { dsp.commit(index, it, mirrors) },
        diameter = diameter,
        valueWidth = valueWidth,
        modifier = modifier,
    )
}

/** "GR" beside a gain-reduction [meter] that fills the rest of the row. */
@Composable
internal fun ArtMeterRow(modifier: Modifier, meter: @Composable RowScope.(Modifier) -> Unit) {
    ArtRow("GR", modifier, labelWidth = 40.dp) { meter(Modifier.weight(1f)) }
}
