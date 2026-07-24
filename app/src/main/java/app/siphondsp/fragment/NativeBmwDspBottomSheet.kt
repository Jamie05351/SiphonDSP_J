package app.siphondsp.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.setPadding
import app.siphondsp.interop.JamesDspWrapper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

class NativeBmwDspBottomSheet : BottomSheetDialogFragment() {
    private lateinit var values: FloatArray
    private lateinit var root: LinearLayout
    private var loading = true
    private val format = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        values = loadValues()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = androidx.core.widget.NestedScrollView(requireContext())
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addHeader()
        section("Module")
        toggle("Native BMW DSP enabled", 0)

        section("Measurements / routing")
        toggle("LPF passthrough", 1)
        toggle("HPF passthrough", 2)
        choice("Channel isolation", 3, listOf("Both", "Mute L", "Mute R"))
        choice("Measurement mute", 4, listOf("Off", "Mute low", "Mute mid"))

        section("Gains")
        slider("Headroom", 5, -12f, 0f, 1f, "dB")
        slider("Low gain L", 6, -6f, 0f, .1f, "dB")
        slider("Low gain R", 7, -6f, 0f, .1f, "dB")
        slider("Mid gain L", 8, -6f, 0f, .1f, "dB")
        slider("Mid gain R", 9, -6f, 0f, .1f, "dB")
        slider("Post gain L", 10, -6f, 6f, .1f, "dB")
        slider("Post gain R", 11, -6f, 6f, .1f, "dB")

        section("Subsonic protection")
        toggle("Subsonic BW4", 12)
        slider("Subsonic frequency", 13, 20f, 60f, 1f, "Hz")

        section("Crossovers")
        toggle("Mute low band", 14)
        slider("Low LPF frequency", 15, 80f, 200f, 1f, "Hz")
        choice("Low topology", 16, listOf("BW3", "LR4"))
        toggle("Mute mid band", 17)
        slider("Mid HPF frequency", 18, 80f, 200f, 1f, "Hz")

        section("Delays / polarity")
        toggle("Invert low polarity", 19)
        toggle("Invert mid polarity", 20)
        slider("Mid delay L", 21, 0f, 2.8f, .01f, "ms")
        slider("Mid delay R", 22, 0f, 2.8f, .01f, "ms")
        slider("Low delay L", 23, 0f, 2.8f, .01f, "ms")
        slider("Low delay R", 24, 0f, 2.8f, .01f, "ms")

        section("Post-sum tonality tilt")
        toggle("Tilt active", 25)
        slider("Tilt amount", 26, -6f, 6f, .1f, "dB")
        slider("Tilt pivot", 27, 200f, 2000f, 1f, "Hz")

        section("Low-band compressor")
        toggle("Compressor active", 28)
        slider("Threshold", 29, -18f, 0f, 1f, "dB")
        slider("Ratio", 30, 1f, 10f, .1f, ":1")
        slider("Soft knee", 31, 0f, 12f, 1f, "dB")
        slider("Attack", 32, 1f, 50f, 1f, "ms")
        slider("Release", 33, 20f, 400f, 1f, "ms")
        slider("Makeup gain", 34, 0f, 6f, .1f, "dB")

        loading = false
        apply()
    }

    private fun addHeader() {
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(requireContext()).apply {
            text = "BMW DSP 6.3 — Native"
            textSize = 20f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(requireContext()).apply {
            text = "Reset"
            setOnClickListener {
                values = DEFAULTS.copyOf()
                saveValues()
                dismiss()
                NativeBmwDspBottomSheet().show(parentFragmentManager, TAG)
            }
        })
        row.addView(MaterialButton(requireContext()).apply {
            text = "Close"
            setOnClickListener { dismiss() }
        })
        root.addView(row)
    }

    private fun section(title: String) {
        root.addView(TextView(requireContext()).apply {
            text = title
            textSize = 17f
            setPadding(0, dp(18), 0, dp(4))
        })
    }

    private fun toggle(label: String, index: Int) {
        root.addView(SwitchCompat(requireContext()).apply {
            text = label
            isChecked = values[index] >= .5f
            setOnCheckedChangeListener { _, checked ->
                if (!loading) {
                    values[index] = if (checked) 1f else 0f
                    changed()
                }
            }
        })
    }

    private fun choice(label: String, index: Int, options: List<String>) {
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(requireContext()).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(values[index].roundToInt().coerceIn(0, options.lastIndex))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!loading) {
                        values[index] = position.toFloat()
                        changed()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        })
        root.addView(row)
    }

    private fun slider(label: String, index: Int, min: Float, max: Float, step: Float, suffix: String) {
        val title = TextView(requireContext())
        fun updateTitle() { title.text = "$label: ${format.format(values[index])} $suffix" }
        updateTitle()
        root.addView(title)
        root.addView(SeekBar(requireContext()).apply {
            this.max = ((max - min) / step).roundToInt()
            progress = ((values[index] - min) / step).roundToInt().coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !loading) {
                        values[index] = min + progress * step
                        updateTitle()
                        changed()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
    }

    private fun changed() {
        saveValues()
        apply()
    }

    private fun apply() {
        runCatching { JamesDspWrapper.configureNativeBmwDsp(values) }
    }

    private fun loadValues(): FloatArray {
        val saved = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val parsed = saved?.split(',')?.mapNotNull(String::toFloatOrNull)?.toFloatArray()
        return if (parsed?.size == DEFAULTS.size) parsed else DEFAULTS.copyOf()
    }

    private fun saveValues() {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, values.joinToString(","))
            .apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val TAG = "native_bmw_dsp"
        private const val PREFS = "native_bmw_dsp"
        private const val KEY = "values"
        val DEFAULTS = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            -6f, 0f, 0f, -1f, -1f, 0f, 0f,
            1f, 32f,
            0f, 150f, 0f,
            0f, 125f,
            0f, 0f,
            0f, 0f, 0f, 0f,
            1f, 3f, 550f,
            1f, -12f, 2f, 8f, 40f, 250f, 1.5f,
        )
    }
}
