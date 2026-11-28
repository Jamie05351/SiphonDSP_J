package app.siphondsp.compose.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.compose.home.HomeEngineState
import app.siphondsp.compose.home.HomePalette
import app.siphondsp.compose.home.LocalHomeScale
import app.siphondsp.interop.NativeDsp
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.LevelReadout
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

/**
 * The middle of the DSP pill, between power and the headroom: what the DSP is doing (on or bypassed,
 * and how many ways), the output level with a peak tick, and the same chips as the DSP pages --
 * clipped, limiting, the compressor working -- so a glance from the driver's seat says whether
 * anything is hitting a limit without opening a menu.
 */
@Composable
internal fun PillStatus(powered: Boolean, engine: HomeEngineState, readout: () -> LevelReadout, modifier: Modifier = Modifier) {
    val s = LocalHomeScale.current
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.dp(18f))) {
        Column(Modifier.width(s.dp(PillStateWidth))) {
            Text(
                if (powered) "DSP ON" else "BYPASSED",
                color = if (powered) HomePalette.HeadroomGood else HomePalette.Caption,
                fontSize = s.sp(11f), fontWeight = FontWeight.Bold, maxLines = 1,
            )
            Text(
                when {
                    !engine.known -> "—"
                    engine.neutral -> "Measuring"
                    engine.crossoverWays == 0 -> "Full range"
                    else -> "${engine.crossoverWays}-way"
                },
                color = Color.White, fontSize = s.sp(18f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // In and out, so the gap between them reads as what the DSP is doing to the level.
        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(s.dp(8f))) {
            LevelMeter("IN", readout, { SpectrumEngine.inputPeakDb().takeIf { it > SpectrumEngine.LEVEL_FLOOR_DB } }, 0f,
                Modifier.fillMaxWidth().height(s.dp(16f)))
            LevelMeter("OUT", readout, { readout().let { if (it.silent) null else max(it.leftPeakDb, it.rightPeakDb) } }, engine.ceilingDb,
                Modifier.fillMaxWidth().height(s.dp(16f)))
        }
        PillChips(powered, Modifier.weight(1.1f).clipToBounds())
        PillFacts(engine, Modifier.width(s.dp(PillFactsWidth)))
    }
}

/** A few of the tune's live settings: the volume, Deep bass at it, and the limiter. */
@Composable
private fun PillFacts(engine: HomeEngineState, modifier: Modifier) {
    val s = LocalHomeScale.current
    val facts = listOf(
        "volume" to if (engine.masterMuted) "muted" else "${signedDb(engine.masterVolumeDb)} dB",
        "deep bass" to if (engine.deepBassDb >= 0.05f) "+%.1f dB".format(Locale.ROOT, engine.deepBassDb) else "off",
        "limiter" to (engine.limiterDb?.let { "${signedDb(it)} dB" } ?: "off"),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(s.dp(3f))) {
        for ((label, value) in facts) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = HomePalette.Caption, fontSize = s.sp(12f), maxLines = 1, modifier = Modifier.weight(1f))
                Text(value, color = Color.White, fontSize = s.sp(13f), fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

private fun signedDb(v: Float): String = when {
    v > 0.049f -> "+%.1f".format(Locale.ROOT, v)
    v < -0.049f -> "−%.1f".format(Locale.ROOT, -v)
    else -> "0.0"
}

/**
 * [label], a level bar for [peakDb] (null: nothing playing) with a held tick, and its dB. The bar
 * redraws with [readout]'s updates; the number five times a second, so neither recomposes the bar.
 */
@Composable
private fun LevelMeter(label: String, readout: () -> LevelReadout, peakDb: () -> Float?, ceilingDb: Float, modifier: Modifier) {
    val s = LocalHomeScale.current
    val hold = remember { floatArrayOf(LevelReadout.FLOOR_DB, 0f) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.dp(8f))) {
        Text(label, color = HomePalette.Caption, fontSize = s.sp(11f), fontWeight = FontWeight.Bold, modifier = Modifier.width(s.dp(30f)))
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            readout() // redraw with each new level
            val peak = peakDb()
            val now = System.nanoTime() / 1_000_000f
            fun x(db: Float) = ((db - MeterFloorDb) / -MeterFloorDb).coerceIn(0f, 1f) * size.width
            val h = s.dp(8f).toPx()
            val y = (size.height - h) / 2
            drawRoundRect(HomePalette.IdleFill, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            if (peak != null) {
                if (peak >= hold[0] || now - hold[1] > PeakHoldMs) { hold[0] = peak; hold[1] = now }
                val over = peak > ceilingDb
                drawRoundRect(
                    Brush.horizontalGradient(listOf(HomePalette.HeadroomGood, HomePalette.HeadroomGood, if (over) HomePalette.HeadroomCritical else HomePalette.HeadroomLow)),
                    Offset(0f, y), Size(x(peak), h), CornerRadius(h / 2),
                )
                if (hold[0] > MeterFloorDb) {
                    drawRect(Color.White.copy(alpha = .85f), Offset(x(hold[0]) - s.dp(1f).toPx(), y - s.dp(4f).toPx()), Size(s.dp(2f).toPx(), h + s.dp(8f).toPx()))
                }
            }
        }
        var shown by remember { mutableStateOf("—") }
        LaunchedEffect(peakDb) {
            while (true) {
                shown = peakDb()?.let { "%.1f".format(Locale.ROOT, it).replace('-', '−') } ?: "—"
                delay(200)
            }
        }
        Text(shown, color = Color.White, fontSize = s.sp(12f), fontFamily = FontFamily.Monospace, maxLines = 1,
            modifier = Modifier.width(s.dp(44f)))
    }
}

/**
 * The status chips, polled about 30 times a second off the native meters (so a short hit isn't
 * missed) but redrawn five times a second: clean / clipped (the output reaching full scale), the
 * limiters (master or a bus) while they're working or for [HoldMs] after, and the compressor's
 * deepest band while it's working.
 */
@Composable
private fun PillChips(powered: Boolean, modifier: Modifier) {
    val s = LocalHomeScale.current
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    var lastLimit by remember { mutableLongStateOf(0L) }
    var mbcDb by remember { mutableFloatStateOf(0f) }
    // Each band's gain reduction, for the B1..B4 bars, refreshed with the chips.
    val bands = remember { androidx.compose.runtime.mutableStateListOf(0f, 0f, 0f, 0f) }
    LaunchedEffect(powered) {
        val bus = FloatArray(3)
        val mbc = FloatArray(12)
        var tick = 0
        // Whether the last MBC read worked: a failed read leaves the array holding the read before.
        var mbcOk = false
        while (true) {
            val t = System.nanoTime()
            if (powered) {
                val master = RootlessAudioProcessorService.nativeBmwMasterLimiterMeter()?.getOrNull(0) ?: 0f
                val busOn = RootlessAudioProcessorService.readNativeBmwMeter(NativeDsp.METER_BUS_LIMITER, bus)
                if (master > LimitingDb || (busOn && bus.any { it > LimitingDb })) lastLimit = t
                mbcOk = RootlessAudioProcessorService.readNativeBmwMeter(NativeDsp.METER_MBC, mbc)
                mbcDb = if (mbcOk) maxOf(mbc[2], mbc[5], mbc[8], mbc[11]) else 0f
            } else {
                mbcOk = false
                mbcDb = 0f
            }
            if (++tick % 6 == 0) {
                now = t
                for (b in 0..3) bands[b] = if (powered && mbcOk) mbc[b * 3 + 2].coerceAtLeast(0f) else 0f
            }
            delay(33)
        }
    }
    val clipAgeMs = SpectrumEngine.lastClipNanos.let { if (it == 0L) Long.MAX_VALUE else (now - it) / 1_000_000 }
    val limitAgeMs = if (lastLimit == 0L) Long.MAX_VALUE else (now - lastLimit) / 1_000_000
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.dp(14f))) {
        MbcBars(bands, Modifier.width(s.dp(MbcBarsWidth)).height(s.dp(MbcBarsHeight)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.dp(6f))) {
            PillChipRows(clipAgeMs, limitAgeMs, mbcDb)
        }
    }
}

/** The multiband compressor's four bands, each a bar of its gain reduction (0..12 dB) in its colour. */
@Composable
private fun MbcBars(bands: List<Float>, modifier: Modifier) {
    val s = LocalHomeScale.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(s.dp(4f))) {
        Text("MBC", color = HomePalette.Caption, fontSize = s.sp(10f), fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val gap = s.dp(5f).toPx()
            val w = (size.width - gap * 3) / 4
            for (b in 0..3) {
                val x = b * (w + gap)
                val colour = Color(MbcBandColours[b])
                drawRoundRect(HomePalette.IdleFill, Offset(x, 0f), Size(w, size.height), CornerRadius(s.dp(3f).toPx()))
                val frac = (bands.getOrElse(b) { 0f } / MbcBarFullDb).coerceIn(0f, 1f)
                // Gain reduction hangs from the top, as on the Dynamics page.
                if (frac > 0f) drawRoundRect(colour, Offset(x, 0f), Size(w, size.height * frac), CornerRadius(s.dp(3f).toPx()))
                drawRect(colour.copy(alpha = .7f), Offset(x, 0f), Size(w, s.dp(2f).toPx()))
            }
        }
    }
}

@Composable
private fun PillChipRows(clipAgeMs: Long, limitAgeMs: Long, mbcDb: Float) {
    val s = LocalHomeScale.current
    Row(horizontalArrangement = Arrangement.spacedBy(s.dp(6f))) {
        when {
            clipAgeMs < 400 -> Chip("clipping", HomePalette.HeadroomCritical)
            clipAgeMs < HoldMs -> Chip("clipped ${clipAgeMs / 1000 + 1} s ago", Color(0xFFFFA24C))
            else -> Chip("clean", HomePalette.HeadroomGood)
        }
        when {
            limitAgeMs < 400 -> Chip("limiting", HomePalette.HeadroomCritical)
            limitAgeMs < HoldMs -> Chip("limited ${limitAgeMs / 1000 + 1} s", Color(0xFFFFA24C))
        }
        if (mbcDb > MbcShownDb) Chip("MBC −%.0f".format(Locale.ROOT, mbcDb), Color(0xFFEA1A26))
    }
}

@Composable
private fun Chip(text: String, colour: Color) {
    val s = LocalHomeScale.current
    val shape = RoundedCornerShape(s.dp(7f))
    Row(
        Modifier.height(s.dp(22f)).clip(shape).background(colour.copy(alpha = .16f)).border(s.dp(1f), colour.copy(alpha = .7f), shape)
            .padding(horizontal = s.dp(8f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.dp(5f)),
    ) {
        Box(Modifier.size(s.dp(6f)).clip(CircleShape).background(colour))
        Text(text, color = Color.White, fontSize = s.sp(10f), fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private const val PillStateWidth = 110f
private const val PillFactsWidth = 170f
private const val MeterFloorDb = -48f
private const val PeakHoldMs = 1500f
private const val HoldMs = 10_000L
private const val LimitingDb = 0.1f
private const val MbcShownDb = 0.5f
private const val MbcBarsWidth = 70f
private const val MbcBarsHeight = 62f
private const val MbcBarFullDb = 12f
// The Dynamics page's band colours (MbcBandColors).
private val MbcBandColours = longArrayOf(0xFFFF6B6B, 0xFFFFB347, 0xFF2EC4B6, 0xFF9B7BFF)
