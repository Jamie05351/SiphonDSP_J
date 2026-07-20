package app.siphondsp.adapter

import android.annotation.SuppressLint
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import app.siphondsp.R

@SuppressLint("RestrictedApi")
class RoundedRipplePreferenceGroupAdapter(preferenceGroup: PreferenceGroup) : PreferenceGroupAdapter(preferenceGroup) {
    override fun onBindViewHolder(
        holder: PreferenceViewHolder,
        position: Int
    ) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position)
        preference ?: return

        val groupBackgroundRes = preference.extras.getInt(EXTRA_GROUP_BACKGROUND_RES, 0)
        if (groupBackgroundRes != 0) {
            holder.itemView.background = ContextCompat.getDrawable(preference.context, groupBackgroundRes)
            return
        }

        if(preference !is PreferenceGroup && preference.isSelectable) {
            holder.itemView.background = ContextCompat.getDrawable(
                preference.context,
                R.drawable.ripple_rounded
            )
        }
    }

    companion object {
        /**
         * Int extra (a drawable res id) a Preference can set on itself via [Preference.getExtras]
         * to opt into a specific background instead of the default ripple_rounded/none behavior --
         * used by LiveprogParamsFragment to render grouped-card borders around each section.
         */
        const val EXTRA_GROUP_BACKGROUND_RES = "grouped_card_background_res"
    }
}