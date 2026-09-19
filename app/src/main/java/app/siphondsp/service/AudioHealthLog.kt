package app.siphondsp.service

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tiny persistent event log for the audio path: the last [MAX_LINES] state changes, recoveries,
 * service starts and -- most importantly -- the reason the service stopped itself.
 *
 * Logcat is the only other place these reasons go, and it can't be read on the head unit. When
 * the audio dies and the unit needs a reset, this file survives the reset, so the status badge's
 * details dialog can show what happened last time. Each line carries the wall-clock time and the
 * device uptime: uptime resets on boot, so "up 00:00:14" beside a failure means it happened right
 * after start-up (e.g. the capture permission wasn't available yet).
 *
 * Writes are queued to a single background thread; nothing here touches the audio thread.
 */
internal object AudioHealthLog {
    const val MAX_LINES = 80
    private const val FILE_NAME = "audio_health.log"

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SiphonDSP-HealthLog").apply { isDaemon = true }
    }
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun record(context: Context, message: String) {
        // Diagnostics must never take the audio service down -- including when it has no Android
        // context yet (the lifecycle unit tests build the service bare), so guard the whole call.
        try {
            val app = context.applicationContext ?: return
            val line = "${synchronized(lock) { clock.format(Date()) }} (up ${uptime()})  $message"
            io.execute {
                try {
                    synchronized(lock) {
                        val file = File(app.filesDir, FILE_NAME)
                        val lines = if (file.exists()) file.readLines() else emptyList()
                        file.writeText((lines + line).takeLast(MAX_LINES).joinToString("\n") + "\n")
                    }
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /** Newest last. Empty when nothing has been recorded or the file can't be read. */
    fun read(context: Context): List<String> = try {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.exists()) file.readLines() else emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun uptime(): String {
        val totalSeconds = SystemClock.elapsedRealtime() / 1000
        return "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
    }
}
