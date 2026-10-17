package app.siphondsp.fragment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import app.siphondsp.dsp.CarBaseline
import app.siphondsp.dsp.CarBaselineMeasurement
import app.siphondsp.dsp.CarBaselineParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
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
                readBounded(stream.bufferedReader())
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
        // Every target is written to its own temp file first, so a failed write (disk full) never
        // touches the live files. Only then are the live files moved aside and the temps renamed
        // in; if any of those renames fails, every target is put back as it was, so the previous
        // measurements survive rather than one channel new and the other old.
        val lives = targets.map(::fileFor)
        val temps = lives.map { File(dir, "${it.name}.tmp") }
        val backups = lives.map { File(dir, "${it.name}.bak") }
        val hadLive = lives.map { it.exists() }
        var installed = false
        return try {
            dir.mkdirs()
            temps.forEach { it.writeText(text) }
            for (i in lives.indices) {
                if (hadLive[i] && !lives[i].renameTo(backups[i])) throw java.io.IOException("backup failed for ${targets[i]}")
            }
            for (i in lives.indices) {
                if (!temps[i].renameTo(lives[i])) throw java.io.IOException("rename failed for ${targets[i]}")
            }
            installed = true
            prefs.edit { targets.forEach { putString(nameKey(it), name) } }
            null
        } catch (error: Exception) {
            Timber.e(error, "Car baseline save failed")
            if (!installed) restore(lives, backups, hadLive)
            "Couldn't save the measurement"
        } finally {
            temps.forEach { it.delete() }
            if (installed) backups.forEach { it.delete() }
        }
    }

    /** Puts each of [lives] back from its backup (or removes it if there was none before). */
    private fun restore(lives: List<File>, backups: List<File>, hadLive: List<Boolean>) {
        for (i in lives.indices) {
            if (hadLive[i]) {
                if (backups[i].exists()) {
                    lives[i].delete()
                    if (!backups[i].renameTo(lives[i])) Timber.e("Car baseline restore failed for ${lives[i].name}")
                }
            } else {
                lives[i].delete()
            }
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

    /** The whole of [reader], refused as soon as it passes [MAX_CHARS] -- checked as it streams,
     *  so an oversized or endless document is never held in memory whole. */
    private fun readBounded(reader: java.io.Reader): String {
        val text = StringBuilder()
        val buffer = CharArray(64 * 1024)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) return text.toString()
            if (text.length + read > MAX_CHARS) throw IllegalArgumentException("File is too large to be a measurement export")
            text.appendRange(buffer, 0, read)
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
        /**
         * Held by every load, import and clear, file work and publish together, for the whole
         * process: an Activity recreated mid-import gets a new holder, and a per-holder lock
         * would let its load read the files mid-swap and publish a stale (or no) baseline.
         */
        val operationLock = Mutex()

        /** Where imports and clears run: outlives any composable or Activity, so a recreate
         *  never strands one half done with the graph out of step with the files. */
        val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private const val DIR = "car_baseline"
        // REW's full-resolution text export of one measurement is ~1.5 MB.
        private const val MAX_CHARS = 8_000_000
        private const val KEY_SHOW = "car_baseline_show"
        private const val KEY_NAME_LEFT = "car_baseline_name_left"
        private const val KEY_NAME_RIGHT = "car_baseline_name_right"
    }
}
