package app.siphondsp.fragment

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.setPadding
import app.siphondsp.interop.JamesDspWrapper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val scroll = androidx.core.widget.NestedScrollView(requireContext()).apply {
            isFillViewport = true
        }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addHeader()

        switchCard("Native BMW DSP", "Enable the complete native BMW processing chain", 0)

        sectionCard("Measurements / routing") {
            addSwitchRow("LPF passthrough", "Bypass the low-pass crossover", 1)
            addSwitchRow("HPF passthrough", "Bypass the mid/high high-pass crossover", 2)
            addChoiceRow("Channel isolation", 3, listOf("Both", "Mute L", "Mute R"))
            addChoiceRow("Measurement mute", 4, listOf("Off", "Mute low", "Mute mid"))
        }

        sectionCard("Gain structure") {
            addSliderRow("Headroom", 5, -12f, 0f, 1f, "dB")
            addSliderRow("Low gain L", 6, -6f, 0f, .1f, "dB")
            addSliderRow("Low gain R", 7, -6f, 0f, .1f, "dB")
            addSliderRow("Mid gain L", 8, -6f, 0f, .1f, "dB")
            addSliderRow("Mid gain R", 9, -6f, 0f, .1f, "dB")
            addSliderRow("Post gain L", 10, -6f, 6f, .1f, "dB")
            addSliderRow("Post gain R", 11, -6f, 6f, .1f, "dB")
        }

        sectionCard("Subsonic protection") {
            addSwitchRow(
                "Subsonic BW4",
                "Protect the under-seat woofers from very low frequencies",
                12,
            )
            addSliderRow("Subsonic frequency", 13, 20f, 60f, 1f, "Hz")
        }

        sectionCard("Crossovers") {
            addSwitchRow("Mute low band", null, 14)
            addSliderRow("Low LPF frequency", 15, 80f, 200f, 1f, "Hz")
            addChoiceRow("Low topology", 16, listOf("BW3", "LR4"))
            addSwitchRow("Mute mid band", null, 17)
            addSliderRow("Mid HPF frequency", 18, 80f, 200f, 1f, "Hz")
        }

        sectionCard("Delay / polarity") {
            addSwitchRow("Invert low polarity", null, 19)
            addSwitchRow("Invert mid polarity", null, 20)
            addSliderRow("Mid delay L", 21, 0f, 2.8f, .01f, "ms")
            addSliderRow("Mid delay R", 22, 0f, 2.8f, .01f, "ms")
            addSliderRow("Low delay L", 23, 0f, 2.8f, .01f, "ms")
            addSliderRow("Low delay R", 24, 0f, 2.8f, .01f, "ms")
        }

        sectionCard("Post-sum tonality tilt") {
            addSwitchRow(
                "Tilt active",
                "Broad tonal balance adjustment after the bands rejoin",
                25,
            )
            addSliderRow("Tilt amount", 26, -6f, 6f, .1f, "dB")
            addSliderRow("Tilt pivot", 27, 200f, 2000f, 1f, "Hz")
        }

        sectionCard("Low-band compressor") {
            addSwitchRow(
                "Compressor active",
                "Stereo-linked protection and level control for the low band",
                28,
            )
            addSliderRow("Threshold", 29, -18f, 0f, 1f, "dB")
            addSliderRow("Ratio", 30, 1f, 10f, .1f, ":1")
            addSliderRow("Soft knee", 31, 0f, 12f, 1f, "dB")
            addSliderRow("Attack", 32, 1f, 50f, 1f, "ms")
            addSliderRow("Release", 33, 20f, 400f, 1f, "ms")
            addSliderRow("Makeup gain", 34, 0f, 6f, .1f, "dB")
        }

        loading = false
        applyConfiguration()
    }

    private fun addHeader() {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(16))
        }
        row.addView(
            TextView(requireContext()).apply {
                text = "BMW DSP 6.3"
                textSize = 24f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(MaterialButton(requireContext()).apply {
            text = "Reset"
            setOnClickListener {
                values = DEFAULTS.copyOf()
                saveValues()
                applyConfiguration()
                dismiss()
                NativeBmwDspBottomSheet().show(parentFragmentManager, TAG)
            }
        })
        row.addView(
            MaterialButton(requireContext()).apply {
                text = "Close"
                setOnClickListener { dismiss() }
            },
            marginStartParams(dp(8)),
        )
        root.addView(row)
    }

    private fun switchCard(title: String, subtitle: String?, index: Int) {
        val card = createCard()
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20))
        }
        content.addView(
            labelBlock(title, subtitle),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        content.addView(createSwitch(index))
        card.addView(content)
        root.addView(card, cardParams(dp(12)))
    }

    private fun sectionCard(title: String, build: LinearLayout.() -> Unit) {
        root.addView(TextView(requireContext()).apply {
            text = title
            textSize = 18f
            setPadding(dp(6), dp(18), dp(6), dp(10))
        })
        val card = createCard()
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(18))
            build()
        }
        card.addView(content)
        root.addView(card, cardParams(dp(4)))
    }

    private fun LinearLayout.addSwitchRow(title: String, subtitle: String?, index: Int) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(
            labelBlock(title, subtitle),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(createSwitch(index))
        addView(row)
    }

    private fun LinearLayout.addChoiceRow(label: String, index: Int, options: List<String>) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 16f
        })
        row.addView(
            Spinner(requireContext()).apply {
                adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    options,
                )
                setSelection(values[index].roundToInt().coerceIn(0, options.lastIndex))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (!loading) {
                            values[index] = position.toFloat()
                            changed()
                        }
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(6)
            },
        )
        addView(row)
    }

    private fun LinearLayout.addSliderRow(
        label: String,
        index: Int,
        min: Float,
        max: Float,
        step: Float,
        suffix: String,
    ) {
        val block = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(16))
        }
        val labelRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(
            TextView(requireContext()).apply {
                text = label
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val valueText = TextView(requireContext()).apply { textSize = 16f }
        fun updateValue() {
            valueText.text = "${format.format(values[index])}$suffix"
        }
        updateValue()
        labelRow.addView(valueText)
        block.addView(labelRow)

        block.addView(
            Slider(requireContext()).apply {
                valueFrom = min
                valueTo = max
                stepSize = step
                value = values[index].coerceIn(min, max)
                addOnChangeListener { _, newValue, fromUser ->
                    if (fromUser && !loading) {
                        values[index] = newValue
                        updateValue()
                        changed()
                    }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
                topMargin = dp(4)
            },
        )
        addView(block)
    }

    private fun createCard() = MaterialCardView(requireContext()).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = resolveColor(com.google.android.material.R.attr.colorOutline)
    }

    private fun createSwitch(index: Int) = SwitchCompat(requireContext()).apply {
        isChecked = values[index] >= .5f
        setOnCheckedChangeListener { _, checked ->
            if (!loading) {
                values[index] = if (checked) 1f else 0f
                changed()
            }
        }
    }

    private fun labelBlock(title: String, subtitle: String?): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = title
                textSize = 17f
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(requireContext()).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(resolveColor(android.R.attr.textColorSecondary))
                    setPadding(0, dp(3), dp(12), 0)
                })
            }
        }

    private fun changed() {
        saveValues()
        applyConfiguration()
    }

    private fun applyConfiguration() {
        runCatching { JamesDspWrapper.configureNativeBmwDsp(values) }
    }

    private fun loadValues(): FloatArray {
        val saved = requireContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val parsed = saved?.split(',')?.mapNotNull(String::toFloatOrNull)?.toFloatArray()
        return if (parsed?.size == DEFAULTS.size) parsed else DEFAULTS.copyOf()
    }

    private fun saveValues() {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, values.joinToString(","))
            .apply()
    }

    private fun cardParams(bottomMargin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.bottomMargin = bottomMargin }

    private fun marginStartParams(margin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { marginStart = margin }

    private fun resolveColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
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
