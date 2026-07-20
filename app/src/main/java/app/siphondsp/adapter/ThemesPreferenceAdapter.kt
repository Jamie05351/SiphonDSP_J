package app.siphondsp.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import app.siphondsp.R
import app.siphondsp.databinding.PreferenceThemeItemBinding
import app.siphondsp.delegates.ThemingDelegate
import app.siphondsp.model.preference.AppTheme
import app.siphondsp.utils.extensions.ContextExtensions.getResourceColor
import app.siphondsp.utils.preferences.Preferences
import com.google.android.material.color.DynamicColors
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ThemesPreferenceAdapter(private val clickListener: OnItemClickListener) :
    RecyclerView.Adapter<ThemesPreferenceAdapter.ThemeViewHolder>(), KoinComponent {

    private var themes = emptyList<AppTheme>()
    private val preferences: Preferences.App by inject()

    private lateinit var binding: PreferenceThemeItemBinding

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThemeViewHolder {
        val isAmoled = preferences.get<Boolean>(R.string.key_appearance_pure_black)
        val appTheme = themes[viewType]
        val themeResIds = ThemingDelegate.getThemeResIds(appTheme, isAmoled)
        var themedContext: Context = themeResIds.fold(parent.context) {
                context, themeResId ->
            ContextThemeWrapper(context, themeResId)
        }

        if (appTheme == AppTheme.CUSTOM) {
            val seedColor = preferences.get<Int>(R.string.key_appearance_custom_color)
            themedContext = DynamicColors.wrapContextIfAvailable(
                themedContext,
                ThemingDelegate.customColorOptions(seedColor),
            )
        }

        binding = PreferenceThemeItemBinding.inflate(LayoutInflater.from(themedContext), parent, false)
        return ThemeViewHolder(binding.root)
    }

    override fun getItemViewType(position: Int): Int = position

    override fun getItemCount(): Int = themes.size

    override fun onBindViewHolder(holder: ThemesPreferenceAdapter.ThemeViewHolder, position: Int) {
        holder.bind(themes[position])
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setItems(themes: List<AppTheme>) {
        this.themes = themes
        notifyDataSetChanged()
    }

    inner class ThemeViewHolder(private val view: View) : RecyclerView.ViewHolder(view) {

        private val selectedColor = view.context.getResourceColor(androidx.appcompat.R.attr.colorPrimary)
        private val unselectedColor = view.context.getResourceColor(android.R.attr.divider)

        fun bind(appTheme: AppTheme) {
            binding.name.text = view.context.getString(appTheme.titleResId!!)

            // For rounded corners
            binding.badges.clipToOutline = true

            val storedAppTheme = AppTheme.valueOf(preferences.get(R.string.key_appearance_app_theme))
            val isSelected = storedAppTheme == appTheme
            binding.themeCard.isChecked = isSelected
            binding.themeCard.strokeColor = if (isSelected) selectedColor else unselectedColor

            listOf(binding.root, binding.themeCard).forEach {
                it.setOnClickListener {
                    clickListener.onItemClick(bindingAdapterPosition)
                }
            }
        }
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int)
    }
}
