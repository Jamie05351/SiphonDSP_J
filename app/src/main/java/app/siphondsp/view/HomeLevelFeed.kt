package app.siphondsp.view

import android.os.Handler
import android.os.Looper
import app.siphondsp.audio.SpectrumEngine

/**
 * The front page's live output levels: polls [SpectrumEngine]'s post-DSP L/R peak and RMS every
 * [FRAME_MS] into the peak-hold meters and passes the held levels to [onFrame] every frame (for the
 * LED rows) and to [onReadout] every [READOUT_EVERY] frames (about 5 a second, slow enough for
 * numbers to be read); both get [LevelReadout.SILENT] when it stops.
 *
 * The analyzer thread only runs while something holds [SpectrumEngine.acquire]; this holds it
 * only while [running] is true, which the front page sets while it is resumed and on the artwork
 * page, so nothing extra runs once the user has moved on to a DSP screen or the settings page.
 */
class HomeLevelFeed(
    private val onFrame: (LevelReadout) -> Unit,
    private val onReadout: (LevelReadout) -> Unit,
) {
    private val leftMeter = PeakHoldMeter(floorDb = LevelReadout.FLOOR_DB)
    private val rightMeter = PeakHoldMeter(floorDb = LevelReadout.FLOOR_DB)
    private val levels = FloatArray(4)
    private var ticksSinceReadout = 0
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            SpectrumEngine.channelLevelsInto(levels)
            val now = System.currentTimeMillis()
            leftMeter.update(levels[0], levels[1], now)
            rightMeter.update(levels[2], levels[3], now)
            val frame = LevelReadout(leftMeter.rmsDb, leftMeter.holdDb, rightMeter.rmsDb, rightMeter.holdDb)
            onFrame(frame)
            if (++ticksSinceReadout >= READOUT_EVERY) {
                ticksSinceReadout = 0
                onReadout(frame)
            }
            handler.postDelayed(this, FRAME_MS)
        }
    }

    var running = false
        set(value) {
            if (field == value) return
            field = value
            if (value) start() else stop()
        }

    private fun start() {
        SpectrumEngine.acquire()
        handler.post(tick)
    }

    private fun stop() {
        handler.removeCallbacks(tick)
        SpectrumEngine.release()
        leftMeter.reset()
        rightMeter.reset()
        ticksSinceReadout = 0
        onFrame(LevelReadout.SILENT)
        onReadout(LevelReadout.SILENT)
    }

    private companion object {
        const val FRAME_MS = 50L
        const val READOUT_EVERY = 4
    }
}
