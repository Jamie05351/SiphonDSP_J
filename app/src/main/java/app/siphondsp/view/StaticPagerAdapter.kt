package app.siphondsp.view

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Wraps a fixed set of pre-built page views (one per position) for a ViewPager2, skipping
 * Fragment-per-page machinery when the pages are already-inflated static views rather than
 * separate fragment destinations.
 */
class StaticPagerAdapter(
    private val pages: List<View>,
    private val onPageAttached: (position: Int) -> Unit = {},
) : RecyclerView.Adapter<StaticPagerAdapter.PageViewHolder>() {
    private val attachedPositions = mutableSetOf<Int>()

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = pages.size

    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PageViewHolder(pages[viewType])

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {}

    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION && attachedPositions.add(position)) {
            onPageAttached(position)
        }
    }
}
