package app.siphondsp.view

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import app.siphondsp.R
import app.siphondsp.databinding.ViewNumberInputBoxBinding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class NumberInputBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.theme,
    defStyleRes: Int = 0,
) : LinearLayout(context, attrs) {

    private var onValueChangedListener: ((Float) -> Unit)? = null
    private val binding: ViewNumberInputBoxBinding
    private val df = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private var suppressTextWatcher = false

    init {
        df.maximumFractionDigits = 4
    }

    var customStepScale: ((Float, Boolean) -> Float)? = null

    var precision: Int
        get() = df.maximumFractionDigits
        set(value) {
            df.maximumFractionDigits = value
            setFormattedValue(this.value, notify = false)
        }

    var min: Float = -Float.MAX_VALUE
        set(value) {
            field = value
            normalizeCurrentValue(notify = false)
        }

    var max: Float = Float.MAX_VALUE
        set(value) {
            field = value
            normalizeCurrentValue(notify = false)
        }

    var step: Float = 1f

    var value: Float
        set(newValue) {
            if (isInEditMode) return
            setFormattedValue(newValue.coerceIn(min, max), notify = true)
        }
        get() = binding.input.text?.toString()?.toFloatOrNull() ?: 0f

    var suffixText: String = ""
        set(value) {
            field = value
            binding.inputLayout.suffixText = value
        }

    var helperText: String = ""
        set(value) {
            field = value
            binding.inputLayout.helperText = value
        }

    var helperTextEnabled: Boolean = false
        set(value) {
            field = value
            binding.inputLayout.isHelperTextEnabled = value
        }

    var hintText: String = ""
        set(value) {
            field = value
            binding.inputLayout.hint = value
        }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable) {
            if (suppressTextWatcher) return
            val parsed = s.toString().toFloatOrNull() ?: return
            if (parsed.isFinite() && parsed in min..max) {
                onValueChangedListener?.invoke(parsed)
            }
        }
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.NumberInputBox, defStyleAttr, defStyleRes)
        binding = ViewNumberInputBoxBinding.inflate(LayoutInflater.from(context), this, true)

        precision = a.getInteger(R.styleable.NumberInputBox_floatPrecision, precision)
        step = a.getFloat(R.styleable.NumberInputBox_step, 1f)
        min = a.getFloat(R.styleable.NumberInputBox_android_min, min)
        max = a.getFloat(R.styleable.NumberInputBox_android_max, max)
        setFormattedValue(a.getFloat(R.styleable.NumberInputBox_value, 0f).coerceIn(min, max), notify = false)
        suffixText = a.getString(R.styleable.NumberInputBox_suffixText) ?: suffixText
        helperText = a.getString(R.styleable.NumberInputBox_helperText) ?: helperText
        helperTextEnabled = a.getBoolean(R.styleable.NumberInputBox_helperTextEnabled, helperTextEnabled)
        hintText = a.getString(R.styleable.NumberInputBox_hintText) ?: hintText
        a.recycle()

        binding.input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) normalizeCurrentValue(notify = true)
        }

        binding.plus.setOnClickListener {
            val finalStep = customStepScale?.invoke(value, true) ?: step
            value = (value + finalStep).coerceIn(min, max)
        }

        binding.minus.setOnClickListener {
            val finalStep = customStepScale?.invoke(value, false) ?: step
            value = (value - finalStep).coerceIn(min, max)
        }
    }

    private fun setFormattedValue(newValue: Float, notify: Boolean) {
        val formatted = df.format(newValue)
        if (binding.input.text?.toString() != formatted) {
            suppressTextWatcher = true
            binding.input.setText(formatted)
            binding.input.setSelection(formatted.length)
            suppressTextWatcher = false
        }
        if (notify) onValueChangedListener?.invoke(newValue)
    }

    private fun normalizeCurrentValue(notify: Boolean) {
        val parsed = binding.input.text?.toString()?.toFloatOrNull() ?: return
        if (!parsed.isFinite()) return
        setFormattedValue(parsed.coerceIn(min, max), notify)
    }

    fun isCurrentValueValid(): Boolean {
        val parsed = binding.input.text?.toString()?.toFloatOrNull() ?: return false
        return parsed.isFinite() && parsed in min..max
    }

    override fun onAttachedToWindow() {
        binding.input.addTextChangedListener(textWatcher)
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        binding.input.removeTextChangedListener(textWatcher)
        super.onDetachedFromWindow()
    }

    fun setOnValueChangedListener(listener: ((Float) -> Unit)?) {
        onValueChangedListener = listener
    }
}
