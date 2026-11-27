package app.siphondsp.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.FloatBuffer

/** The Output page's clip chip: every sample of every published buffer counts, NaN/Inf included. */
class SpectrumEngineClipTest {
    @Before fun up() = SpectrumEngine.acquireLevels()
    @After fun down() = SpectrumEngine.releaseLevels()

    private fun publish(samples: FloatArray): Long {
        val before = SpectrumEngine.lastClipNanos
        val buffer = FloatBuffer.wrap(samples)
        SpectrumEngine.captureDry(buffer, samples.size)
        SpectrumEngine.publishWet(buffer, 48_000)
        return before
    }

    @Test
    fun aClipPastTheCopiedFrameStillCounts() {
        val samples = FloatArray(80_000) // longer than the analyser's frame
        samples[samples.size - 1] = 1f
        val before = publish(samples)
        assertTrue(SpectrumEngine.lastClipNanos > before)
    }

    @Test
    fun aNonFiniteSampleCountsAsAClip() {
        val before = publish(FloatArray(512).also { it[100] = Float.NaN })
        assertTrue(SpectrumEngine.lastClipNanos > before)
        val beforeInf = publish(FloatArray(512).also { it[7] = Float.POSITIVE_INFINITY })
        assertTrue(SpectrumEngine.lastClipNanos > beforeInf)
    }

    @Test
    fun quietAudioIsClean() {
        val before = publish(FloatArray(512) { .5f })
        assertEquals(before, SpectrumEngine.lastClipNanos)
    }
}
