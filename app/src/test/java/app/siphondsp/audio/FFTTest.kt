package app.siphondsp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FFTTest {

    private fun tone(size: Int, cyclesPerWindow: Int): FloatArray =
        FloatArray(size) { i -> sin(2.0 * PI * cyclesPerWindow * i / size).toFloat() }

    @Test
    fun applyWindowIntoMatchesAllocatingVariant() {
        val fft = FFT(1024).apply { initHannWindow(1024) }
        val input = tone(1024, 17)

        val allocated = fft.applyWindow(input)
        val into = DoubleArray(1024)
        fft.applyWindowInto(input, into)

        for (i in input.indices) assertEquals(allocated[i], into[i], 0.0)
    }

    @Test
    fun computePowerSpectrumIntoMatchesAllocatingVariant() {
        val fft = FFT(2048).apply { initHannWindow(2048) }
        val windowed = fft.applyWindow(tone(2048, 40))

        val allocated = fft.computePowerSpectrum(windowed)
        val into = DoubleArray(2048 / 2 + 1)
        fft.computePowerSpectrumInto(windowed, into)

        assertEquals(allocated.size, into.size)
        for (i in allocated.indices) assertEquals(allocated[i], into[i], 0.0)
    }

    @Test
    fun reusedScratchBuffersStayConsistentAcrossFrames() {
        // The live spectrum path calls the *Into variants repeatedly against the same buffers;
        // make sure a second call fully overwrites the first frame's data.
        val fft = FFT(1024).apply { initHannWindow(1024) }
        val window = DoubleArray(1024)
        val power = DoubleArray(1024 / 2 + 1)

        fft.applyWindowInto(tone(1024, 8), window)
        fft.computePowerSpectrumInto(window, power)
        val firstPeakBin = power.indices.maxByOrNull { power[it] }

        fft.applyWindowInto(tone(1024, 64), window)
        fft.computePowerSpectrumInto(window, power)
        val secondPeakBin = power.indices.maxByOrNull { power[it] }

        assertEquals(8, firstPeakBin)
        assertEquals(64, secondPeakBin)
    }

    @Test
    fun largeWindowResolvesLowFrequencyBins() {
        // The reason the window grew to 16384: a ~30 Hz tone must land in its own bin, not smear
        // across the first two or three the way it does at 2048.
        val size = 16384
        val sampleRate = 48000
        val fft = FFT(size).apply { initHannWindow(size) }
        val binHz = sampleRate.toDouble() / size
        val targetBin = 12 // ~35 Hz
        val windowed = fft.applyWindow(tone(size, targetBin))
        val power = fft.computePowerSpectrum(windowed)

        val peakBin = power.indices.maxByOrNull { power[it] }
        assertEquals(targetBin, peakBin)
        assertTrue("bin resolution should be a few Hz, was $binHz", binHz < 3.5)
    }
}
