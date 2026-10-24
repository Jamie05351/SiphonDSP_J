package app.siphondsp.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import timber.log.Timber
import kotlin.math.min

/**
 * The app sidebar over other apps: after an app is opened from the home screen, the nine home
 * slots run down the left edge for [SHOW_MS], so another one is a tap away without going home.
 * Then it shrinks to a small tab on the edge, which brings it back when tapped.
 *
 * Android can't shrink another app to make room, so this is an overlay window (J_DSP already
 * holds "display over other apps" for its boot start) over the app's left edge; it takes no key
 * focus, and touches anywhere else go to the app. Plain views, not Compose: the window lives on
 * its own, outside any activity. It goes away when the J_DSP home screen comes back ([hide]).
 * Without the overlay permission it simply doesn't appear.
 */
object AppDock {
    private const val SHOW_MS = 5_000L
    private const val TOP_DP = 48f          // clear of the head unit's top banner, as the home screen
    private const val BOTTOM_DP = 8f
    private const val MAX_PITCH_DP = 64f
    private const val TAB_W_DP = 22f
    private const val TAB_H_DP = 76f

    private val PANEL = Color.argb(235, 5, 5, 6)
    private val EDGE = 0xFF2A2C31.toInt()
    private val SIGNAL = 0xFFA855F7.toInt()
    private val TILE = 0xFF1C1E23.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val collapse = Runnable { current?.let { show(it.context, expanded = false) } }
    private var current: View? = null

    /** Shows the sidebar, open for [SHOW_MS] then as the tab ([expanded] false: the tab at once). */
    fun show(context: Context, expanded: Boolean = true) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        handler.removeCallbacks(collapse)
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val view = if (expanded) panel(app) else tab(app)
        try {
            current?.let { runCatching { wm.removeViewImmediate(it) } }
            current = null
            wm.addView(view, params(if (expanded) app.dp(TOP_DP) else tabTop(app)))
            current = view
        } catch (e: RuntimeException) {
            // A refused overlay must never take the home screen (and the audio) down with it.
            Timber.w(e, "App sidebar could not be shown")
            return
        }
        if (expanded) handler.postDelayed(collapse, SHOW_MS)
    }

    /** Removes the sidebar (the J_DSP home screen is showing again). */
    fun hide(context: Context) {
        handler.removeCallbacks(collapse)
        val view = current ?: return
        current = null
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    /** Where the tab's window starts: centred down the edge below the top banner. */
    private fun tabTop(context: Context): Int {
        val height = context.resources.displayMetrics.heightPixels - context.dp(TOP_DP) - context.dp(BOTTOM_DP)
        return context.dp(TOP_DP) + ((height - context.dp(TAB_H_DP)) / 2).coerceAtLeast(0)
    }

    /** The window: only as big as what's in it, so touches beside it reach the app. */
    private fun params(top: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = top
    }

    /** The open sidebar: one tile per filled home slot, sized so all of them fit the height. */
    private fun panel(context: Context): View {
        val targets = HomeSlots.get(context).slots.value.filter { it != SlotTarget.Empty }
        val apps = InstalledApps.get(context).apps.value.orEmpty()
        val icons = LauncherCustomisation.get(context).look.value.icons
        val height = context.resources.displayMetrics.heightPixels - context.dp(TOP_DP) - context.dp(BOTTOM_DP)
        val pitch = min(context.dp(MAX_PITCH_DP), height / targets.size.coerceAtLeast(1))
        val tile = pitch - context.dp(6f)

        val column = TouchAwareColumn(context) {
            // Any touch on it, a tile's included, keeps it open for another [SHOW_MS], so it can't
            // shrink away between a tile's press and release.
            handler.removeCallbacks(collapse)
            handler.postDelayed(collapse, SHOW_MS)
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(5f), context.dp(4f), context.dp(5f), context.dp(4f))
            background = GradientDrawable().apply {
                setColor(PANEL)
                setStroke(context.dp(1.5f), SIGNAL)
                cornerRadii = floatArrayOf(0f, 0f, 16f, 16f, 16f, 16f, 0f, 0f).map { context.dp(it).toFloat() }.toFloatArray()
            }
        }
        for (target in targets) {
            val item: View = when (target) {
                is SlotTarget.App -> {
                    val entry = apps.firstOrNull { it.component == target.component }
                    // Once the app list has loaded, an app that has gone isn't offered.
                    if (entry == null && apps.isNotEmpty()) continue
                    val label = entry?.label ?: target.component.packageName
                    val open = {
                        if (Launch.component(context, target.component)) show(context)
                    }
                    val image = icons[target.encode()] ?: entry?.icon
                    if (image != null) {
                        ImageView(context).apply {
                            setImageBitmap(image.asAndroidBitmap())
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = label
                            setOnClickListener { open() }
                        }
                    } else {
                        textTile(context, label.take(1).uppercase(), label, tile, open)
                    }
                }
                is SlotTarget.Car -> textTile(context, context.getString(target.function.short), context.getString(target.function.label), tile) {
                    when {
                        target.function.run(context) -> show(context)
                        // iDrive, AUX and the like switch the display away from Android.
                        target.function.command != null -> hide(context)
                    }
                }
                SlotTarget.Empty -> continue
            }
            column.addView(item, LinearLayout.LayoutParams(tile, tile).apply {
                val gap = (pitch - tile) / 2
                setMargins(0, gap, 0, gap)
            })
        }
        return column
    }

    /** A tile with a short name on it, as the home screen draws a car function. */
    private fun textTile(context: Context, text: String, description: String, size: Int, onClick: () -> Unit) =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.24f)
            gravity = Gravity.CENTER
            contentDescription = description
            background = GradientDrawable().apply {
                setColor(TILE)
                setStroke(context.dp(1.5f), SIGNAL)
                cornerRadius = size * 0.24f
            }
            setOnClickListener { onClick() }
        }

    /** The shrunk sidebar: a small purple-edged tab on the screen edge that opens it again. */
    private fun tab(context: Context): View =
        FrameLayout(context).apply {
            addView(
                TextView(context).apply {
                    text = "›"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, context.dp(22f).toFloat())
                    gravity = Gravity.CENTER
                    contentDescription = context.getString(app.siphondsp.R.string.launcher_dock_open)
                    background = GradientDrawable().apply {
                        setColor(PANEL)
                        setStroke(context.dp(1.5f), SIGNAL)
                        cornerRadii = floatArrayOf(0f, 0f, 12f, 12f, 12f, 12f, 0f, 0f).map { context.dp(it).toFloat() }.toFloatArray()
                    }
                    setOnClickListener { show(context) }
                },
                FrameLayout.LayoutParams(context.dp(TAB_W_DP), context.dp(TAB_H_DP)),
            )
        }

    /** A column that reports every touch-down, including those its tiles go on to take. */
    private class TouchAwareColumn(context: Context, private val onTouchDown: () -> Unit) : LinearLayout(context) {
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchDown()
            return false
        }
    }

    private fun Context.dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}
