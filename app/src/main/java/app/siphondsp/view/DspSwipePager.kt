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
 * content of the currently selected DSP submenu. Page factories are evaluated synchronously when
 * create() is called (from Fragment.onCreateView), while the fragment is definitely attached.
 * RecyclerView therefore never calls back into a fragment later just to manufacture a page.
 */
object DspSwipePager {
    fun create(parent: ViewGroup, pageFactories: List<() -> View>): ViewPager2 {
        val pages = pageFactories.map { it() }
        return ViewPager2(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = pages.size.coerceIn(1, 3)
            adapter = PageAdapter(pages)
            getChildAt(0)?.overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    private class PageAdapter(
        private val pages: List<View>,
    ) : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = pages.size
        override fun getItemViewType(position: Int): Int = position

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
            val page = pages[position]
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
    }

    private class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
}
