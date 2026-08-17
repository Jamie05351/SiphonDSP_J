package app.siphondsp.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for the 32-bit float PCM WAV files the measurement capture tool exports
 * (written natively by dr_wav.h) -- just enough RIFF/fmt/data chunk walking to pull a few
 * evenly-spaced, mono-downmixed windows for spectrum analysis without loading the whole file.
 */
object WavFloatReader {
    data class WavInfo(val sampleRate: Int, val channels: Int, val totalFrames: Long, val dataOffset: Long)

    fun readInfo(file: File): WavInfo? {
        if (!file.exists() || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12)
            raf.readFully(riff)
            val isRiff = riff[0] == 'R'.code.toByte() && riff[1] == 'I'.code.toByte() &&
                riff[2] == 'F'.code.toByte() && riff[3] == 'F'.code.toByte()
            val isWave = riff[8] == 'W'.code.toByte() && riff[9] == 'A'.code.toByte() &&
                riff[10] == 'V'.code.toByte() && riff[11] == 'E'.code.toByte()
            if (!isRiff || !isWave) return null

            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataSize = 0L

            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt())
                        raf.readFully(fmt)
                        val fmtBuf = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        fmtBuf.short // format tag (IEEE float)
                        channels = fmtBuf.short.toInt()
                        sampleRate = fmtBuf.int
                        fmtBuf.int // byte rate
                        fmtBuf.short // block align
                        bitsPerSample = fmtBuf.short.toInt()
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        raf.seek(raf.filePointer + size + (size and 1L))
                    }
                    else -> raf.seek(raf.filePointer + size + (size and 1L))
                }
            }
            if (dataOffset < 0 || channels <= 0 || bitsPerSample != 32) return null
            val bytesPerFrame = channels * 4
            return WavInfo(sampleRate, channels, dataSize / bytesPerFrame, dataOffset)
        }
    }

    /**
     * Reads [windowCount] evenly-spaced, mono-downmixed windows of [windowSize] frames each,
     * skipping the first/last ~5% of the file to avoid onset/tail transients. Returns null if
     * the file doesn't hold at least one full window.
     */
    fun readEvenlySpacedWindows(file: File, windowSize: Int, windowCount: Int): List<FloatArray>? {
        val info = readInfo(file) ?: return null
        if (info.totalFrames < windowSize) return null

        val marginFrames = (info.totalFrames * 0.05).toLong()
        val usableStart = marginFrames
        val usableEnd = (info.totalFrames - marginFrames - windowSize).coerceAtLeast(usableStart)
        val span = usableEnd - usableStart
        val actualWindowCount = windowCount.coerceAtMost((info.totalFrames / windowSize).toInt()).coerceAtLeast(1)
        val bytesPerFrame = info.channels * 4

        RandomAccessFile(file, "r").use { raf ->
            return (0 until actualWindowCount).map { i ->
                val startFrame = if (actualWindowCount == 1) usableStart else usableStart + span * i / (actualWindowCount - 1)
                raf.seek(info.dataOffset + startFrame * bytesPerFrame)
                val raw = ByteArray(windowSize * bytesPerFrame)
                raf.readFully(raw)
                val samples = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(windowSize) { s ->
                    if (info.channels >= 2) {
                        (samples.get(s * info.channels) + samples.get(s * info.channels + 1)) * 0.5f
                    } else {
                        samples.get(s * info.channels)
                    }
                }
            }
        }
    }
}
