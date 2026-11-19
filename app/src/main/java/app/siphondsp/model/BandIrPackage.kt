package app.siphondsp.model

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Stages and validates a complete set before the store is touched. No archive paths are trusted. */
internal object BandIrPackage {
    class Prepared(val directory: File, val manifest: BandIrManifest) : AutoCloseable {
        override fun close() { directory.deleteRecursively() }
    }

    fun prepare(input: InputStream, scratch: File, validate: (File) -> String?): Prepared {
        val dir = File(scratch, "band-ir-${java.util.UUID.randomUUID()}")
        check(dir.mkdirs()) { "Could not create package staging folder" }
        try {
            val seen = HashSet<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name.matches(Regex("[A-Za-z0-9_.-]+"))) { "Unsafe package entry" }
                    require(seen.add(entry.name) && seen.size <= 16) { "Duplicate or excessive package entries" }
                    File(dir, entry.name).outputStream().use { output ->
                        val bytes = ByteArray(65536)
                        var fileBytes = 0L
                        while (true) {
                            val count = zip.read(bytes)
                            if (count < 0) break
                            total += count
                            fileBytes += count
                            require(fileBytes <= 64L * 1024 * 1024 && total <= 128L * 1024 * 1024) { "Package is too large" }
                            if (entry.name == "manifest.json") require(fileBytes <= 131072) { "Manifest is too large" }
                            output.write(bytes, 0, count)
                        }
                    }
                }
            }
            val manifest = BandIrManifest.parse(File(dir, "manifest.json").readText())
            for (name in manifest.files) {
                val wav = File(dir, name)
                require(wav.isFile) { "Missing $name" }
                checkWavMetadata(wav, manifest)
                validate(wav)?.let { throw IllegalArgumentException("$name: $it") }
            }
            return Prepared(dir, manifest)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Designer packages use float32 stereo WAV; reject inconsistent sample rates/tap lengths. */
    private fun checkWavMetadata(file: File, expected: BandIrManifest) {
        RandomAccessFile(file, "r").use { wav ->
            val riff = ByteArray(12)
            wav.readFully(riff)
            require(String(riff, 0, 4, Charsets.US_ASCII) == "RIFF" && String(riff, 8, 4, Charsets.US_ASCII) == "WAVE") { "Expected RIFF WAV" }
            var formatOk = false
            var frames = -1L
            val header = ByteArray(8)
            while (wav.filePointer + 8 <= wav.length()) {
                wav.readFully(header)
                val name = String(header, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                val start = wav.filePointer
                require(size <= wav.length() - start) { "Truncated WAV" }
                when (name) {
                    "fmt " -> {
                        require(size in 16..4096) { "Invalid WAV format chunk" }
                        val fmt = ByteArray(16)
                        wav.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        val type = b.short.toInt()
                        val channels = b.short.toInt()
                        val rate = b.int
                        b.int
                        val align = b.short.toInt()
                        val bits = b.short.toInt()
                        require(type == 3 && channels == 2 && bits == 32 && align == 8 && rate == expected.sampleRate) { "WAV must be stereo float32 at the package sample rate" }
                        formatOk = true
                    }
                    "data" -> { require(size % 8 == 0L) { "Invalid WAV sample data" }; frames = size / 8 }
                }
                wav.seek(start + size + (size and 1))
            }
            require(formatOk && frames == expected.taps.toLong()) { "WAV tap count differs from manifest" }
        }
    }
}
