package app.siphondsp.model

import android.content.Context
import android.net.Uri
import android.util.Log
import app.siphondsp.R
import app.siphondsp.dsp.FactoryBaseline
import app.siphondsp.utils.Constants
import java.io.File

/**
 * The factory baseline the graphs multiply by. Two places, the first that exists wins:
 *  1. an imported file, `<external files>/factory_baseline.json` (Settings > Misc > Graphs >
 *     Import, or `adb push` to `Android/data/<package>/files/`);
 *  2. the built-in one shipped in the APK (`assets/factory_baseline.json`, this car's).
 * Parsed results are cached until the source changes. A file that fails to parse is reported
 * through [lastError] and treated as no baseline -- the graphs fall back to the app's own response.
 */
object FactoryBaselineStore {
    const val FILE_NAME = "factory_baseline.json"
    private const val TAG = "FactoryBaselineStore"
    private val BUILT_IN_KEY = -1L to -1L

    enum class Source { IMPORTED, BUILT_IN, NONE }

    private var cachedKey: Pair<Long, Long>? = null
    private var cached: FactoryBaseline? = null

    /** Why the last load produced nothing, when a baseline exists but could not be used. */
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

    fun source(context: Context): Source = when {
        file(context)?.isFile == true -> Source.IMPORTED
        hasBuiltIn(context) -> Source.BUILT_IN
        else -> Source.NONE
    }

    /** The baseline to draw, or null when the switch is off or there is no usable baseline. */
    @Synchronized
    fun load(context: Context): FactoryBaseline? {
        if (!isEnabled(context)) return null
        val file = file(context)
        val key = when {
            file != null && file.isFile -> file.lastModified() to file.length()
            hasBuiltIn(context) -> BUILT_IN_KEY
            else -> {
                cachedKey = null
                cached = null
                lastError = null
                return null
            }
        }
        if (key == cachedKey) return cached
        cachedKey = key
        cached = runCatching { FactoryBaseline.parse(activeText(context)!!) }
            .onSuccess { lastError = null }
            .onFailure {
                lastError = it.message
                Log.w(TAG, "ignoring factory baseline: ${it.message}")
            }
            .getOrNull()
        return cached
    }

    /**
     * Validates [uri] as a factory baseline and, only if it parses, makes it the imported file.
     * A bad file never replaces a good one.
     */
    @Synchronized
    fun importFrom(context: Context, uri: Uri): Result<FactoryBaseline> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("could not read the file")
        val baseline = FactoryBaseline.parse(text)
        val target = file(context) ?: throw IllegalStateException("no app storage available")
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IllegalStateException("could not save the file")
        }
        cachedKey = null
        baseline
    }

    /** Writes the baseline in use (imported, else built-in) to [uri]. */
    fun exportTo(context: Context, uri: Uri): Result<Unit> = runCatching {
        val text = activeText(context) ?: throw IllegalStateException("there is no factory baseline to export")
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
            ?: throw IllegalStateException("could not write the file")
    }

    /** Deletes the imported file, so the built-in baseline (if any) is used again. */
    @Synchronized
    fun resetToBuiltIn(context: Context): Boolean {
        val removed = file(context)?.delete() == true
        cachedKey = null
        return removed
    }

    private fun activeText(context: Context): String? {
        file(context)?.takeIf { it.isFile }?.let { return it.readText() }
        return runCatching { context.assets.open(FILE_NAME).bufferedReader().use { it.readText() } }.getOrNull()
    }

    private fun hasBuiltIn(context: Context): Boolean =
        runCatching { context.assets.open(FILE_NAME).close() }.isSuccess
}
