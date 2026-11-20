package app.siphondsp.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BandIrStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun oldStereoPathAndBandSlotsAreIndependent() {
        val stereo = ConvolverIrStore.irFile(context)
        assertEquals(File(context.filesDir, "convolver/ir.wav"), stereo)
        val files = (-1..2).map { ConvolverIrStore.irFile(context, it) }
        assertEquals(4, files.toSet().size)
        files.forEachIndexed { i, file -> file.parentFile!!.mkdirs(); file.writeText("slot $i") }
        ConvolverIrStore.clear(context, 1)
        assertFalse(files[2].exists())
        assertEquals("slot 0", files[0].readText())
        assertEquals("slot 1", files[1].readText())
        assertEquals("slot 3", files[3].readText())
        assertFalse(ConvolverIrStore.allBandsStored(context))
    }

    @Test fun modeAndIndependentTrimsSurviveSubsequentReads() {
        assertTrue(ConvolverIrStore.setMode(context, 1))
        assertEquals(1, ConvolverIrStore.mode(context))
        assertTrue(ConvolverIrStore.saveGain(context, 0, -5f))
        assertTrue(ConvolverIrStore.saveGain(context, 1, -2f))
        assertEquals(-5f, ConvolverIrStore.savedGain(context, 0, 0f), 0f)
        assertEquals(-2f, ConvolverIrStore.savedGain(context, 1, 0f), 0f)
        assertFalse(ConvolverIrStore.saveGain(context, 1, Float.NaN))
        assertEquals(-2f, ConvolverIrStore.savedGain(context, 1, 0f), 0f)
    }

    @Test fun manifestRejectsBakedCrossoversAndUnsafeFilenames() {
        val valid = """{"format":"siphondsp-band-ir","version":1,"correction_only":true,"sample_rate":48000,"taps":1024,"latency_frames":512,"crossover_hz":{"low_mid":150,"mid_high":3500},"files":{"low":"low.wav","mid":"mid.wav","high":"high.wav"}}"""
        val manifest = BandIrManifest.parse(valid)
        assertTrue(manifest.matchesCrossovers(150f, 3500f))
        assertFalse(manifest.matchesCrossovers(180f, 3500f))
        assertThrows(IllegalArgumentException::class.java) { BandIrManifest.parse(valid.replace("true", "false")) }
        assertThrows(IllegalArgumentException::class.java) { BandIrManifest.parse(valid.replace("low.wav", "../low.wav")) }
        assertThrows(IllegalArgumentException::class.java) { BandIrManifest.parse(valid.replace("48000", "96000")) }
    }
}
