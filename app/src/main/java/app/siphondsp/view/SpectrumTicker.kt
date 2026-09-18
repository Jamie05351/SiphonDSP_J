package app.siphondsp.view

import android.os.Handler
import android.os.Looper
import app.siphondsp.audio.SpectrumEngine

/**
 * The 30 fps live-spectrum poll loop for [ParametricEqSurface]: acquires [SpectrumEngine] while
 * running, invalidates the host on every frame via [onTick], and -- when [gainMetersEnabled] --
 * feeds the L/R [PeakHoldMeter]s the view draws alongside the graph.
 *
 * Lifted verbatim out of ParametricEqSurface (spectrumHandler / spectrumActive / spectrumTick /
 * startSpectrum / stopSpectrum / updateGainMeters + the two meters) so the view keeps only
 * `spectrumTicker.start()/stop()` calls from its attach/detach hooks and the showSpectrum setter.
 */
class SpectrumTicker(
    private val onTick: () -> Unit,
    private val gainMetersEnabled: () -> Boolean,
) {
    val leftMeter = PeakHoldMeter(floorDb = SpectrumEngine.LEVEL_FLOOR_DB)
    val rightMeter = PeakHoldMeter(floorDb = SpectrumEngine.LEVEL_FLOOR_DB)

    var isActive = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val levelScratch = FloatArray(4)
    private val tick = object : Runnable {
        override fun run() {
            if (gainMetersEnabled()) updateGainMeters()
            onTick()
            handler.postDelayed(this, 33L)
        }
    }

    fun start() {
        if (isActive) return
        isActive = true
        SpectrumEngine.acquire()
        handler.post(tick)
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        handler.removeCallbacks(tick)
        SpectrumEngine.release()
        leftMeter.reset()
        rightMeter.reset()
    }

    private fun updateGainMeters() {
        SpectrumEngine.channelLevelsInto(levelScratch)
        val now = System.currentTimeMillis()
        leftMeter.update(levelScratch[0], levelScratch[1], now)
        rightMeter.update(levelScratch[2], levelScratch[3], now)
    }
}
