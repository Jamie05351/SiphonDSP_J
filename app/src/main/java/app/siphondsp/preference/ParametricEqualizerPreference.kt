package app.siphondsp.preference

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import app.siphondsp.R
import app.siphondsp.databinding.PreferenceParametricEqualizerBinding
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver

class ParametricEqualizerPreference : Preference {

    private var binding: PreferenceParametricEqualizerBinding? = null
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateFromPreferences()
        }
    }

    constructor(
        context: Context, attrs: AttributeSet?,
        defStyleAttr: Int
    ) : this(context, attrs, defStyleAttr, 0)

    constructor(
        context: Context, attrs: AttributeSet?
    ) : this(context, attrs, androidx.preference.R.attr.preferenceStyle)

    constructor(
        context: Context
    ) : this(context, null)

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        layoutResource = R.layout.preference_parametric_equalizer
    }

    override fun onAttached() {
        context.registerLocalReceiver(broadcastReceiver, IntentFilter(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        super.onAttached()
    }

    override fun onDetached() {
        context.unregisterLocalReceiver(broadcastReceiver)
        super.onDetached()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        // The legacy preference value is intentionally ignored. BmwPeqState is
        // the only authoritative source for the main-card preview.
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getString(index).toString()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        binding = PreferenceParametricEqualizerBinding.bind(holder.itemView)
        updateFromPreferences()
    }

    fun updateFromPreferences() {
        setEqualizerViewValues(BmwPeqState.load(context))
    }

    private fun setEqualizerViewValues(state: BmwPeqState) {
        val combinedBands = ParametricEqBandList().apply {
            addAll(state.fullRangeBands)
            addAll(state.lowBandBands)
            addAll(state.midBandBands)
        }
        binding?.layoutEqualizer?.setBands(
            combinedBands,
            state.preampDb.toDouble(),
        )
        binding?.bandCount?.text = buildString {
            append("${state.fullRangeBands.size} Full")
            append(" · ${state.lowBandBands.size} Low")
            append(" · ${state.midBandBands.size} Mid")
            if (state.preampDb != 0f) append(" · ${state.preampDb} dB preamp")
        }
    }
}
