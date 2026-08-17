package app.siphondsp.audio

import java.io.File
import kotlin.math.log10

/**
 * Post-hoc frequency-response comparison for a finished measurement capture -- reads the two
 * exported WAV files back (the same bytes the user could inspect externally), Welch-averages
 * several windows' power spectra per side, and returns per-bin dB curves for
 * [app.siphondsp.view.MeasurementResponseView] to plot.
 */
object MeasurementSpectrumAnalyzer {
    const val WINDOW_SIZE = 2048
    const val WINDOW_COUNT = 16

    data class Result(val rawInDb: FloatArray, val outDb: FloatArray, val sampleRate: Int, val fftSize: Int)

    /** Returns null if either capture is too short to hold at least one analysis window. */
    fun analyze(rawInFile: File, outFile: File): Result? {
        val info = WavFloatReader.readInfo(rawInFile) ?: return null
        val rawInWindows = WavFloatReader.readEvenlySpacedWindows(rawInFile, WINDOW_SIZE, WINDOW_COUNT) ?: return null
        val outWindows = WavFloatReader.readEvenlySpacedWindows(outFile, WINDOW_SIZE, WINDOW_COUNT) ?: return null

        val fft = FFT(WINDOW_SIZE).apply { initHannWindow(WINDOW_SIZE) }
        return Result(
            rawInDb = averagePowerSpectrumDb(rawInWindows, fft),
            outDb = averagePowerSpectrumDb(outWindows, fft),
            sampleRate = info.sampleRate,
            fftSize = WINDOW_SIZE,
        )
    }

    // Welch's method: average linear power across windows first, then convert to dB once --
    // averaging dB values directly would not be the same thing and would bias the result.
    private fun averagePowerSpectrumDb(windows: List<FloatArray>, fft: FFT): FloatArray {
        val numBins = WINDOW_SIZE / 2 + 1
        val accum = DoubleArray(numBins)
        for (window in windows) {
            val power = fft.computePowerSpectrum(fft.applyWindow(window))
            for (bin in 0 until numBins) accum[bin] += power[bin]
        }
        val count = windows.size.coerceAtLeast(1)
        return FloatArray(numBins) { bin ->
            (10.0 * log10((accum[bin] / count).coerceAtLeast(1e-18))).toFloat()
        }
    }
}
