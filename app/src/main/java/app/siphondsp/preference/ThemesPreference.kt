package app.siphondsp.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.siphondsp.R
import app.siphondsp.adapter.ThemesPreferenceAdapter
import app.siphondsp.model.preference.AppTheme
import app.siphondsp.utils.extensions.ContextExtensions.dpToPx

class ThemesPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ListPreference(context, attrs),
    ThemesPreferenceAdapter.OnItemClickListener {

    private var recycler: RecyclerView? = null
    private val adapter = ThemesPreferenceAdapter(this)

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            lastScrollPosition = recyclerView.computeHorizontalScrollOffset()
        }
    }

    var lastScrollPosition: Int? = null

    /** Intercept a theme tap. Return true if handled (skip the default select-and-persist behavior). */
    var onThemeClick: ((AppTheme) -> Boolean)? = null

    var entries: List<AppTheme> = emptyList()
        set(value) {
            field = value
            adapter.setItems(value)
        }

    init {
        layoutResource = R.layout.preference_themes_list
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val boundRecycler = holder.findViewById(R.id.themes_list) as RecyclerView
        if(recycler !== boundRecycler) {
            recycler?.removeOnScrollListener(scrollListener)
            recycler = boundRecycler
            recycler?.addOnScrollListener(scrollListener)
        }

        boundRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        boundRecycler.adapter = adapter

        // Retain scroll position on activity recreate after changing theme
        lastScrollPosition?.let { scrollToOffset(it) }
    }

    /**
     * Forces every theme swatch to be recreated, e.g. after the custom theme's color changes.
     * A plain notifyDataSetChanged() isn't enough here since each swatch's colors are baked
     * into its themed context at creation time, not re-resolved on every bind.
     */
    fun refreshPreviews() {
        recycler?.adapter = null
        recycler?.adapter = adapter
    }

    override fun onItemClick(position: Int) {
        if(position !in entries.indices)
            return

        val theme = entries[position]
        if(onThemeClick?.invoke(theme) == true)
            return

        val newValue = theme.name
        if(callChangeListener(newValue))
            value = newValue
    }

    override fun onClick() {
        // no-op; not actually a DialogPreference
    }

    private fun scrollToOffset(lX: Int) {
        recycler?.let {
            (it.layoutManager as LinearLayoutManager).apply {
                scrollToPositionWithOffset(
                    // 114dp is the width of the pref_theme_item layout
                    lX / 114.dpToPx,
                    -lX % 114.dpToPx,
                )
            }
            lastScrollPosition = it.computeHorizontalScrollOffset()
        }
    }
}
