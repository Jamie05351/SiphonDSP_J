package app.siphondsp.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * How the home screen is dressed, beyond which app sits in which slot ([HomeSlots]): custom icons,
 * the wallpaper, and apps hidden from the drawer. Kept in the launcher's own preferences and
 * files, never in the DSP settings.
 *
 * Icons are keyed by what they stand for, a [SlotTarget.encode] string ("app:<component>" or
 * "car:<id>"), so a custom icon follows its app into any slot and into the drawer. Picked images
 * are cropped to a square and drawn in the launcher's own rounded-icon shape, like the apps' own
 * adaptive icons. Image work runs off the main thread.
 */
class LauncherCustomisation internal constructor(private val context: Context) {
    @Immutable
    data class Look(
        val icons: Map<String, ImageBitmap> = emptyMap(),
        val hidden: Set<String> = emptySet(),
        val wallpaper: ImageBitmap? = null,
    )

    private val prefs = context.getSharedPreferences("launcher_look", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "launcher").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _look = MutableStateFlow(Look(hidden = prefs.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()))
    val look: StateFlow<Look> = _look.asStateFlow()

    init {
        scope.launch { lock.withLock { reload() } }
    }

    fun setHidden(key: String, hidden: Boolean) {
        val next = if (hidden) _look.value.hidden + key else _look.value.hidden - key
        _look.value = _look.value.copy(hidden = next)
        prefs.edit { putStringSet(KEY_HIDDEN, next) }
    }

    /** Crops the image at [uri] to a square icon for [key]. [onDone] gets false if it couldn't be read. */
    fun setIcon(key: String, uri: Uri, onDone: (Boolean) -> Unit) = scope.launch {
        val ok = lock.withLock {
            val bitmap = decode(uri, ICON_PX, ICON_PX) ?: return@withLock false
            val square = centreCrop(bitmap, ICON_PX, ICON_PX)
            write(iconFile(key), square, Bitmap.CompressFormat.PNG)
            reload()
            true
        }
        withContext(Dispatchers.Main) { onDone(ok) }
    }

    fun clearIcon(key: String) = scope.launch {
        lock.withLock {
            iconFile(key).delete()
            reload()
        }
    }

    /** Uses the image at [uri] as the wallpaper, sized for the 1280 x 480 screen. */
    fun setWallpaper(uri: Uri, onDone: (Boolean) -> Unit) = scope.launch {
        val ok = lock.withLock {
            val bitmap = decode(uri, WALL_W, WALL_H) ?: return@withLock false
            write(wallpaperFile(), centreCrop(bitmap, WALL_W, WALL_H), Bitmap.CompressFormat.JPEG)
            reload()
            true
        }
        withContext(Dispatchers.Main) { onDone(ok) }
    }

    fun clearWallpaper() = scope.launch {
        lock.withLock {
            wallpaperFile().delete()
            reload()
        }
    }

    /**
     * The whole home layout as one JSON document: the nine slots, hidden apps, custom icons and the
     * wallpaper (images as base64). Written to [uri] off the main thread.
     */
    fun export(slots: List<SlotTarget>, uri: Uri, onDone: (Boolean) -> Unit) = scope.launch {
        val ok = try {
            val doc = lock.withLock {
                JSONObject()
                    .put("format", FORMAT)
                    .put("version", 1)
                    .put("slots", JSONArray(slots.map { it.encode() }))
                    .put("hidden", JSONArray(_look.value.hidden.toList()))
                    .put("icons", JSONObject().apply {
                        for (key in iconKeys()) put(key, base64(iconFile(key)))
                    })
                    .apply { if (wallpaperFile().exists()) put("wallpaper", base64(wallpaperFile())) }
            }
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(doc.toString().toByteArray()) }
            true
        } catch (e: Exception) {
            Timber.w(e, "Home layout export failed")
            false
        }
        withContext(Dispatchers.Main) { onDone(ok) }
    }

    /**
     * Reads a document written by [export] and applies it: slots through [applySlots] (on the main
     * thread), the rest here. Nothing is changed unless the whole document reads correctly.
     */
    fun import(uri: Uri, applySlots: (List<SlotTarget>) -> Unit, onDone: (Boolean) -> Unit) = scope.launch {
        val slots = try {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            require(bytes.size <= MAX_BACKUP_BYTES) { "Backup too large" }
            val doc = JSONObject(String(bytes))
            require(doc.optString("format") == FORMAT) { "Not a home layout backup" }
            val slots = doc.getJSONArray("slots").let { a -> List(a.length()) { SlotTarget.decode(a.getString(it)) } }
            val hidden = doc.optJSONArray("hidden")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet()
            val icons = doc.optJSONObject("icons")?.let { o -> o.keys().asSequence().associateWith { Base64.decode(o.getString(it), Base64.NO_WRAP) } } ?: emptyMap()
            val wallpaper = doc.optString("wallpaper").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
            // Every image must decode before anything is replaced.
            (icons.values + listOfNotNull(wallpaper)).forEach { requireNotNull(BitmapFactory.decodeByteArray(it, 0, it.size)) }
            lock.withLock {
                iconKeys().forEach { iconFile(it).delete() }
                icons.forEach { (key, png) -> iconFile(key).writeBytes(png) }
                wallpaperFile().delete()
                wallpaper?.let { wallpaperFile().writeBytes(it) }
                prefs.edit { putStringSet(KEY_HIDDEN, hidden) }
                _look.value = _look.value.copy(hidden = hidden)
                reload()
            }
            slots
        } catch (e: Exception) {
            Timber.w(e, "Home layout import failed")
            null
        }
        withContext(Dispatchers.Main) {
            if (slots != null) applySlots(slots)
            onDone(slots != null)
        }
    }

    private fun reload() {
        val icons = iconKeys().mapNotNull { key ->
            BitmapFactory.decodeFile(iconFile(key).path)?.let { key to it.asImageBitmap() }
        }.toMap()
        val wallpaper = BitmapFactory.decodeFile(wallpaperFile().path)?.asImageBitmap()
        _look.value = _look.value.copy(icons = icons, wallpaper = wallpaper)
    }

    // Icon files are named by a hash of their key; the key itself is kept in the index pref.
    private fun iconKeys(): Set<String> = prefs.getStringSet(KEY_ICONS, emptySet())!!.filter { iconFile(it).exists() }.toSet()

    private fun iconFile(key: String): File {
        val known = prefs.getStringSet(KEY_ICONS, emptySet())!!
        if (key !in known) prefs.edit { putStringSet(KEY_ICONS, known + key) }
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "icon_$hash.png")
    }

    private fun wallpaperFile() = File(dir, "wallpaper.jpg")

    /** Decodes [uri], sampled down to about [w] x [h] or a little more. Null if it isn't an image. */
    private fun decode(uri: Uri, w: Int, h: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        Timber.w(e, "Image could not be read")
        null
    }

    private fun centreCrop(source: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / source.width, h.toFloat() / source.height)
        val cropW = min(source.width, (w / scale).toInt())
        val cropH = min(source.height, (h / scale).toInt())
        val x = (source.width - cropW) / 2
        val y = (source.height - cropH) / 2
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(source, x, y, cropW, cropH), w, h, true)
    }

    private fun write(file: File, bitmap: Bitmap, format: Bitmap.CompressFormat) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(format, 92, it) }
        tmp.renameTo(file)
    }

    private fun base64(file: File): String = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

    companion object {
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_ICONS = "icons"
        private const val FORMAT = "siphondsp-home-layout"
        private const val ICON_PX = 192
        private const val WALL_W = 1920
        private const val WALL_H = 720
        private const val MAX_BACKUP_BYTES = 20 * 1024 * 1024

        @Volatile private var instance: LauncherCustomisation? = null

        fun get(context: Context): LauncherCustomisation =
            instance ?: synchronized(this) {
                instance ?: LauncherCustomisation(context.applicationContext).also { instance = it }
            }
    }
}
