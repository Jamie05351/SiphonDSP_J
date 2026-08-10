package app.siphondsp.view

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Lightweight horizontal pager used inside DSP workspaces.
 *
 * The workspace navigation rail lives outside the fragment container, so this only pages the
 * content of the currently selected DSP submenu. Pages are ordinary Views rather than nested
 * fragments because the BMW workspaces are built programmatically and all pages share one DSP
 * state array.
 */
object DspSwipePager {
    fun create(parent: ViewGroup, pageFactories: List<() -> View>): ViewPager2 =
        ViewPager2(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = pageFactories.size.coerceAtMost(3)
            adapter = PageAdapter(pageFactories)
            getChildAt(0)?.overScrollMode = View.OVER_SCROLL_NEVER
        }

    private class PageAdapter(
        private val pageFactories: List<() -> View>,
    ) : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val frame = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            return PageHolder(frame)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.container.removeAllViews()
            val page = pageFactories[position]()
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.container.removeAllViews()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = pageFactories.size
    }

    private class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
}
