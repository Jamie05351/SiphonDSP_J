package app.siphondsp.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import app.siphondsp.R
import app.siphondsp.fragment.NativeBmwDspResponseView
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class NativeBmwDspResponsePreference : Preference {
    private var values = FloatArray(0)
    private var responseView: NativeBmwDspResponseView? = null
    private var signalPathView: TextView? = null
    private val format = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        layoutResource = R.layout.preference_native_bmw_response
        isSelectable = false
    }

    constructor(context: Context) : this(context, null)

    fun setValues(newValues: FloatArray) {
        values = newValues.copyOf()
        updateViews()
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        responseView = holder.findViewById(R.id.native_bmw_response_view) as? NativeBmwDspResponseView
        signalPathView = holder.findViewById(R.id.native_bmw_signal_path) as? TextView
        updateViews()
    }

    private fun updateViews() {
        if (values.size < 35) return
        responseView?.setValues(values)
        val topology = if (values[16] >= .5f) "LR4" else "BW3"
        val compressor = if (values[28] >= .5f) "compressor on" else "compressor bypassed"
        val tilt = if (values[25] >= .5f) "tilt ${format.format(values[26])} dB" else "tilt off"
        signalPathView?.text =
            "Subsonic ${format.format(values[13])} Hz  →  Low ${format.format(values[15])} Hz $topology  +  Mid ${format.format(values[18])} Hz LR4  →  $compressor  →  $tilt  →  L/R corrected"
    }
}
