package app.siphondsp.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.siphondsp.interop.NativeDsp
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The convolver's impulse response: one WAV kept in app storage (files/convolver/ir.wav), with the
 * name it was picked under beside it. The native engine reads it from here at service start and
 * on every import; the enable switch and gain trim live in the DSP config array instead
 * ([NativeBmwDspValues.INDEX_CONVOLVER_ENABLED] / [NativeBmwDspValues.INDEX_CONVOLVER_GAIN]).
 *
 * Blocking file I/O and native WAV decoding: call off the main thread.
 */
object ConvolverIrStore {
    private const val DIRECTORY = "convolver"
    private const val IR_FILE = "ir.wav"
    private const val NAME_FILE = "ir.name"
    // Far above any IR the engine accepts (65536 frames, stereo, 64-bit): a guard against copying
    // a stray huge file before native rejects it.
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    // The highest rate the pipeline runs at (it clamps to 44.1..48 kHz): an IR that fits here
    // fits at every stream rate.
    private const val CHECK_SAMPLE_RATE = 48_000f

    private val lock = Any()

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)
    fun irFile(context: Context) = File(directory(context), IR_FILE)
    fun hasIr(context: Context): Boolean = irFile(context).isFile

    /** The file name the IR was picked under, or null with no IR stored. */
    fun displayName(context: Context): String? = synchronized(lock) {
        if (!hasIr(context)) return null
        runCatching { File(directory(context), NAME_FILE).readText().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: IR_FILE
    }

    /**
     * Copies the WAV at [uri] into app storage, replacing the stored IR -- only once the native
     * reader has accepted it (mono or stereo, not silent, short enough), so a bad pick never
     * replaces a working IR. Null on success, else a message for the user.
     */
    fun importIr(context: Context, uri: Uri): String? = synchronized(lock) {
        val dir = directory(context)
        if (!dir.isDirectory && !dir.mkdirs()) return "Can't create the IR folder"
        val temp = File(dir, "$IR_FILE.import")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return "Can't open the file"
            input.use { stream ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) return "The file is too large to be an impulse response"
                        out.write(buffer, 0, read)
                    }
                    out.fd.sync()
                }
            }
            NativeDsp.checkConvolverIr(temp.absolutePath, CHECK_SAMPLE_RATE)?.let { return it }
            moveIntoPlace(temp, irFile(context))
            File(dir, NAME_FILE).writeText(displayNameOf(context, uri) ?: IR_FILE)
            Timber.i("Convolver IR imported from $uri")
            null
        } catch (e: IOException) {
            Timber.e(e, "Convolver IR import failed")
            "Couldn't copy the file: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: SecurityException) {
            Timber.e(e, "Convolver IR import failed")
            "No permission to read the file"
        } finally {
            temp.delete()
        }
    }

    /** Forgets the stored IR. */
    fun clear(context: Context) = synchronized(lock) {
        irFile(context).delete()
        File(directory(context), NAME_FILE).delete()
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.replace('\n', ' ')
}
