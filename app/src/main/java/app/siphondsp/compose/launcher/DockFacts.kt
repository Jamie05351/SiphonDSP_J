package app.siphondsp.compose.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.siphondsp.compose.home.HomeStage
import app.siphondsp.dsp.AutoHeadroom
import app.siphondsp.dsp.AutoHeadroomCalculator
import app.siphondsp.dsp.GainBudget
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ThreeWayCrossover
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * What each DSP tile in the open dock says under its name: two or three facts from the saved
 * settings, so the dock shows the tune at a glance before a page is opened. Output's worst case is
 * the Output page's gain budget ([GainBudget]); [worstCaseDb] is null until it's worked out.
 */
internal fun dockFacts(
    stage: HomeStage,
    values: FloatArray,
    peq: BmwPeqState,
    worstCaseDb: Double?,
    crossoverWays: Int,
): List<Pair<String, String>> {
    if (values.size < NativeBmwDspValues.SIZE) return emptyList()
    fun on(index: Int) = values[index] >= .5f
    return when (stage) {
        HomeStage.PEQ -> listOf(
            "filters" to (peq.fullRangeBands.size + peq.lowBandBands.size + peq.midBandBands.size + peq.highBandBands.size).toString(),
            "preamp" to "${signed(peq.preampDb.toDouble())} dB",
        )
        HomeStage.ALIGNMENT -> listOf(
            "low" to ms(maxOf(values[NativeBmwDspValues.INDEX_LOW_DELAY_L], values[NativeBmwDspValues.INDEX_LOW_DELAY_R])),
            "mid" to ms(maxOf(values[NativeBmwDspValues.INDEX_MID_DELAY_L], values[NativeBmwDspValues.INDEX_MID_DELAY_R])),
        )
        HomeStage.XOVERS -> buildList {
            if (crossoverWays == 0) {
                add("bands" to "bypassed")
            } else {
                // Each split is two filters set independently: the lower band's lowpass and the
                // upper band's highpass. One figure when they meet, both when they don't.
                add("low|mid" to split(values[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ], values[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ]))
                if (crossoverWays >= 3) {
                    add("mid|high" to split(values[ThreeWayCrossover.midLowpassIndex], values[ThreeWayCrossover.highHighpassIndex]))
                }
            }
            add("tilt" to if (on(NativeBmwDspValues.INDEX_TILT_ENABLED)) {
                "${signed(values[NativeBmwDspValues.INDEX_TILT_AMOUNT] * NativeBmwDspValues.TILT_END_GAIN_PER_AMOUNT.toDouble())} dB"
            } else "off")
        }
        HomeStage.DYNAMICS -> {
            val bands = (0 until 4).count { values[NativeBmwDspValues.mbcBandIndex(it, NativeBmwDspValues.MBC_FIELD_ENABLED)] >= .5f }
            listOf(
                "MBC" to if (on(NativeBmwDspValues.INDEX_MBC_ENABLED)) "$bands bands" else "off",
                "limiter" to if (on(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED)) {
                    "${signed(values[NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD].toDouble())} dB"
                } else "off",
            )
        }
        HomeStage.OUTPUT -> listOf(
            "worst" to (worstCaseDb?.let { "${signed(it)} dBFS" } ?: "—"),
            "volume" to "${signed(values[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB].toDouble())} dB",
        )
    }
}

/** The dock's facts per stage, loaded off the main thread while the dock is open and kept current. */
@Composable
internal fun rememberDockFacts(open: Boolean, crossoverWays: Int): Map<HomeStage, List<Pair<String, String>>> {
    val context = LocalContext.current.applicationContext
    var facts by remember { mutableStateOf<Map<HomeStage, List<Pair<String, String>>>>(emptyMap()) }
    var generation by remember { mutableStateOf(0) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        AppEvents.flow.collect { event ->
            if (event is AppEvent.NativeBmwDspUpdated || event == AppEvent.ParametricEqChanged ||
                event == AppEvent.PresetLoaded || event == AppEvent.BackupRestored
            ) generation++
        }
    }
    LaunchedEffect(open, generation, crossoverWays) {
        if (!open) return@LaunchedEffect
        facts = withContext(Dispatchers.Default) {
            runCatching {
                val values = NativeBmwDspValues.load(context)
                val peq = BmwPeqState.load(context)
                val sampleRate = (RootlessAudioProcessorService.nativeBmwPeqSampleRate()?.takeIf { it >= 8000f } ?: 48_000f).toDouble()
                val worst = runCatching {
                    val peak = AutoHeadroomCalculator().peakDb(values, peq, sampleRate)
                    val headroom = if (AutoHeadroom.isAuto(values)) AutoHeadroom.headroomForPeak(peak).toDouble()
                    else values[NativeBmwDspValues.INDEX_HEADROOM].toDouble()
                    val summed = GainBudget.bandSumPeakDb(values, peq, sampleRate)
                    GainBudget.compute(values, peq.preampDb.toDouble(), maxOf(peak, summed), headroom).outputDb
                }.getOrNull()
                HomeStage.entries.associateWith { dockFacts(it, values, peq, worst, crossoverWays) }
            }.getOrDefault(emptyMap())
        }
    }
    return facts
}

private fun signed(v: Double): String = when {
    v > 0.049 -> "+%.1f".format(Locale.ROOT, v)
    v < -0.049 -> "−%.1f".format(Locale.ROOT, -v)
    else -> "0.0"
}

private fun ms(v: Float): String = "%.2f ms".format(Locale.ROOT, v)

private fun split(lowpass: Float, highpass: Float): String =
    if (kotlin.math.abs(lowpass - highpass) < 0.5f) hz(lowpass) else "${short(lowpass)} / ${hz(highpass)}"

private fun short(v: Float): String = if (v >= 1000f) "%.1fk".format(Locale.ROOT, v / 1000f) else "%.0f".format(Locale.ROOT, v)

private fun hz(v: Float): String = if (v >= 1000f) "%.1fk Hz".format(Locale.ROOT, v / 1000f) else "%.0f Hz".format(Locale.ROOT, v)
