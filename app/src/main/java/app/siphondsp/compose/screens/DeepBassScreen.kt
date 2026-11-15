package app.siphondsp.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.siphondsp.compose.controls.ArtGroupHeader
import app.siphondsp.compose.controls.ArtLabel
import app.siphondsp.compose.controls.WorkspaceArtBox
import app.siphondsp.compose.controls.artDp
import app.siphondsp.compose.state.BmwDspState
import app.siphondsp.compose.state.rememberBmwDspState
import app.siphondsp.compose.theme.BmwDspTheme
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.utils.BiquadUtils
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.ln
import kotlin.math.pow

/**
 * The Output workspace's DEEP BASS page: the volume-dependent bass boost, after the master volume.
 *
 * - **Deep bass**: the header switch turns it on/off (the engine fades it over 50 ms); Strength
 *   scales the boost; Max boost caps it. Below them, what it is doing at the current master
 *   volume, and how it moves.
 * - **Auto curve**: the boost the engine applies now (solid, with its centre marked) and at the
 *   quietest volume, where it reaches Max boost (dashed). Both are [NativeBmwDspValues.deepBassCurve],
 *   the same bell native DeepBass builds.
 */
@Composable
fun DeepBassScreen(modifier: Modifier = Modifier) {
    val dsp = rememberBmwDspState()
    val controls: @Composable (DeepBassRow) -> Unit = { row -> DeepBassGroup(dsp, row) }
    val curve: @Composable (DeepBassRow) -> Unit = { row -> CurveGroup(dsp, row) }
    BmwDspTheme {
        if (LocalContext.current.isHeadUnitDisplay()) {
            WorkspaceArtBox(modifier.fillMaxSize()) {
                controls { y, h -> Modifier.artRect(artDp(LeftX, GroupTop + y, GroupWidth, h)) }
                curve { y, h -> Modifier.artRect(artDp(RightX, GroupTop + y, GroupWidth, h)) }
            }
        } else {
            PhoneDeepBassPage(controls, curve, modifier)
        }
    }
}

/** Where one row of a group goes: [y] dp down the group on the head unit, [h] tall. */
private typealias DeepBassRow = (y: Int, h: Int) -> Modifier

@Composable
private fun DeepBassGroup(dsp: BmwDspState, row: DeepBassRow) {
    val on = dsp.isOn(NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED)
    ArtGroupHeader(
        title = "Deep bass",
        accent = DeepBassAccent,
        modifier = row(0, HeaderHeight),
        checked = on,
        onCheckedChange = { dsp.commit(NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED, if (it) 1f else 0f) },
    )
    DspArtSlider(
        dsp, "Strength", NativeBmwDspValues.INDEX_DEEP_BASS_STRENGTH, 0f..100f, 5f, "%", DeepBassAccent, row(52, 48),
    )
    DspArtSlider(
        dsp, "Max boost", NativeBmwDspValues.INDEX_DEEP_BASS_MAX_DB,
        NativeBmwDspValues.DEEP_BASS_MIN_MAX_DB..NativeBmwDspValues.DEEP_BASS_MAX_MAX_DB, 1f, "dB",
        DeepBassAccent, row(108, 48),
    )
    val volumeDb = dsp.get(NativeBmwDspValues.INDEX_MASTER_VOLUME_DB)
    val now = NativeBmwDspValues.deepBassCurve(dsp.values)
    InfoLine(
        when {
            !on -> "Off: the bass is left as it is"
            now.gainDb < 0.05 -> "Now: no boost at full volume"
            else -> "Now: +%.1f dB at %.0f Hz".format(now.gainDb, now.centreHz)
        },
        row(172, 28),
        color = if (on) DeepBassAccent else InfoColor,
    )
    InfoLine("Master %s dB, +0.3 dB per dB of cut".format(formatVolume(volumeDb)), row(204, 28))
    InfoLine("Moves 50 Hz to 32 Hz as the volume drops", row(232, 28))
    InfoLine("None at full volume, after the master volume", row(260, 28))
}

@Composable
private fun CurveGroup(dsp: BmwDspState, row: DeepBassRow) {
    ArtGroupHeader("Auto curve", Color.White, row(0, HeaderHeight))
    val now = NativeBmwDspValues.deepBassCurve(dsp.values)
    val quietest = NativeBmwDspValues.deepBassCurve(dsp.values, volumeDb = NativeBmwDspValues.MASTER_VOLUME_MIN_DB.toDouble())
    val description = "Deep bass curve: now +%.1f dB at %.0f Hz, quietest +%.1f dB at %.0f Hz".format(
        now.gainDb, now.centreHz, quietest.gainDb, quietest.centreHz,
    )
    Canvas(row(52, 176).semantics { contentDescription = description }) {
        drawCurveGraph(now, quietest)
    }
    InfoLine("Solid: now. Dashed: quietest (Max boost)", row(236, 28))
}

private fun DrawScope.drawCurveGraph(now: NativeBmwDspValues.DeepBassCurve, quietest: NativeBmwDspValues.DeepBassCurve) {
    val left = 34.dp.toPx()
    val bottom = size.height - 18.dp.toPx()
    val top = 6.dp.toPx()
    val right = size.width - 4.dp.toPx()
    val x = { f: Double -> left + ((ln(f / GraphMinHz) / ln(GraphMaxHz / GraphMinHz)) * (right - left)).toFloat() }
    val y = { db: Double -> bottom - (db.coerceIn(0.0, GraphMaxDb) / GraphMaxDb * (bottom - top)).toFloat() }

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AxisLabelColor.toArgb()
        textSize = 11.dp.toPx()
    }
    for (db in intArrayOf(0, 5, 10, 15)) {
        val gy = y(db.toDouble())
        drawLine(GridColor, Offset(left, gy), Offset(right, gy), 1.dp.toPx())
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText(if (db == 0) "0" else "+$db", left - 6.dp.toPx(), gy + 4.dp.toPx(), paint)
    }
    for (f in doubleArrayOf(20.0, 50.0, 100.0, 200.0)) {
        val gx = x(f)
        drawLine(GridColor, Offset(gx, top), Offset(gx, bottom), 1.dp.toPx())
        paint.textAlign = android.graphics.Paint.Align.CENTER
        val label = if (f == 200.0) "200 Hz" else f.toInt().toString()
        drawContext.canvas.nativeCanvas.drawText(label, gx, size.height - 2.dp.toPx(), paint)
    }

    fun curvePath(c: NativeBmwDspValues.DeepBassCurve): Path {
        val coeffs = BiquadUtils.computeCoefficients(c.centreHz, c.gainDb, NativeBmwDspValues.DEEP_BASS_Q, ParametricEqFilterType.PEAKING, GraphSampleRate)
        return Path().apply {
            for (i in 0..GraphPoints) {
                val f = GraphMinHz * (GraphMaxHz / GraphMinHz).pow(i.toDouble() / GraphPoints)
                val db = if (c.gainDb > 0.0) BiquadUtils.magnitudeResponse(coeffs, f, GraphSampleRate) else 0.0
                if (i == 0) moveTo(x(f), y(db)) else lineTo(x(f), y(db))
            }
        }
    }
    drawPath(
        curvePath(quietest), DeepBassAccent.copy(alpha = .6f),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
    drawPath(curvePath(now), DeepBassAccent, style = Stroke(2.5.dp.toPx()))
    if (now.gainDb > 0.0) {
        drawCircle(DeepBassAccent, 4.dp.toPx(), Offset(x(now.centreHz), y(now.gainDb)))
    }
}

@Composable
private fun InfoLine(text: String, modifier: Modifier, color: Color = InfoColor) {
    Box(modifier, contentAlignment = Alignment.CenterStart) { ArtLabel(text, color = color) }
}

/** Phone: the two groups side by side when they fit, else stacked; scrolls if too tall. */
@Composable
private fun PhoneDeepBassPage(
    left: @Composable (DeepBassRow) -> Unit,
    right: @Composable (DeepBassRow) -> Unit,
    modifier: Modifier,
) {
    val stack: DeepBassRow = { _, h -> Modifier.fillMaxWidth().height(h.dp) }
    val group: @Composable (@Composable (DeepBassRow) -> Unit) -> Unit = { content ->
        Column(Modifier.width(GroupWidth.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content(stack) }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= GroupWidth.dp * 2 + PhoneGroupGap + 24.dp
        Box(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(PhoneGroupGap)) { group(left); group(right) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { group(left); group(right) }
            }
        }
    }
}

private fun formatVolume(db: Float): String = if (db >= 0f) "0" else "%.0f".format(db)

private val DeepBassAccent = Color(BmwDashboardSkin.SLIDER_DEEP_BASS_COLOR)
private val InfoColor = Color(0xFF969EA8)
private val GridColor = Color(0xFF2A2A2A)
private val AxisLabelColor = Color(0xFF777777)
private const val GraphMinHz = 15.0
private const val GraphMaxHz = 300.0
private const val GraphMaxDb = 15.0
private const val GraphPoints = 96
private const val GraphSampleRate = 48_000.0

// Head-unit layout, in the 1280x480 editor's dp: the Output page's two 333dp columns, starting
// under the 69dp toolbar line (status strip + OUTPUT/CONVOLVER/DEEP BASS finder), ending at 364.
private const val GroupWidth = 333
private const val LeftX = 317
private const val RightX = 770
private const val GroupTop = 76
private const val HeaderHeight = 40
private val PhoneGroupGap = 60.dp
