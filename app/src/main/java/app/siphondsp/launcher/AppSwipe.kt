package app.siphondsp.launcher

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.siphondsp.R
import app.siphondsp.session.dump.utils.DumpUtils
import app.siphondsp.utils.extensions.PermissionExtensions.hasDumpPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

/**
 * Swipe between Spotify and Waze: while one of them is in front and the other is open too, a
 * sideways swipe in from either edge of the screen brings up the other, sliding in the way the
 * swipe went.
 *
 * Android gives no app the touches meant for another, so the swipe is caught by two thin,
 * invisible overlay windows down the screen edges (J_DSP already holds "display over other apps"
 * for its boot start); everywhere else touches go to the app as usual. Which app is in front comes
 * from the activity manager's dump, read with the DUMP permission J_DSP holds for audio sessions,
 * every [POLL_MS] while the home screen is away ([start] .. [stop]). Without either permission the
 * strips never appear.
 */
object AppSwipe {
    /** The two apps to swipe between. */
    val PAIR = listOf("com.spotify.music", "com.waze")

    private const val POLL_MS = 1_500L
    private const val TOP_DP = 48f          // clear of the head unit's top banner, as the home screen
    private const val STRIP_DP = 18f
    private const val SWIPE_DP = 60f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var poll: Job? = null
    private var strips: List<View> = emptyList()

    /** The app a swipe opens: the one of [PAIR] that isn't in front. Null while the strips are away. */
    private var target: String? = null

    /** What the activity manager says is open: the app in front, and every app with an activity. */
    internal data class Tasks(val front: String?, val open: Set<String>)

    /** The home screen went away: watch for Spotify or Waze in front. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (poll?.isActive == true) return
        if (!Settings.canDrawOverlays(app) || !app.hasDumpPermission()) return
        poll = scope.launch {
            while (isActive) {
                val tasks = withContext(Dispatchers.IO) { DumpUtils.dumpAll(app, "activity", arrayOf("activities")) }?.let(::parse)
                update(app, tasks?.let(::swipeTarget))
                delay(POLL_MS)
            }
        }
    }

    /** The home screen is back: no strips, no watching. */
    fun stop(context: Context) {
        poll?.cancel()
        poll = null
        update(context.applicationContext, null)
    }

    /** The app a swipe should open with [tasks] open: the other of [PAIR], if one is in front and both are open. */
    internal fun swipeTarget(tasks: Tasks): String? {
        val front = tasks.front ?: return null
        if (front !in PAIR) return null
        return PAIR.firstOrNull { it != front && it in tasks.open }
    }

    /**
     * Reads `dumpsys activity activities`: the app in front is the top resumed activity's (the
     * resumed one's before Android 10), and an app is open while any activity record of it is.
     */
    internal fun parse(dump: String): Tasks {
        val front = FRONT.firstNotNullOfOrNull { it.find(dump)?.groupValues?.get(1) }
        val open = RECORD.findAll(dump).map { it.groupValues[1] }.toSet()
        return Tasks(front, open)
    }

    private val RECORD = Regex("""ActivityRecord\{\S+ u\d+ ([A-Za-z0-9_.]+)/""")
    private val FRONT = listOf("topResumedActivity", "mResumedActivity", "ResumedActivity").map {
        Regex("""\b$it[=:]\s*ActivityRecord\{\S+ u\d+ ([A-Za-z0-9_.]+)/""")
    }

    /** Shows the strips with a swipe to [next], or takes them away when there is nothing to swipe to. */
    private fun update(context: Context, next: String?) {
        target = next
        if (next == null) {
            removeStrips(context)
        } else if (strips.isEmpty()) {
            addStrips(context)
        }
        strips.forEach { it.contentDescription = next?.let { pkg -> context.getString(R.string.launcher_swipe_to, label(context, pkg)) } }
    }

    private fun addStrips(context: Context) {
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val added = mutableListOf<View>()
        for (gravity in listOf(Gravity.START, Gravity.END)) {
            val strip = strip(context)
            try {
                wm.addView(strip, params(context, gravity))
                added += strip
            } catch (e: RuntimeException) {
                // A refused overlay must never take the home screen (and the audio) down with it.
                Timber.w(e, "Swipe strip could not be shown")
            }
        }
        strips = added
    }

    private fun removeStrips(context: Context) {
        val wm = context.getSystemService(WindowManager::class.java)
        strips.forEach { runCatching { wm?.removeViewImmediate(it) } }
        strips = emptyList()
    }

    /** A strip's window: the full height below the top banner, [STRIP_DP] wide, on one edge. */
    private fun params(context: Context, edge: Int) = WindowManager.LayoutParams(
        context.dp(STRIP_DP),
        context.resources.displayMetrics.heightPixels - context.dp(TOP_DP),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or edge
        y = context.dp(TOP_DP)
    }

    /** An invisible strip that opens [target] once a touch on it has moved [SWIPE_DP] sideways. */
    @SuppressLint("ClickableViewAccessibility")
    private fun strip(context: Context): View = View(context).apply {
        var startX = 0f
        var startY = 0f
        var done = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    done = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    // Mostly sideways, so a drag down the edge doesn't switch.
                    if (!done && abs(dx) >= context.dp(SWIPE_DP) && abs(dx) > 2 * abs(dy)) {
                        done = true
                        target?.let { switchTo(context, it, leftwards = dx < 0) }
                    }
                }
            }
            true
        }
    }

    /** Brings [pkg] to the front in its existing task, sliding in from the side the swipe came from. */
    private fun switchTo(context: Context, pkg: String, leftwards: Boolean) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        val options = if (leftwards) {
            ActivityOptions.makeCustomAnimation(context, R.anim.swipe_in_from_right, R.anim.swipe_out_to_left)
        } else {
            ActivityOptions.makeCustomAnimation(context, R.anim.swipe_in_from_left, R.anim.swipe_out_to_right)
        }
        if (Launch.start(context, intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), options.toBundle())) {
            // The app that was in front is the one to swipe back to, before the next poll says so.
            target = PAIR.firstOrNull { it != pkg }
        }
    }

    private fun label(context: Context, pkg: String): CharSequence = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
    }.getOrDefault(pkg)

    private fun Context.dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}
