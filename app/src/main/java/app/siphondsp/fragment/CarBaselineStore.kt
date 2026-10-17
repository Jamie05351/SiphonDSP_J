package app.siphondsp.fragment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import app.siphondsp.dsp.CarBaseline
import app.siphondsp.dsp.CarBaselineMeasurement
import app.siphondsp.dsp.CarBaselineParser
import timber.log.Timber
import java.io.File

/**
 * The car-baseline measurement files behind the Parametric EQ graph (see [CarBaseline]): one per
 * output channel, kept as the imported text under the app's files dir so a new measurement is
 * just another import. Whether the graph includes them is a separate switch, so the baseline can
 * be hidden without losing it.
 */
class CarBaselineStore(context: Context) {

    enum class Side(val label: String) { LEFT("Left"), RIGHT("Right"), BOTH("Left + Right") }

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR)
    private val prefs = appContext.getSharedPreferences(PeqGraphPreferences.PREFS, Context.MODE_PRIVATE)

    var showOnGraph: Boolean
        get() = prefs.getBoolean(KEY_SHOW, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW, value) }

    /** The imported file's name for [side] (LEFT or RIGHT), or null when that channel has none. */
    fun sourceName(side: Side): String? =
        if (fileFor(side).exists()) prefs.getString(nameKey(side), null) ?: "measurement" else null

    fun load(): CarBaseline? {
        val left = read(Side.LEFT)
        val right = read(Side.RIGHT)
        return if (left == null && right == null) null else CarBaseline(left, right)
    }

    /**
     * Parse [uri] and, only if it is a usable measurement, keep it for [side] (both channels for
     * BOTH). Returns null on success or the reason it was rejected; the previous file stays.
     */
    fun import(uri: Uri, side: Side): String? {
        val text = try {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText().also {
                    if (it.length > MAX_CHARS) throw IllegalArgumentException("File is too large to be a measurement export")
                }
            } ?: return "Couldn't open that file"
        } catch (error: Exception) {
            Timber.w(error, "Car baseline read failed")
            return error.message ?: "Couldn't read that file"
        }
        try {
            CarBaselineParser.parse(text.reader().buffered())
        } catch (error: IllegalArgumentException) {
            return error.message ?: "Not a measurement export"
        }
        val name = displayName(uri)
        val targets = if (side == Side.BOTH) listOf(Side.LEFT, Side.RIGHT) else listOf(side)
        // Every target is written to its own temp file first and only renamed over the live file
        // once all of them are on disk, so a failed write (disk full) leaves the previous
        // measurements intact rather than one channel new and the other old, or a truncated file.
        val temps = targets.map { File(dir, "${fileFor(it).name}.tmp") }
        return try {
            dir.mkdirs()
            temps.forEach { it.writeText(text) }
            for ((target, temp) in targets.zip(temps)) {
                if (!temp.renameTo(fileFor(target))) throw java.io.IOException("rename failed for $target")
            }
            prefs.edit { targets.forEach { putString(nameKey(it), name) } }
            null
        } catch (error: Exception) {
            Timber.e(error, "Car baseline save failed")
            "Couldn't save the measurement"
        } finally {
            temps.forEach { it.delete() }
        }
    }

    fun clear() {
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            fileFor(side).delete()
            prefs.edit { remove(nameKey(side)) }
        }
    }

    private fun read(side: Side): CarBaselineMeasurement? {
        val file = fileFor(side)
        if (!file.exists()) return null
        return try {
            file.bufferedReader().use(CarBaselineParser::parse)
        } catch (error: Exception) {
            Timber.w(error, "Stored car baseline for $side unreadable")
            null
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun fileFor(side: Side) = File(dir, if (side == Side.RIGHT) "right.txt" else "left.txt")

    private fun nameKey(side: Side) = if (side == Side.RIGHT) KEY_NAME_RIGHT else KEY_NAME_LEFT

    companion object {
        private const val DIR = "car_baseline"
        // REW's full-resolution text export of one measurement is ~1.5 MB.
        private const val MAX_CHARS = 8_000_000
        private const val KEY_SHOW = "car_baseline_show"
        private const val KEY_NAME_LEFT = "car_baseline_name_left"
        private const val KEY_NAME_RIGHT = "car_baseline_name_right"
    }
}
