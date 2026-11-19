package app.siphondsp.model

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BandIrPackageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manifest = """{"format":"siphondsp-band-ir","version":1,"correction_only":true,"sample_rate":48000,"taps":32,"latency_frames":16,"crossover_hz":{"low_mid":150,"mid_high":3500},"files":{"low":"low.wav","mid":"mid.wav","high":"high.wav"}}"""

    @Test fun completePackageIsValidatedBeforeItCanBeInstalled() {
        val checked = mutableListOf<String>()
        val input = archive(mapOf("manifest.json" to manifest.toByteArray(), "low.wav" to wav(), "mid.wav" to wav(), "high.wav" to wav()))
        val prepared = BandIrPackage.prepare(input, temporary.root) { checked += it.name; null }
        assertEquals(listOf("low.wav", "mid.wav", "high.wav"), checked)
        assertEquals(48000, prepared.manifest.sampleRate)
        assertTrue(prepared.directory.isDirectory)
        val directory = prepared.directory
        prepared.close()
        assertFalse(directory.exists())
    }

    @Test fun missingOrRejectedBandLeavesNoPartialSet() {
        val missing = archive(mapOf("manifest.json" to manifest.toByteArray(), "low.wav" to wav(), "mid.wav" to wav()))
        assertThrows(IllegalArgumentException::class.java) { BandIrPackage.prepare(missing, temporary.root) { null } }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        val rejected = archive(mapOf("manifest.json" to manifest.toByteArray(), "low.wav" to wav(), "mid.wav" to wav(), "high.wav" to wav()))
        assertThrows(IllegalArgumentException::class.java) {
            BandIrPackage.prepare(rejected, temporary.root) { if (it.name == "mid.wav") "silent IR" else null }
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun wrongWavRateAndArchiveTraversalAreRejected() {
        val wrongRate = archive(mapOf("manifest.json" to manifest.toByteArray(), "low.wav" to wav(44100), "mid.wav" to wav(), "high.wav" to wav()))
        assertThrows(IllegalArgumentException::class.java) { BandIrPackage.prepare(wrongRate, temporary.root) { null } }
        val traversal = archive(mapOf("../escape.wav" to wav()))
        assertThrows(IllegalArgumentException::class.java) { BandIrPackage.prepare(traversal, temporary.root) { null } }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        assertFalse(java.io.File(temporary.root.parentFile, "escape.wav").exists())
    }

    private fun archive(entries: Map<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun wav(rate: Int = 48000): ByteArray = ByteBuffer.allocate(44 + 32 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(3); putShort(2); putInt(rate); putInt(rate * 8); putShort(8); putShort(32)
        put("data".toByteArray()); putInt(32 * 8)
        repeat(32) { putFloat(if (it == 16) 1f else 0f); putFloat(if (it == 16) 1f else 0f) }
    }.array()
}
