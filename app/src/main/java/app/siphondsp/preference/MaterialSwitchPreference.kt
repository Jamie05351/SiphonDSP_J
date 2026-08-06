package app.siphondsp.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import app.siphondsp.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * Boolean preference rendered as a two-segment OFF / ON control.
 *
 * It keeps the same persisted Boolean contract as SwitchPreferenceCompat, so existing DSP
 * preference keys and stored values continue to work unchanged.
 */
class MaterialSwitchPreference : TwoStatePreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init()
    }

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : this(
        context,
        attrs,
        androidx.preference.R.attr.switchPreferenceCompatStyle
    )

    constructor(context: Context) : this(context, null)

    private fun init() {
        widgetLayoutResource = R.layout.preference_materialswitch
    }

    override fun onClick() {
        val newValue = !isChecked
        if (callChangeListener(newValue)) {
            isChecked = newValue
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val group = holder.findViewById(R.id.segmented_switch) as MaterialButtonToggleGroup
        val offButton = holder.findViewById(R.id.segment_off) as MaterialButton
        val onButton = holder.findViewById(R.id.segment_on) as MaterialButton

        group.clearOnButtonCheckedListeners()
        group.check(if (isChecked) R.id.segment_on else R.id.segment_off)

        group.isEnabled = isEnabled
        offButton.isEnabled = isEnabled
        onButton.isEnabled = isEnabled

        group.addOnButtonCheckedListener { toggleGroup, checkedId, buttonIsChecked ->
            if (!buttonIsChecked) return@addOnButtonCheckedListener

            val requestedValue = checkedId == R.id.segment_on
            if (requestedValue == isChecked) return@addOnButtonCheckedListener

            if (callChangeListener(requestedValue)) {
                isChecked = requestedValue
            } else {
                toggleGroup.check(if (isChecked) R.id.segment_on else R.id.segment_off)
            }
        }
    }
}
