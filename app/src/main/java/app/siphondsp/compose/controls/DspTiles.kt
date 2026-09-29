package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Palette (sampled from the reference renders)
// ─────────────────────────────────────────────────────────────────────────────
object DspColors {
    val Peq = Color(0xFF0A8EDF)
    val Delay = Color(0xFF2BB46B)
    val Xover = Color(0xFFF0A608)
    val XoverLow = Color(0xFFFF6A00)
    val Comp = Color(0xFFEA1A26)
    val Allpass = Color(0xFFAE4AF6)
    val Neutral = Color(0xFF9A9CA0)

    val TileTop = Color(0xFF1E2327)
    val TileBottom = Color(0xFF060809)
    val BezelHi = Color(0xFF8E9194)
    val BezelLo = Color(0xFF222426)
    val Label = Color(0xFFC9CCCF)

    // PEQ band colours from the reference icon
    val BandMagenta = Color(0xFFD64BE0)
    val BandBlue = Color(0xFF1E9BFF)
    val BandAmber = Color(0xFFF2A100)
    val BandGreen = Color(0xFF2FCB6B)
}

// ─────────────────────────────────────────────────────────────────────────────
// Tile shell: metal bezel + accent ring + dark face + label.
// Everything is a fraction of the tile width, so it scales to any resolution.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DspTile(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    // What accessibility services announce in place of the short visible [label] (e.g. the rail's
    // "PEQ" tile reads as the full screen name). Null announces the label itself.
    a11yLabel: String? = null,
    glyph: @Composable () -> Unit
) {
    val interaction = interactionSource
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(80), label = "press")
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "glow")

    BoxWithConstraints(
        modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind { drawShell(accent, glow) }
            .then(
                // A null onClick makes the tile purely visual: no click, no button semantics. The home
                // page uses that, because each tile's host ComposeView owns the click and the a11y label.
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClickLabel = a11yLabel ?: label,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .then(if (a11yLabel != null) Modifier.semantics { contentDescription = a11yLabel } else Modifier)
    ) {
        val w = maxWidth
        // 10 % of the tile, at least 12sp, then shrunk (not below LabelMinSp) if the label would be
        // wider than the tile's inner 90 %: a short landscape phone makes rail tiles small.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labelSize = remember(label, w, density) {
            with(density) {
                val base = maxOf((w * 0.10f).toSp().value, 12f)
                val width = measurer.measure(label, TextStyle(fontSize = base.sp, fontWeight = FontWeight.Normal)).size.width
                val room = (w * 0.90f).toPx()
                (if (width > room) maxOf(base * room / width, LabelMinSp) else base).sp
            }
        }
        // glyph area: x 10–90 %, y 11–67 %
        Box(
            Modifier
                .offset(w * 0.10f, w * 0.11f)
                .size(w * 0.80f, w * 0.56f)
        ) { glyph() }

        Text(
            text = label,
            color = lerp(DspColors.Label, Color.White, glow),
            fontSize = labelSize,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = w * 0.09f)
                // With an a11yLabel the tile announces that instead, not both.
                .then(if (a11yLabel != null) Modifier.clearAndSetSemantics { } else Modifier)
        )
    }
}

private const val LabelMinSp = 9f

private fun DrawScope.drawShell(accent: Color, glow: Float) {
    val w = size.width

    // Selected glow: layered strokes outside the bezel
    if (glow > 0.01f) {
        for (i in 4 downTo 1) {
            val g = w * 0.012f * i
            drawRoundRect(
                color = accent.copy(alpha = 0.07f * glow * (5 - i)),
                topLeft = Offset(-g, -g),
                size = Size(w + g * 2, w + g * 2),
                cornerRadius = CornerRadius(w * 0.15f + g),
                style = Stroke(w * 0.025f)
            )
        }
    }

    // Metal bezel (light top-left, dark bottom-right)
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(DspColors.BezelHi, Color(0xFF45474A), DspColors.BezelLo),
            start = Offset.Zero,
            end = Offset(w, w)
        ),
        topLeft = Offset(w * 0.017f, w * 0.017f),
        size = Size(w * 0.966f, w * 0.966f),
        cornerRadius = CornerRadius(w * 0.15f),
        style = Stroke(w * 0.026f)
    )

    // Dark face
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(DspColors.TileTop, DspColors.TileBottom)),
        topLeft = Offset(w * 0.04f, w * 0.04f),
        size = Size(w * 0.92f, w * 0.92f),
        cornerRadius = CornerRadius(w * 0.13f)
    )

    // Accent ring + soft inner glow
    val ringTL = Offset(w * 0.052f, w * 0.052f)
    val ringSize = Size(w * 0.896f, w * 0.896f)
    val ringR = CornerRadius(w * 0.122f)
    drawRoundRect(accent.copy(alpha = 0.18f + 0.15f * glow), ringTL, ringSize, ringR, style = Stroke(w * 0.03f))
    drawRoundRect(accent, ringTL, ringSize, ringR, style = Stroke(w * 0.012f))
}

// ─────────────────────────────────────────────────────────────────────────────
// Drawing helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun DrawScope.neon(path: Path, color: Color, sw: Float) {
    val cap = StrokeCap.Round
    val join = StrokeJoin.Round
    drawPath(path, color.copy(alpha = 0.10f), style = Stroke(sw * 5f, cap = cap, join = join))
    drawPath(path, color.copy(alpha = 0.22f), style = Stroke(sw * 2.4f, cap = cap, join = join))
    drawPath(path, color, style = Stroke(sw, cap = cap, join = join))
}

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun spike(cx: Float, base: Float, top: Float, halfW: Float) = Path().apply {
    moveTo(cx - halfW * 2.5f, base)
    cubicTo(cx - halfW, base, cx - halfW * 0.6f, top, cx, top)
    cubicTo(cx + halfW * 0.6f, top, cx + halfW, base, cx + halfW * 2.5f, base)
}

// ─────────────────────────────────────────────────────────────────────────────
// PEQ glyph: real analog peaking-filter magnitude per band, log-frequency axis
// ─────────────────────────────────────────────────────────────────────────────
data class PeqBand(val freqHz: Float, val gainDb: Float, val q: Float, val color: Color)

private val GRID_HZ = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)

/** |H| in dB of an analog peaking EQ at f (Hz). At f == f0 this returns exactly gainDb. */
private fun peakingDb(f: Float, f0: Float, gainDb: Float, q: Float): Float {
    val a = 10f.pow(gainDb / 40f)
    val x = f / f0
    val d = 1f - x * x
    val num = d * d + (x * a / q).let { it * it }
    val den = d * d + (x / (a * q)).let { it * it }
    return 10f * log10(num / den)
}

@Composable
fun PeqGlyph(bands: () -> List<PeqBand>, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val midY = h * 0.5f
        val maxDb = 11f
        val sw = w * 0.014f
        fun fx(f: Float) = (log10(f / 20f) / 3f).coerceIn(0f, 1f) * w
        fun dy(db: Float) = midY - (db / maxDb).coerceIn(-1f, 1f) * h * 0.42f

        for (f in GRID_HZ) {
            drawLine(Color.White.copy(0.07f), Offset(fx(f), h * 0.05f), Offset(fx(f), h * 0.95f), w * 0.003f)
        }
        drawLine(Color.White.copy(0.18f), Offset(0f, midY), Offset(w, midY), w * 0.004f)

        val list = bands()
        val steps = 96
        for (b in list) {
            val curve = Path()
            val fill = Path()
            for (i in 0..steps) {
                val f = 20f * 1000f.pow(i / steps.toFloat())
                val x = w * i / steps
                val y = dy(peakingDb(f, b.freqHz, b.gainDb, b.q))
                if (i == 0) {
                    curve.moveTo(x, y); fill.moveTo(x, midY); fill.lineTo(x, y)
                } else {
                    curve.lineTo(x, y); fill.lineTo(x, y)
                }
            }
            fill.lineTo(w, midY); fill.close()
            drawPath(fill, b.color.copy(alpha = 0.16f))
            neon(curve, b.color, sw)
        }
        for (b in list) {
            val c = Offset(fx(b.freqHz), dy(b.gainDb))
            drawCircle(Color.White, w * 0.034f, c)
            drawCircle(b.color, w * 0.024f, c)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Delay/Gain glyph: second impulse moves with delay, height follows gain
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DelayGainGlyph(
    delayMs: () -> Float,
    gainDb: () -> Float,
    maxDelayMs: Float = 10f,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val base = h * 0.90f
        val sw = w * 0.016f
        val x1 = w * 0.16f
        val t = (delayMs() / maxDelayMs).coerceIn(0f, 1f)
        val x2 = w * 0.30f + (w * 0.88f - w * 0.30f) * t
        val h1 = h * 0.80f
        val h2 = (h1 * 0.6f * 10f.pow(gainDb() / 20f)).coerceIn(h * 0.04f, h1)
        val half = w * 0.016f
        val split = (x1 + x2) / 2f

        // baseline: grey until midway, green after
        drawLine(Color.White.copy(0.35f), Offset(w * 0.02f, base), Offset(split, base), sw * 0.8f, StrokeCap.Round)
        drawLine(DspColors.Delay.copy(0.6f), Offset(split, base), Offset(w * 0.98f, base), sw * 0.8f, StrokeCap.Round)

        neon(spike(x1, base, base - h1, half), Color.White, sw)
        neon(spike(x2, base, base - h2, half), DspColors.Delay, sw)

        // dashed arrow between the spikes
        val ax0 = x1 + w * 0.07f
        val ax1 = x2 - w * 0.06f
        if (ax1 - ax0 > w * 0.06f) {
            val ay = h * 0.20f
            drawLine(
                Color.White.copy(0.9f), Offset(ax0, ay), Offset(ax1, ay), sw * 0.7f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.03f, w * 0.022f))
            )
            val ah = w * 0.03f
            drawLine(Color.White, Offset(ax1, ay), Offset(ax1 - ah, ay - ah * 0.6f), sw * 0.7f, StrokeCap.Round)
            drawLine(Color.White, Offset(ax1, ay), Offset(ax1 - ah, ay + ah * 0.6f), sw * 0.7f, StrokeCap.Round)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Crossover glyph: input splits into 2 or 3 bands
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun XoverGlyph(ways: () -> Int, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.018f
        val x0 = w * 0.30f
        val x1 = w * 0.62f
        val midY = h * 0.5f

        val (ys, cols) = if (ways() >= 3) {
            listOf(h * 0.16f, h * 0.5f, h * 0.84f) to
                listOf(DspColors.BandBlue, DspColors.Xover, DspColors.XoverLow)
        } else {
            listOf(h * 0.22f, h * 0.78f) to listOf(DspColors.BandBlue, DspColors.XoverLow)
        }

        val input = Path().apply { moveTo(w * 0.02f, midY); lineTo(x0, midY) }
        neon(input, Color.White.copy(0.9f), sw)

        ys.forEachIndexed { i, ty ->
            val p = Path().apply {
                moveTo(x0, midY)
                val dx = (x1 - x0) * 0.5f
                cubicTo(x0 + dx, midY, x1 - dx, ty, x1, ty)
                lineTo(w * 0.98f, ty)
            }
            neon(p, cols[i], sw)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compressor glyph: white = input burst, red = output; red half shrinks with GR
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CompressorGlyph(
    thresholdNorm: () -> Float,
    grDb: () -> Float,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h * 0.5f
        val amp = h * 0.44f
        val sw = w * 0.012f
        val thr = thresholdNorm().coerceIn(0.1f, 0.95f)
        val g = 10f.pow(-grDb().coerceIn(0f, 24f) / 20f)

        fun sig(t: Float): Float {
            val env = (t / 0.05f).coerceAtMost(1f) * exp(-t * 1.6f)
            val s = 0.7f * sin(2f * PI.toFloat() * 9f * t) + 0.3f * sin(2f * PI.toFloat() * 14f * t + 1f)
            return (s * env * 1.05f).coerceIn(-1f, 1f)
        }

        drawLine(Color.White.copy(0.15f), Offset(0f, mid), Offset(w, mid), w * 0.003f)

        // threshold lines
        val dash = PathEffect.dashPathEffect(floatArrayOf(w * 0.03f, w * 0.02f))
        for (sign in listOf(-1f, 1f)) {
            val y = mid + sign * thr * amp
            drawLine(DspColors.Comp.copy(0.75f), Offset(w * 0.5f, y), Offset(w, y), sw * 0.6f, pathEffect = dash)
        }

        val n = 240
        val white = Path()
        val red = Path()
        for (i in 0..n) {
            val t = i / n.toFloat()
            val gain = 1f + (g - 1f) * smoothstep(0.42f, 0.58f, t)
            val y = mid - sig(t) * gain * amp
            val x = w * t
            if (t <= 0.5f) {
                if (i == 0) white.moveTo(x, y) else white.lineTo(x, y)
            }
            if (t >= 0.5f) {
                if (red.isEmpty) red.moveTo(x, y) else red.lineTo(x, y)
            }
        }
        neon(white, Color.White, sw)
        neon(red, DspColors.Comp, sw)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Allpass glyph: reference sine vs phase-shifted sine
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AllpassGlyph(phaseDeg: () -> Float, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h * 0.5f
        val amp = h * 0.36f
        val sw = w * 0.014f
        val cycles = 1.25f
        val ph = phaseDeg() * PI.toFloat() / 180f
        val twoPi = 2f * PI.toFloat()

        drawLine(Color.White.copy(0.15f), Offset(0f, mid), Offset(w, mid), w * 0.003f)

        val ref = Path()
        val shifted = Path()
        val n = 160
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = w * (0.02f + 0.96f * t)
            val y1 = mid - amp * sin(twoPi * cycles * t)
            val y2 = mid - amp * sin(twoPi * cycles * t - ph)
            if (i == 0) { ref.moveTo(x, y1); shifted.moveTo(x, y2) } else { ref.lineTo(x, y1); shifted.lineTo(x, y2) }
        }
        neon(ref, Color.White.copy(0.92f), sw)
        neon(shifted, DspColors.Allpass, sw)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Neutral tiles
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GearGlyph(modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) * 0.46f
        val body = Brush.linearGradient(
            listOf(Color(0xFFF2F2F2), Color(0xFF8E9094)),
            start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)
        )
        for (i in 0 until 8) rotate(i * 45f, pivot = c) {
            drawRoundRect(
                body,
                topLeft = Offset(c.x - r * 0.17f, c.y - r),
                size = Size(r * 0.34f, r * 0.36f),
                cornerRadius = CornerRadius(r * 0.06f)
            )
        }
        drawCircle(body, r * 0.78f, c)
        drawCircle(Color(0xFF17181A), r * 0.34f, c)
        drawCircle(Color(0xFFDADADC), r * 0.17f, c)
    }
}

@Composable
fun MoreGlyph(modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val r = size.width * 0.06f
        for (fx in listOf(0.3f, 0.5f, 0.7f)) drawCircle(Color(0xFFEDEDED), r, Offset(size.width * fx, size.height * 0.55f))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Convenience wrappers
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PeqTile(bands: () -> List<PeqBand>, selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) =
    DspTile("PEQ", DspColors.Peq, selected, onClick, modifier) { PeqGlyph(bands) }

@Composable
fun DelayGainTile(
    delayMs: () -> Float, gainDb: () -> Float,
    selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier
) = DspTile("Delay/Gain", DspColors.Delay, selected, onClick, modifier) { DelayGainGlyph(delayMs, gainDb) }

@Composable
fun XoverTile(ways: () -> Int, selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) =
    DspTile("Xovers", DspColors.Xover, selected, onClick, modifier) { XoverGlyph(ways) }

@Composable
fun CompressorTile(
    thresholdNorm: () -> Float, grDb: () -> Float,
    selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier
) = DspTile("Compressor", DspColors.Comp, selected, onClick, modifier) { CompressorGlyph(thresholdNorm, grDb) }

@Composable
fun AllpassTile(phaseDeg: () -> Float, selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) =
    DspTile("Allpass", DspColors.Allpass, selected, onClick, modifier) { AllpassGlyph(phaseDeg) }

@Composable
fun SettingsTile(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) =
    DspTile("Settings", DspColors.Neutral, selected, onClick, modifier) { GearGlyph() }

@Composable
fun MoreTile(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) =
    DspTile("More", DspColors.Neutral, selected, onClick, modifier) { MoreGlyph() }

// ─────────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────────
@Preview(widthDp = 900, heightDp = 160, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun TileRowPreview() {
    var sel by remember { mutableStateOf(0) }
    val bands = remember {
        listOf(
            PeqBand(60f, 5f, 1.2f, DspColors.BandMagenta),
            PeqBand(250f, 9f, 1.4f, DspColors.BandBlue),
            PeqBand(1000f, -9f, 1.6f, DspColors.BandAmber),
            PeqBand(6000f, 5f, 1.5f, DspColors.BandGreen)
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val m = Modifier.weight(1f)
        PeqTile({ bands }, sel == 0, { sel = 0 }, m)
        DelayGainTile({ 4f }, { -6f }, sel == 1, { sel = 1 }, m)
        XoverTile({ 3 }, sel == 2, { sel = 2 }, m)
        CompressorTile({ 0.55f }, { 8f }, sel == 3, { sel = 3 }, m)
        AllpassTile({ 90f }, sel == 4, { sel = 4 }, m)
        SettingsTile(sel == 5, { sel = 5 }, m)
        MoreTile(sel == 6, { sel = 6 }, m)
    }
}
