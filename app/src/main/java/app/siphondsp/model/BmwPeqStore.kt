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
 * The single persistent store for the complete three-bank PEQ state.
 *
 * [primaryFile] is authoritative. [recoveryFile] is the previous/last-known-good
 * transaction copy and is consulted only when the primary is missing or corrupt.
 * Both live in internal no-backup storage and are written atomically.
 */
internal class BmwPeqStore(private val directory: File) {
    internal enum class Source {
        PRIMARY,
        RECOVERY,
        LEGACY_V1_PRIMARY,
        LEGACY_V1_RECOVERY,
        NONE,
    }

    internal data class LoadResult(
        val state: BmwPeqState?,
        val source: Source,
        val primaryError: String? = null,
        val recoveryError: String? = null,
    )

    private val primaryFile = File(directory, FILE_NAME)
    private val recoveryFile = File(directory, RECOVERY_FILE_NAME)

    fun save(state: BmwPeqState): Boolean {
        val encoded = encode(state)
        val recoveryOk = atomicWrite(recoveryFile, encoded)
        val primaryOk = recoveryOk && atomicWrite(primaryFile, encoded)
        val success = recoveryOk && primaryOk
        Timber.i(
            "BMW PEQ save target=${primaryFile.absolutePath} version=$VERSION " +
                "${summary(state)} success=$success recovery=$recoveryOk primary=$primaryOk"
        )
        return success
    }

    fun load(): LoadResult {
        val primary = read(primaryFile)
        if (primary.state != null) {
            logRestore(primary.source, primaryFile, primary.state)
            return primary
        }

        val recovery = read(recoveryFile)
        if (recovery.state != null) {
            logRestore(recovery.source, recoveryFile, recovery.state)
            if (primary.error != null) {
                Timber.w("BMW PEQ primary unavailable reason=${primary.error}; selected recovery")
            }
            return LoadResult(
                recovery.state,
                recovery.source,
                primaryError = primary.error,
                recoveryError = recovery.error,
            )
        }

        Timber.w(
            "BMW PEQ restore source=none target=${primaryFile.absolutePath} version=$VERSION " +
                "primary=${primary.error ?: "missing"} recovery=${recovery.error ?: "missing"}"
        )
        return LoadResult(
            null,
            Source.NONE,
            primaryError = primary.error,
            recoveryError = recovery.error,
        )
    }

    fun loadRecovery(): BmwPeqState? {
        val result = read(recoveryFile)
        result.state?.let { logRestore(result.source, recoveryFile, it) }
        return result.state
    }

    internal fun primaryPath(): File = primaryFile
    internal fun recoveryPath(): File = recoveryFile

    private data class ReadAttempt(
        val state: BmwPeqState?,
        val source: Source,
        val error: String? = null,
    )

    private fun read(file: File): ReadAttempt {
        if (!file.isFile) return ReadAttempt(null, Source.NONE, "missing")
        return runCatching {
            val text = file.readText(StandardCharsets.UTF_8)
            when {
                text.startsWith("$HEADER_V2\n") ->
                    ReadAttempt(decodeV2(text), sourceFor(file, legacy = false))
                text.startsWith("$HEADER_V1\n") ->
                    ReadAttempt(decodeV1(text), sourceFor(file, legacy = true))
                else -> throw IllegalArgumentException("unsupported header")
            }
        }.getOrElse {
            Timber.e(it, "BMW PEQ restore failed target=${file.absolutePath}")
            ReadAttempt(null, Source.NONE, it.message ?: it.javaClass.simpleName)
        }
    }

    private fun sourceFor(file: File, legacy: Boolean): Source = when {
        file == primaryFile && legacy -> Source.LEGACY_V1_PRIMARY
        file == recoveryFile && legacy -> Source.LEGACY_V1_RECOVERY
        file == primaryFile -> Source.PRIMARY
        else -> Source.RECOVERY
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
        Timber.e(it, "BMW PEQ atomic save failed target=${target.absolutePath}")
        false
    }

    private fun encode(state: BmwPeqState): String {
        val payload = listOf(
            state.enabled.toString(),
            state.preampDb.toString(),
            state.fullRangeBands.serialize(),
            state.lowBandBands.serialize(),
            state.midBandBands.serialize(),
        ).joinToString("\n")
        return "$HEADER_V2\n${sha256(payload)}\n$payload"
    }

    private fun decodeV2(text: String): BmwPeqState {
        val lines = text.split('\n')
        require(lines.size == 7) { "unexpected field count" }
        val payload = lines.drop(2).joinToString("\n")
        require(MessageDigest.isEqual(
            lines[1].toByteArray(StandardCharsets.US_ASCII),
            sha256(payload).toByteArray(StandardCharsets.US_ASCII),
        )) { "checksum mismatch" }
        return decodeFields(lines, 2)
    }

    private fun decodeV1(text: String): BmwPeqState {
        val lines = text.split('\n')
        require(lines.size == 6) { "unexpected legacy field count" }
        return decodeFields(lines, 1)
    }

    private fun decodeFields(lines: List<String>, offset: Int): BmwPeqState {
        fun bands(value: String) = ParametricEqBandList().apply { deserialize(value) }
        return BmwPeqState(
            enabled = lines[offset].toBooleanStrict(),
            preampDb = lines[offset + 1].toFloat(),
            fullRangeBands = bands(lines[offset + 2]),
            lowBandBands = bands(lines[offset + 3]),
            midBandBands = bands(lines[offset + 4]),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun logRestore(source: Source, file: File, state: BmwPeqState) {
        Timber.i(
            "BMW PEQ restore source=${source.name.lowercase()} target=${file.absolutePath} " +
                "version=$VERSION ${summary(state)}"
        )
    }

    private fun summary(state: BmwPeqState): String =
        "enabled=${state.enabled} full=${state.fullRangeBands.size} " +
            "low=${state.lowBandBands.size} mid=${state.midBandBands.size}"

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "native_bmw_peq_state.txt"
        const val RECOVERY_FILE_NAME = "native_bmw_peq_state.recovery"
        private const val HEADER_V2 = "BMW_PEQ_STATE_V2"
        private const val HEADER_V1 = "BMW_PEQ_STATE_V1"
    }
}
