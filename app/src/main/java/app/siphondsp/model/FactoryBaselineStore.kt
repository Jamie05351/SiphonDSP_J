package app.siphondsp.model

import android.content.Context
import android.util.Log
import app.siphondsp.R
import app.siphondsp.dsp.FactoryBaseline
import app.siphondsp.utils.Constants
import java.io.File

/**
 * Loads the factory baseline from `<external files>/factory_baseline.json`
 * (`Android/data/<package>/files/factory_baseline.json` -- copy it there over USB or adb).
 * Re-reads only when the file's timestamp or size changes; a missing or invalid file is
 * "no baseline", and the graphs fall back to the app's own response.
 */
object FactoryBaselineStore {
    const val FILE_NAME = "factory_baseline.json"
    private const val TAG = "FactoryBaselineStore"

    private var cachedKey: Pair<Long, Long>? = null
    private var cached: FactoryBaseline? = null

    /** Why the last load produced nothing, when the file exists but could not be used. */
    @Volatile
    var lastError: String? = null
        private set

    fun file(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    /** Settings > Misc > Graphs > Factory baseline graphs. Off = every graph shows this app's own response. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).getBoolean(
            context.getString(R.string.key_factory_baseline_enabled),
            context.resources.getBoolean(R.bool.default_factory_baseline_enabled),
        )

    /** The baseline to draw, or null when the switch is off or there is no usable file. */
    @Synchronized
    fun load(context: Context): FactoryBaseline? {
        if (!isEnabled(context)) return null
        val file = file(context)
        if (file == null || !file.isFile) {
            cachedKey = null
            cached = null
            lastError = null
            return null
        }
        val key = file.lastModified() to file.length()
        if (key == cachedKey) return cached
        cachedKey = key
        cached = runCatching { FactoryBaseline.parse(file.readText()) }
            .onSuccess { lastError = null }
            .onFailure {
                lastError = it.message
                Log.w(TAG, "ignoring ${file.path}: ${it.message}")
            }
            .getOrNull()
        return cached
    }
}
