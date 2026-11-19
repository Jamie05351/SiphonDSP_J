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
 * The legacy stereo WAV remains files/convolver/ir.wav. Three independent correction WAVs live
 * under files/convolver/bands/{low,mid,high}/ir.wav. Names and both modes' trims are retained. The native engine reads it from here at service start and
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

    const val MODE_STEREO = 0
    const val MODE_BANDS = 1
    const val SLOT_STEREO = -1
    private fun settings(context: Context) = context.getSharedPreferences("convolver_modes", Context.MODE_PRIVATE)
    fun mode(context: Context): Int = settings(context).getInt("mode", MODE_STEREO)
        .takeIf { it == MODE_BANDS } ?: MODE_STEREO
    fun setMode(context: Context, mode: Int): Boolean {
        require(mode == MODE_STEREO || mode == MODE_BANDS)
        return settings(context).edit().putInt("mode", mode).commit()
    }
    fun savedGain(context: Context, mode: Int, fallback: Float): Float =
        settings(context).getFloat("gain_$mode", fallback).takeIf { it.isFinite() } ?: fallback
    fun saveGain(context: Context, mode: Int, gain: Float): Boolean =
        gain.isFinite() && settings(context).edit().putFloat("gain_$mode", gain).commit()
    fun allBandsStored(context: Context): Boolean = (0..2).all { hasIr(context, it) }
    private fun directory(context: Context, band: Int = SLOT_STEREO): File {
        require(band in SLOT_STEREO..2)
        val base = File(context.filesDir, DIRECTORY)
        return if (band == SLOT_STEREO) base else File(File(base, "bands"), arrayOf("low", "mid", "high")[band])
    }
    fun irFile(context: Context, band: Int = SLOT_STEREO) = File(directory(context, band), IR_FILE)
    fun hasIr(context: Context, band: Int = SLOT_STEREO): Boolean = irFile(context, band).isFile

    /** The file name the IR was picked under, or null with no IR stored. */
    fun displayName(context: Context, band: Int = SLOT_STEREO): String? = synchronized(lock) {
        if (!hasIr(context, band)) return null
        runCatching { File(directory(context, band), NAME_FILE).readText().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: IR_FILE
    }

    /**
     * Copies the WAV at [uri] into app storage, replacing the stored IR -- only once the native
     * reader has accepted it (mono or stereo, not silent, short enough), so a bad pick never
     * replaces a working IR. Null on success, else a message for the user.
     */
    fun importIr(context: Context, uri: Uri, band: Int = SLOT_STEREO): String? = synchronized(lock) {
        val dir = directory(context, band)
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
            moveIntoPlace(temp, irFile(context, band))
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
    fun clear(context: Context, band: Int = SLOT_STEREO) = synchronized(lock) {
        irFile(context, band).delete()
        File(directory(context, band), NAME_FILE).delete()
    }

    /** A mismatch is returned before touching stored files; the caller may explicitly retry. */
    fun importBandPackage(context: Context, uri: Uri, lowMid: Float, midHigh: Float, allowMismatch: Boolean = false, currentCorners: List<Pair<Float, Float>> = emptyList()): String? = synchronized(lock) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return "Can't open the package"
            BandIrPackage.prepare(input, context.cacheDir) {
                NativeDsp.checkConvolverIr(it.absolutePath, CHECK_SAMPLE_RATE)
            }.use { prepared ->
                val manifest = prepared.manifest
                if (!allowMismatch && (!manifest.matchesCrossovers(lowMid, midHigh) || currentCorners.any { !manifest.matchesCrossovers(it.first, it.second) })) {
                    return "Crossover mismatch: package ${manifest.lowMidHz}/${manifest.midHighHz} Hz, app $lowMid/$midHigh Hz (all left/right band corners must match). Import without changing app crossovers?"
                }
                val base = directory(context)
                if (!base.isDirectory && !base.mkdirs()) return "Can't create the IR folder"
                val staged = File(base, "bands-staged-${java.util.UUID.randomUUID()}")
                check(staged.mkdirs()) { "Can't stage band files" }
                try {
                    for ((band, name) in manifest.files.withIndex()) {
                        val slotDir = File(staged, arrayOf("low", "mid", "high")[band])
                        check(slotDir.mkdir())
                        File(prepared.directory, name).copyTo(File(slotDir, IR_FILE))
                        File(slotDir, NAME_FILE).writeText(name)
                    }
                    File(prepared.directory, "manifest.json").copyTo(File(staged, "manifest.json"))
                    val target = File(base, "bands")
                    val backup = File(base, "bands-backup-${java.util.UUID.randomUUID()}")
                    val hadPrevious = target.exists()
                    if (hadPrevious) check(target.renameTo(backup)) { "Can't preserve previous band set" }
                    if (!staged.renameTo(target)) {
                        if (hadPrevious) check(backup.renameTo(target)) { "Could not restore previous band set" }
                        error("Could not install the band set")
                    }
                    backup.deleteRecursively()
                } finally {
                    staged.deleteRecursively()
                }
            }
            null
        } catch (e: Exception) {
            Timber.e(e, "Band IR package import failed")
            "Package rejected: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Refreshes Android's cached preferences after BackupManager replaces their XML file. */
    fun refreshModePreferencesAfterRestore(context: Context) {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/convolver_modes.xml")
        if (!file.isFile) return
        require(file.length() <= 16384) { "Convolver mode preferences are too large" }
        val parser = android.util.Xml.newPullParser()
        var mode = 0
        val gains = mutableMapOf<String, Float>()
        file.inputStream().use { input ->
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val key = parser.getAttributeValue(null, "name")
                    val value = parser.getAttributeValue(null, "value")
                    if (parser.name == "int" && key == "mode") mode = value?.toIntOrNull() ?: 0
                    if (parser.name == "float" && key in setOf("gain_0", "gain_1")) {
                        value?.toFloatOrNull()?.takeIf { it.isFinite() }?.let {
                            gains[key!!] = it.coerceIn(NativeBmwDspValues.CONVOLVER_MIN_GAIN_DB, NativeBmwDspValues.CONVOLVER_MAX_GAIN_DB)
                        }
                    }
                }
                event = parser.next()
            }
        }
        val edit = settings(context).edit().clear().putInt("mode", if (mode == 1) 1 else 0)
        gains.forEach { (key, value) -> edit.putFloat(key, value) }
        check(edit.commit()) { "Could not restore convolution mode preferences" }
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
