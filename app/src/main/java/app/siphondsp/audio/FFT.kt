package app.siphondsp.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Fast Fourier Transform for real-time audio spectrum analysis.
 * Cooley-Tukey radix-2 DIT algorithm.
 *
 * Ported from Equalizer314 (github.com/bearinmindcat/Equalizer314, GPLv3), which itself is
 * based on audio-analyzer-for-android by bewantbe (Apache 2.0 License).
 */
class FFT(private val size: Int) {

    init {
        require(size > 0 && (size and (size - 1)) == 0) {
            "FFT size must be a power of 2, got $size"
        }
    }

    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private val real = DoubleArray(size)
    private val imag = DoubleArray(size)
    private var wnd: DoubleArray? = null

    init {
        for (i in 0 until size / 2) {
            val angle = -2.0 * PI * i / size
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
    }

    /**
     * Initializes the Hann window function with normalization.
     *
     * @param fftLen Length of the window to initialize
     */
    fun initHannWindow(fftLen: Int) {
        wnd = DoubleArray(fftLen)
        wnd?.let { window ->
            for (i in 0 until fftLen) {
                window[i] = 0.5 * (1.0 - cos(2.0 * PI * i / (fftLen - 1.0))) * 2.0
            }
            var normalizeFactor = 0.0
            for (i in 0 until fftLen) normalizeFactor += window[i]
            normalizeFactor = fftLen / normalizeFactor
            for (i in 0 until fftLen) window[i] *= normalizeFactor
        }
    }

    /**
     * Ensures the window is initialized to match the given length.
     * Lazy-initializes if needed or size mismatch.
     *
     * @param size Desired window size
     */
    private fun ensureWindowInitialized(size: Int) {
        if (wnd?.size != size) {
            initHannWindow(size)
        }
    }

    /**
     * Applies Hann window function to the input signal.
     *
     * @param input Float array of input samples
     * @return Windowed samples as DoubleArray
     */
    fun applyWindow(input: FloatArray): DoubleArray {
        ensureWindowInitialized(input.size)
        val window = wnd ?: error("Window failed to initialize")
        val windowed = DoubleArray(input.size)
        for (i in input.indices) {
            windowed[i] = input[i].toDouble() * window[i]
        }
        return windowed
    }

    /**
     * Computes the power spectrum from windowed input.
     *
     * @param windowedInput Windowed FFT input
     * @return Power spectrum (linear scale)
     */
    fun computePowerSpectrum(windowedInput: DoubleArray): DoubleArray {
        require(windowedInput.size == size) {
            "Input size ${windowedInput.size} doesn't match FFT size $size"
        }
        for (i in 0 until size) {
            real[i] = windowedInput[i]
            imag[i] = 0.0
        }
        fft()

        val numBins = size / 2 + 1
        val power = DoubleArray(numBins)
        val scaler = FFT_SCALE_FACTOR_NUMERATOR / (size.toDouble() * size.toDouble())
        power[0] = (real[0] * real[0] + imag[0] * imag[0]) * scaler / 4.0
        for (i in 1 until numBins - 1) {
            power[i] = (real[i] * real[i] + imag[i] * imag[i]) * scaler
        }
        val nyquist = numBins - 1
        power[nyquist] = (real[nyquist] * real[nyquist] + imag[nyquist] * imag[nyquist]) * scaler / 4.0
        return power
    }

    /**
     * Computes the power spectrum in dB scale (10 * log10(power)).
     *
     * @param windowedInput Windowed FFT input
     * @return Power spectrum (dB scale)
     */
    fun computePowerSpectrumDb(windowedInput: DoubleArray): DoubleArray {
        val power = computePowerSpectrum(windowedInput)
        for (i in power.indices) power[i] = 10.0 * log10(power[i].coerceAtLeast(MIN_POWER_DB))
        return power
    }

    /**
     * Performs the FFT computation using Cooley-Tukey algorithm.
     */
    private fun fft() {
        var j = 0
        for (i in 0 until size - 1) {
            if (i < j) {
                var temp = real[i]; real[i] = real[j]; real[j] = temp
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp
            }
            var k = size / 2
            while (k <= j) { j -= k; k /= 2 }
            j += k
        }

        var step = 1
        while (step < size) {
            val halfStep = step
            step *= 2
            val tableFactor = size / step
            for (i in 0 until size step step) {
                var tableIndex = 0
                for (k in 0 until halfStep) {
                    val evenIndex = i + k
                    val oddIndex = evenIndex + halfStep
                    val tReal = cosTable[tableIndex] * real[oddIndex] - sinTable[tableIndex] * imag[oddIndex]
                    val tImag = cosTable[tableIndex] * imag[oddIndex] + sinTable[tableIndex] * real[oddIndex]
                    real[oddIndex] = real[evenIndex] - tReal
                    imag[oddIndex] = imag[evenIndex] - tImag
                    real[evenIndex] += tReal
                    imag[evenIndex] += tImag
                    tableIndex += tableFactor
                }
            }
        }
    }

    /**
     * Converts frequency to the nearest FFT bin index.
     *
     * @param frequency Frequency in Hz
     * @param sampleRate Sample rate in Hz
     * @return Bin index (clamped to valid range)
     */
    fun frequencyToBin(frequency: Float, sampleRate: Int): Int =
        (frequency * size / sampleRate).toInt().coerceIn(0, size / 2)

    companion object {
        /** Numerator for FFT scale factor (4.0 / (size * size)) */
        private const val FFT_SCALE_FACTOR_NUMERATOR = 4.0
        
        /** Minimum power level to prevent log(0) in dB conversion */
        private const val MIN_POWER_DB = 1e-18
    }
}
