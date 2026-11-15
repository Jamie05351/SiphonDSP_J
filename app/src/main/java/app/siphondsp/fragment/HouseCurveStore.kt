package app.siphondsp.fragment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import app.siphondsp.dsp.HouseCurve
import app.siphondsp.dsp.HouseCurveParser
import timber.log.Timber
import java.io.File

/**
 * The house curve drawn over the Parametric EQ graph (see [HouseCurve]): kept as the imported
 * text under the app's files dir, so it survives restarts, with its file name and whether the
 * overlay is shown in the graph's preferences. Display only.
 *
 * Every call does file work: call off the main thread, and under [CarBaselineStore.operationLock]
 * (shared with the car baseline: both are the graph's imported overlays, and one lock keeps an
 * Activity recreated mid-import from reading a half-written file).
 */
class HouseCurveStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR)
    private val file = File(dir, FILE)
    private val temp = File(dir, "$FILE.tmp")
    private val prefs = appContext.getSharedPreferences(PeqGraphPreferences.PREFS, Context.MODE_PRIVATE)

    /** Whether the graph draws the overlay; the legend's toggle. On by default. */
    var showOnGraph: Boolean
        get() = prefs.getBoolean(KEY_SHOW, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW, value) }

    /** The imported file's name, or null when there is no house curve. */
    fun sourceName(): String? = if (file.exists()) prefs.getString(KEY_NAME, null) ?: "house curve" else null

    fun load(): HouseCurve? {
        temp.delete()  // an import the process died in: the live file is untouched
        if (!file.exists()) return null
        return try {
            file.bufferedReader().use(HouseCurveParser::parse)
        } catch (error: Exception) {
            Timber.w(error, "Stored house curve unreadable")
            null
        }
    }

    /**
     * Parse [uri] and, only if it is a usable house curve, keep it in place of the current one.
     * Returns null on success or the reason it was rejected; the previous curve stays.
     */
    fun import(uri: Uri): String? {
        val text = try {
            appContext.contentResolver.openInputStream(uri)?.use { readBounded(it.bufferedReader()) }
                ?: return "Couldn't open that file"
        } catch (error: Exception) {
            Timber.w(error, "House curve read failed")
            return error.message ?: "Couldn't read that file"
        }
        try {
            HouseCurveParser.parse(text.reader().buffered())
        } catch (error: IllegalArgumentException) {
            return error.message ?: "Not a house curve"
        }
        return try {
            dir.mkdirs()
            temp.writeText(text)
            // Over the live file in one step: a failed write never touches the current curve.
            if (!temp.renameTo(file)) throw java.io.IOException("rename failed")
            prefs.edit { putString(KEY_NAME, displayName(uri)) }
            null
        } catch (error: Exception) {
            Timber.e(error, "House curve save failed")
            "Couldn't save the house curve"
        } finally {
            temp.delete()
        }
    }

    fun clear() {
        temp.delete()
        file.delete()
        prefs.edit { remove(KEY_NAME) }
    }

    private fun readBounded(reader: java.io.Reader): String {
        val text = StringBuilder()
        val buffer = CharArray(64 * 1024)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) return text.toString()
            if (text.length + read > MAX_CHARS) throw IllegalArgumentException("File is too large to be a house curve")
            text.appendRange(buffer, 0, read)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val DIR = "house_curve"
        const val FILE = "house_curve.txt"
        const val MAX_CHARS = 8_000_000
        const val KEY_SHOW = "house_curve_show"
        const val KEY_NAME = "house_curve_name"
    }
}
