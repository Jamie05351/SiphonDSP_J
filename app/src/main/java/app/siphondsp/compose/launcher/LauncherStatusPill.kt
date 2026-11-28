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
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.dp(10f))) {
        Column(Modifier.width(s.dp(PillStateWidth))) {
            Text(
                if (powered) "DSP ON" else "BYPASSED",
                color = if (powered) HomePalette.HeadroomGood else HomePalette.Caption,
                fontSize = s.sp(10f), fontWeight = FontWeight.Bold, maxLines = 1,
            )
            Text(
                when {
                    !engine.known -> "—"
                    engine.neutral -> "Measuring"
                    engine.crossoverWays == 0 -> "Full range"
                    else -> "${engine.crossoverWays}-way"
                },
                color = Color.White, fontSize = s.sp(15f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.dp(7f))) {
            OutMeter(readout, engine.ceilingDb, Modifier.fillMaxWidth().height(s.dp(14f)))
            PillChips(powered, Modifier.fillMaxWidth().clipToBounds())
        }
    }
}

/** "OUT", the louder channel's peak as a bar with a held tick, and its dB. Redraws only itself. */
@Composable
private fun OutMeter(readout: () -> LevelReadout, ceilingDb: Float, modifier: Modifier) {
    val s = LocalHomeScale.current
    val hold = remember { floatArrayOf(LevelReadout.FLOOR_DB, 0f) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.dp(6f))) {
        Text("OUT", color = HomePalette.Caption, fontSize = s.sp(9f), fontWeight = FontWeight.Bold)
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val levels = readout()
            val peak = max(levels.leftPeakDb, levels.rightPeakDb)
            val now = System.nanoTime() / 1_000_000f
            if (peak >= hold[0] || now - hold[1] > PeakHoldMs) { hold[0] = peak; hold[1] = now }
            fun x(db: Float) = ((db - MeterFloorDb) / -MeterFloorDb).coerceIn(0f, 1f) * size.width
            val h = s.dp(7f).toPx()
            val y = (size.height - h) / 2
            drawRoundRect(HomePalette.IdleFill, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            if (!levels.silent) {
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
        val levels = readout()
        Text(
            if (levels.silent) "—" else "%.1f".format(Locale.ROOT, max(levels.leftPeakDb, levels.rightPeakDb)).replace('-', '−'),
            color = Color.White, fontSize = s.sp(10f), fontFamily = FontFamily.Monospace, maxLines = 1,
            modifier = Modifier.width(s.dp(38f)),
        )
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
    LaunchedEffect(powered) {
        val bus = FloatArray(3)
        val mbc = FloatArray(12)
        var tick = 0
        while (true) {
            val t = System.nanoTime()
            if (powered) {
                val master = RootlessAudioProcessorService.nativeBmwMasterLimiterMeter()?.getOrNull(0) ?: 0f
                val busOn = RootlessAudioProcessorService.readNativeBmwMeter(NativeDsp.METER_BUS_LIMITER, bus)
                if (master > LimitingDb || (busOn && bus.any { it > LimitingDb })) lastLimit = t
                mbcDb = if (RootlessAudioProcessorService.readNativeBmwMeter(NativeDsp.METER_MBC, mbc)) {
                    maxOf(mbc[2], mbc[5], mbc[8], mbc[11])
                } else 0f
            } else {
                mbcDb = 0f
            }
            if (++tick % 6 == 0) now = t
            delay(33)
        }
    }
    val clipAgeMs = SpectrumEngine.lastClipNanos.let { if (it == 0L) Long.MAX_VALUE else (now - it) / 1_000_000 }
    val limitAgeMs = if (lastLimit == 0L) Long.MAX_VALUE else (now - lastLimit) / 1_000_000
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(s.dp(6f))) {
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

private const val PillStateWidth = 92f
private const val MeterFloorDb = -48f
private const val PeakHoldMs = 1500f
private const val HoldMs = 10_000L
private const val LimitingDb = 0.1f
private const val MbcShownDb = 0.5f
