package app.siphondsp.view

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Wraps one [ViewPager2] page and arbitrates a horizontal drag between the page's own content
 * (Compose sliders, mostly) and the pager.
 *
 * The problem: the DSP workspace pages are full-bleed and a [BmwSlider] fills most of every
 * slider row. A quick horizontal flick meant to turn the page would land on a slider and, because
 * the Compose slider claims the horizontal drag the instant it crosses touch slop (and Material3's
 * slider even seeks to the press position on pointer-down), the flick nudged -- and committed -- a
 * value instead of paging.
 *
 * The fix, a velocity gate:
 *  - The page's content never sees the pointer-down until this view has classified the gesture, so
 *    a flick that turns into a page swipe never reaches the slider at all (no seek, no commit).
 *  - Once the gesture crosses touch slop, it's classified once and latched:
 *      * horizontal-dominant AND fast (>= [handoffVelocityPxPerSec])  -> step the pager one page
 *        in the swipe direction; the content is untouched and the rest of the gesture is
 *        swallowed.
 *      * anything else (slow drag, vertical scroll, a tap)             -> the buffered events are
 *        replayed into the content and it takes the gesture; the pager stays put.
 *
 * So a deliberate, unhurried horizontal drag still adjusts a slider; only a *quick* swipe pages.
 * A quick swipe always advances exactly one page (it does not follow the finger 1:1) -- that's the
 * whole intent of a flick, and it sidesteps ViewPager2.fakeDrag needing a long enough event
 * stream to build fling velocity, which a fast flick never has.
 *
 * Built by [DspPager]; not used anywhere else.
 */
class PagerChildSwipeGate(context: Context) : FrameLayout(context) {

    private enum class Phase { IDLE, HOLDING, PAGER, CONTENT }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density

    /**
     * Speed, in px/s of horizontal travel averaged from the pointer-down, at or above which the
     * gesture is treated as a page-turning flick rather than a slider adjustment. A careful slider
     * drag runs a few hundred dp/s; a flick to change page is well over a thousand. Tunable.
     */
    private val handoffVelocityPxPerSec = HANDOFF_DP_PER_SEC * density

    private var pager: ViewPager2? = null
    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    /** Set while we re-dispatch buffered events into our subtree so we don't re-buffer them. */
    private var replaying = false
    private val buffered = ArrayList<MotionEvent>(4)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Everything is handled in dispatchTouchEvent so we can withhold the down from the
        // children until the gesture is classified. Never intercept from here.
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (replaying) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pager = findPager()
                val pageCount = pager?.adapter?.itemCount ?: 0
                if (pager == null || pageCount <= 1) {
                    phase = Phase.IDLE
                    return super.dispatchTouchEvent(ev)
                }
                downX = ev.rawX
                downY = ev.rawY
                downTime = ev.eventTime
                phase = Phase.HOLDING
                clearBuffer()
                buffered.add(MotionEvent.obtain(ev))
                // Hold the whole gesture until we've classified it: the pager must not steal it
                // during the hold, and the content must not see it yet.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> when (phase) {
                Phase.HOLDING -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        buffered.add(MotionEvent.obtain(ev))
                        return true
                    }
                    val horizontal = abs(dx) > abs(dy) * HORIZONTAL_BIAS
                    val dtMs = (ev.eventTime - downTime).coerceAtLeast(1L)
                    val velPxPerSec = abs(dx) / dtMs * 1000f
                    if (horizontal && velPxPerSec >= handoffVelocityPxPerSec) {
                        stepPage(forward = dx < 0f)
                        phase = Phase.PAGER
                        clearBuffer()
                        return true
                    }
                    // Content takes it: hand back everything buffered so far, then this move.
                    phase = Phase.CONTENT
                    replayBufferedInto()
                    return super.dispatchTouchEvent(ev)
                }
                // Flick already actioned; swallow the tail of the gesture.
                Phase.PAGER -> return true
                Phase.CONTENT -> return super.dispatchTouchEvent(ev)
                Phase.IDLE -> return super.dispatchTouchEvent(ev)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val result = when (phase) {
                    Phase.HOLDING -> {
                        // Never moved past slop: a tap (or a cancelled press). Replay it so the
                        // content gets a clean tap -- e.g. a deliberate tap-to-seek on a slider.
                        replayBufferedInto()
                        super.dispatchTouchEvent(ev)
                    }
                    Phase.PAGER -> true
                    Phase.CONTENT -> super.dispatchTouchEvent(ev)
                    Phase.IDLE -> super.dispatchTouchEvent(ev)
                }
                phase = Phase.IDLE
                clearBuffer()
                return result
            }
        }

        // Anything else mid-hold (a second pointer going down, say) -- stop arbitrating and give
        // the gesture to the content from here.
        if (phase == Phase.HOLDING) {
            phase = Phase.CONTENT
            replayBufferedInto()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun stepPage(forward: Boolean) {
        val p = pager ?: return
        val count = p.adapter?.itemCount ?: return
        val target = (p.currentItem + if (forward) 1 else -1).coerceIn(0, count - 1)
        if (target != p.currentItem) p.setCurrentItem(target, true)
    }

    /** Re-dispatch the held events into our subtree, in order, then drop the buffer. */
    private fun replayBufferedInto() {
        replaying = true
        try {
            for (e in buffered) {
                super.dispatchTouchEvent(e)
                e.recycle()
            }
        } finally {
            buffered.clear()
            replaying = false
        }
    }

    private fun clearBuffer() {
        for (e in buffered) e.recycle()
        buffered.clear()
    }

    private fun findPager(): ViewPager2? {
        var v: View? = parent as? View
        while (v != null) {
            if (v is ViewPager2) return v
            v = v.parent as? View
        }
        return null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        phase = Phase.IDLE
        clearBuffer()
    }

    companion object {
        private const val HANDOFF_DP_PER_SEC = 700f
        private const val HORIZONTAL_BIAS = 1.2f

        /** Wrap [content] so it fills the pager page and gate its horizontal drags. */
        fun wrap(context: Context, content: View): PagerChildSwipeGate =
            PagerChildSwipeGate(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(
                    content,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
    }
}
