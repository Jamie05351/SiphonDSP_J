package app.siphondsp.model

import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Persistent store for the native BMW DSP config array (crossover, subsonic, tilt,
 * delay/polarity, gain structure, compressors -- everything in [NativeBmwDspValues]
 * except PEQ, which keeps its own [BmwPeqStore] file so frequent PEQ edits don't
 * churn this file and vice versa).
 *
 * Entries are keyed by index rather than position, so a build that adds or removes
 * an index only affects that index on load -- it can't blow away the rest of the
 * array the way the old length-checked SharedPreferences blob did.
 *
 * Mirrors [BmwPeqStore]'s atomic write + checksum + primary/recovery pattern.
 */
internal class NativeBmwDspStore(private val directory: File) {
    private val primaryFile = File(directory, FILE_NAME)
    private val recoveryFile = File(directory, RECOVERY_FILE_NAME)

    fun save(values: FloatArray): Boolean = synchronized(writeLock) {
        val encoded = encode(values)
        val previous = runCatching {
            primaryFile.takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)
        }.getOrNull()
        val recoveryOk = atomicWrite(recoveryFile, previous ?: encoded)
        val primaryOk = recoveryOk && atomicWrite(primaryFile, encoded)
        val success = recoveryOk && primaryOk
        Timber.i(
            "BMW DSP save target=${primaryFile.absolutePath} success=$success " +
                "recovery=$recoveryOk primary=$primaryOk"
        )
        success
    }

    /** Null when neither primary nor recovery could be read -- caller falls back to defaults/legacy. */
    fun load(): FloatArray? {
        read(primaryFile)?.let { return it }
        return read(recoveryFile)?.also {
            Timber.w("BMW DSP primary unavailable; restored from recovery target=${recoveryFile.absolutePath}")
        }
    }

    internal fun primaryPath(): File = primaryFile
    internal fun recoveryPath(): File = recoveryFile

    private fun read(file: File): FloatArray? {
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText(StandardCharsets.UTF_8)
            require(text.startsWith("$HEADER\n")) { "unsupported header" }
            val lines = text.split('\n')
            require(lines.size >= 2) { "truncated file" }
            val payload = lines.drop(2).joinToString("\n")
            require(
                MessageDigest.isEqual(
                    lines[1].toByteArray(StandardCharsets.US_ASCII),
                    sha256(payload).toByteArray(StandardCharsets.US_ASCII),
                )
            ) { "checksum mismatch" }
            val values = NativeBmwDspValues.DEFAULTS.copyOf()
            lines.drop(2).forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                val index = line.substring(0, separator).toIntOrNull() ?: return@forEach
                val value = line.substring(separator + 1).toFloatOrNull() ?: return@forEach
                if (index in values.indices) values[index] = value
            }
            values
        }.getOrElse {
            Timber.e(it, "BMW DSP restore failed target=${file.absolutePath}")
            null
        }
    }

    private fun encode(values: FloatArray): String {
        val payload = values.indices.joinToString("\n") { index -> "$index=${values[index]}" }
        return "$HEADER\n${sha256(payload)}\n$payload"
    }

    private fun atomicWrite(target: File, text: String): Boolean = runCatching {
        directory.mkdirs()
        require(directory.isDirectory) { "persistent directory is unavailable" }
        val temp = File(directory, "${target.name}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(text.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrElse {
        Timber.e(it, "BMW DSP atomic save failed target=${target.absolutePath}")
        false
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val FILE_NAME = "native_bmw_dsp_state.txt"
        const val RECOVERY_FILE_NAME = "native_bmw_dsp_state.recovery"
        private const val HEADER = "BMW_DSP_STATE_V1"

        // store(context) constructs a new instance per call, so this lock is shared at the
        // class level to actually serialize concurrent save() calls against the same file.
        private val writeLock = Any()
    }
}
