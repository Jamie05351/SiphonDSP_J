package app.siphondsp.interop

import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricBiquadProcessorTest {
    @Test
    fun rejectsZeroQ() {
        val processor = ParametricBiquadProcessor()
        assertFalse(processor.configure(true, "PEQ: 1000 6 0 0;", 0f, 48000f))
    }

    @Test
    fun zeroDbBandIsTransparent() {
        val processor = ParametricBiquadProcessor()
        assertTrue(processor.configure(true, "PEQ: 1000 0 1.41 0;", 0f, 48000f))

        val buffer = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f, 0.0f, 0.0f)
        val original = buffer.copyOf()
        processor.process(buffer, buffer.size)

        for (i in buffer.indices) {
            assertTrue("sample $i changed", abs(buffer[i] - original[i]) < 1.0e-6f)
        }
    }

    @Test
    fun validBoostChangesSignal() {
        val processor = ParametricBiquadProcessor()
        assertTrue(processor.configure(true, "PEQ: 1000 6 1.0 0;", 0f, 48000f))

        val buffer = FloatArray(512)
        buffer[0] = 0.25f
        buffer[1] = 0.25f
        processor.process(buffer, buffer.size)

        assertTrue(buffer.any { abs(it) > 1.0e-7f })
    }
}
