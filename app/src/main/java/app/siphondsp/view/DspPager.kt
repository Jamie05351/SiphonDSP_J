package app.siphondsp.view

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2

/**
 * Wraps a fixed set of pre-built page views in a [ViewPager2] -- for the DSP workspace screens
 * that page their sections (Gains & Delay, Crossovers & Tilt, Compressor, All-pass; the
 * Parametric EQ screen builds its own pager and is deliberately not routed through here).
 * Horizontal swipe (see [PagerChildSwipeGate]) is the only way to change page.
 *
 * No on-screen page indicator: a bottom dot row sat on top of slider rows on several pages, and a
 * top-right numbered-chip row collided with a real card in that corner on others -- every spot
 * tried so far overlaps real content on at least one of the pages this wraps. A numbered strip in
 * the top toolbar or a dedicated footer row (tried even earlier) both read as an unwanted opaque
 * bar sitting on top of the workspace chrome. Given that, no floating indicator beats one that's
 * sometimes in the way.
 */
object DspPager {
    fun build(
        context: Context,
        pages: List<View>,
        onPageSelected: (Int) -> Unit = {},
        // Several host fragments rebuild their pager from scratch on every onResume (a fresh
        // ComposeView per page, so edits made elsewhere while the screen was stopped show up) --
        // without this, that rebuild silently snapped back to page 0 every time the screen was
        // merely resumed, e.g. switching away and back, or the whole app backgrounded and
        // returned to. The caller reads the outgoing pager's [currentPage] before rebuilding and
        // feeds it back in here so the swipe position survives the rebuild.
        initialPage: Int = 0,
    ): View {
        // Each page is wrapped in a PagerChildSwipeGate: a quick horizontal flick that lands on a
        // slider row turns the page instead of nudging (and committing) the slider, while a slow,
        // deliberate horizontal drag still adjusts the slider. The gate also satisfies ViewPager2's
        // requirement that every page explicitly fill it -- programmatically-built pages have no
        // LayoutParams until something sets them, which otherwise throws "Pages must fill the whole
        // ViewPager2" the instant the page is measured.
        val gatedPages = pages.map { page -> PagerChildSwipeGate.wrap(context, page) }

        val viewPager = ViewPager2(context).apply {
            id = VIEW_PAGER_ID
            adapter = StaticPagerAdapter(gatedPages)
            setCurrentItem(initialPage.coerceIn(0, pages.lastIndex), false)
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPageSelected(position)
            }
        })

        val root = FrameLayout(context)
        root.addView(viewPager, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    /** Reads back the swipe position from a [View] previously returned by [build], or 0 if
     *  [pagerRoot] is null or wasn't built by [build] (e.g. the first call, with nothing to
     *  restore yet). Pair with [build]'s [initialPage] to carry the page across a rebuild. */
    fun currentPage(pagerRoot: View?): Int =
        pagerRoot?.findViewById<ViewPager2>(VIEW_PAGER_ID)?.currentItem ?: 0

    // A fixed id (not tied to any layout XML) so currentPage() can find the ViewPager2 built by a
    // *previous* build() call before it's torn down and replaced. Reused across every DspPager
    // instance in the process -- fine, since lookups are always scoped to one pagerRoot subtree.
    private val VIEW_PAGER_ID = View.generateViewId()
}
