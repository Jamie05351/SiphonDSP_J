package app.siphondsp.interop

import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricBiquadProcessorTest {
    @Test
    fun rejectsZeroQ() {
        val processor = ParametricBiquadProcessor()
        assertFalse(processor.configure(true, "PEQ: 1000 6 0 0 0;", 0f, 48000f))
    }

    @Test
    fun zeroDbBandIsTransparent() {
        val processor = ParametricBiquadProcessor()
        assertTrue(processor.configure(true, "PEQ: 1000 0 1.41 0 0;", 0f, 48000f))

        val buffer = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f, 0.0f, 0.0f)
        val original = buffer.copyOf()
        processor.process(buffer, buffer.size)

        for (i in buffer.indices) {
            assertTrue("sample $i changed", abs(buffer[i] - original[i]) < 1.0e-6f)
        }
    }

    @Test
    fun invalidReconfigurationBypassesOldFilter() {
        val processor = ParametricBiquadProcessor()
        assertTrue(processor.configure(true, "PEQ: 1000 6 1.0 0 0;", 0f, 48000f))
        assertFalse(processor.configure(true, "PEQ: 1000 6 0 0 0;", 0f, 48000f))

        val buffer = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f)
        val original = buffer.copyOf()
        processor.process(buffer, buffer.size)
        assertTrue(buffer.contentEquals(original))
    }

    @Test
    fun leftOnlyBandLeavesRightChannelUntouched() {
        val processor = ParametricBiquadProcessor()
        assertTrue(processor.configure(true, "PEQ: 1000 12 1.0 0 1;", 0f, 48000f))

        val buffer = FloatArray(1024)
        for (i in buffer.indices step 2) {
            buffer[i] = 0.1f
            buffer[i + 1] = 0.1f
        }
        processor.process(buffer, buffer.size)

        assertTrue(buffer.indices.filter { it % 2 == 0 }.any { abs(buffer[it] - 0.1f) > 1.0e-5f })
        assertTrue(buffer.indices.filter { it % 2 == 1 }.all { abs(buffer[it] - 0.1f) < 1.0e-7f })
    }

    @Test
    fun legacyPresetDefaultsToBothChannels() {
        val bands = ParametricEqBandList().apply { deserialize("PEQ: 1000 3 1.0 0;") }
        assertEquals(1, bands.size)
        assertEquals(ParametricEqChannel.LEFT_RIGHT, bands.single().channel)
    }

    @Test
    fun channelRoutingSurvivesInternalRoundTrip() {
        val original = ParametricEqBandList().apply {
            deserialize("PEQ: 1000 3 1.0 0 1; 2000 -2 2.0 0 2;")
        }
        val restored = ParametricEqBandList().apply { deserialize(original.serialize()) }

        assertEquals(ParametricEqChannel.LEFT, restored[0].channel)
        assertEquals(ParametricEqChannel.RIGHT, restored[1].channel)
    }
}
