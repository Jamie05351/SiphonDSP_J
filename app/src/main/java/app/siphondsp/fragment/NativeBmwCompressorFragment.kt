package app.siphondsp.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwCompressorState
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.BmwDashboardSkin
import app.siphondsp.view.CompressorGrTraceView
import app.siphondsp.view.DspPager
import app.siphondsp.view.NativeBmwCompressorView
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlin.math.roundToInt

class NativeBmwCompressorFragment : Fragment() {
    private enum class Band { LOW, MID }

    /** One band's complete, permanently-bound page -- title, enable switch, its own visualizer/
     *  GR trace/meter, and its own 6 parameter sliders. Each band gets its own instances (inflated
     *  once, on its own pager page) rather than one shared set that gets rebound, since both pages
     *  stay attached simultaneously under ViewPager2 and swiping now moves the whole page (visualizer
     *  included), not just the sliders. */
    private class BandControls(page: View) {
        val bandTitle: TextView = page.findViewById(R.id.compressor_band_title)
        val enabledSwitch: SwitchCompat = page.findViewById(R.id.compressor_enable)
        val visualizerCard: MaterialCardView = page.findViewById(R.id.compressor_visualizer_card)
        val visualizer: NativeBmwCompressorView = page.findViewById(R.id.compressor_visualizer)
        val grTrace: CompressorGrTraceView = page.findViewById(R.id.compressor_gr_trace)
        val meterText: TextView = page.findViewById(R.id.compressor_meter_text)
        val threshold: Slider = page.findViewById(R.id.compressor_threshold)
        val ratio: Slider = page.findViewById(R.id.compressor_ratio)
        val knee: Slider = page.findViewById(R.id.compressor_knee)
        val attack: Slider = page.findViewById(R.id.compressor_attack)
        val release: Slider = page.findViewById(R.id.compressor_release)
        val makeup: Slider = page.findViewById(R.id.compressor_makeup)
        // Named *ValueText, not *Value, to avoid colliding with the ratioValue/thresholdDb/etc.
        // Float parameter names used throughout applyBandChange()/bindBandFromState() below.
        val thresholdValueText: TextView = page.findViewById(R.id.compressor_threshold_value)
        val ratioValueText: TextView = page.findViewById(R.id.compressor_ratio_value)
        val kneeValueText: TextView = page.findViewById(R.id.compressor_knee_value)
        val attackValueText: TextView = page.findViewById(R.id.compressor_attack_value)
        val releaseValueText: TextView = page.findViewById(R.id.compressor_release_value)
        val makeupValueText: TextView = page.findViewById(R.id.compressor_makeup_value)
        // The titles' own text ("Threshold", "Makeup gain", ...) is static XML, but their boxed
        // width isn't -- see resizeTitleBoxesToLongest(), which sizes every one of these to
        // whichever single title actually needs the most room, matching how
        // CrossoverDashboardBuilder.addSliderRow sizes its own Kotlin-built title boxes.
        val titleTexts: List<TextView> = listOf(
            page.findViewById(R.id.compressor_threshold_title),
            page.findViewById(R.id.compressor_ratio_title),
            page.findViewById(R.id.compressor_knee_title),
            page.findViewById(R.id.compressor_attack_title),
            page.findViewById(R.id.compressor_release_title),
            page.findViewById(R.id.compressor_makeup_title),
        )
    }

    private lateinit var state: NativeBmwCompressorState
    private lateinit var lowControls: BandControls
    private lateinit var midControls: BandControls
    private val handler = Handler(Looper.getMainLooper())
    private var bindingState = false
    private var pendingPersist = false
    private var lastPersistMs = 0L
    private val flushPersist = Runnable { persistNow() }

    // Both bands' meters/traces update every tick regardless of which page is visible -- the
    // native call already returns both bands' data in one array, so this is cheap, and it means
    // there's no visible catch-up/pop the moment you swipe to the other page.
    private val meterTick = object : Runnable {
        override fun run() {
            val meter = RootlessAudioProcessorService.nativeBmwCompressorMeter()
            if (meter != null && meter.size >= 6) {
                updateMeter(lowControls, meter, offset = 0, label = "LOW")
                updateMeter(midControls, meter, offset = 3, label = "MID")
            } else {
                lowControls.meterText.text = "Native engine is not running"
                midControls.meterText.text = "Native engine is not running"
            }
            handler.postDelayed(this, 33L)
        }
    }

    private fun updateMeter(controls: BandControls, meter: FloatArray, offset: Int, label: String) {
        val input = meter[offset]
        val output = meter[offset + 1]
        val reduction = meter[offset + 2]
        controls.visualizer.setMeter(input, output, reduction)
        controls.grTrace.pushFrame(input, output, reduction)
        controls.meterText.text = String.format(
            Locale.ENGLISH,
            "%s   Input %.1f dBFS   Output %.1f dBFS   GR %.1f dB",
            label, input, output, reduction,
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_native_bmw_compressor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Each band's complete page -- title, switch, visualizer, GR trace, meter, and all 6
        // sliders -- is inflated at runtime from the same page_compressor_band.xml, one instance
        // per band, so swiping the pager moves the whole band view at once instead of just the
        // sliders underneath a fixed header.
        val pagerContainer = view.findViewById<FrameLayout>(R.id.compressor_slider_pager_container)
        val lowPage = layoutInflater.inflate(R.layout.page_compressor_band, pagerContainer, false)
        val midPage = layoutInflater.inflate(R.layout.page_compressor_band, pagerContainer, false)
        pagerContainer.addView(DspPager.build(requireContext(), listOf(lowPage, midPage)))

        lowControls = BandControls(lowPage)
        midControls = BandControls(midPage)
        listOf(lowControls, midControls).forEach { controls ->
            configureSlider(controls.threshold, -24f, 0f, .5f)
            configureSlider(controls.ratio, 1f, 10f, .1f)
            configureSlider(controls.knee, 0f, 12f, 1f)
            configureSlider(controls.attack, 1f, 100f, 1f)
            configureSlider(controls.release, 20f, 800f, 1f)
            configureSlider(controls.makeup, 0f, 6f, .1f)
            // Styled directly here rather than left to BmwDashboardSkin.styleWorkspace()'s later
            // recursive walk: the non-visible band page is off-screen at that point, and ViewPager2
            // doesn't guarantee it's attached to the view hierarchy yet, so the walk can miss it.
            BmwDashboardSkin.styleCard(controls.visualizerCard)
            BmwDashboardSkin.styleSwitch(controls.enabledSwitch)
        }
        resizeTitleBoxesToLongest(lowControls.titleTexts + midControls.titleTexts)

        state = NativeBmwCompressorState.load(requireContext())
        configureListeners()
        bindBandFromState(Band.LOW)
        bindBandFromState(Band.MID)
    }

    private fun configureSlider(slider: Slider, from: Float, to: Float, step: Float) {
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = step
        BmwDashboardSkin.styleSlider(slider)
    }

    /** Sizes every title box in [titles] to whichever single one actually needs the most room --
     *  not a hand-picked dp guess -- so every slider row's title column (and therefore where its
     *  slider starts) lines up across both bands, the same "size to the longest sibling" approach
     *  CrossoverDashboardBuilder.addSliderRow uses for its own Kotlin-built title boxes
     *  (see its pendingTitleBoxes). Each title already has its real font/size/padding applied by
     *  Widget.SiphonDSP.SliderTitleBox, so measuring its own Paint gives the exact width needed. */
    private fun resizeTitleBoxesToLongest(titles: List<TextView>) {
        if (titles.isEmpty()) return
        val horizontalPadding = titles.first().let { it.paddingStart + it.paddingEnd }
        val maxTextWidth = titles.maxOf { it.paint.measureText(it.text.toString()) }
        val boxWidth = (maxTextWidth + horizontalPadding).roundToInt()
        titles.forEach { it.layoutParams = it.layoutParams.apply { width = boxWidth } }
    }

    override fun onStart() {
        super.onStart()
        state = NativeBmwCompressorState.load(requireContext())
        bindBandFromState(Band.LOW)
        bindBandFromState(Band.MID)
        lowControls.grTrace.reset()
        midControls.grTrace.reset()
        lowControls.visualizer.resetHistory()
        midControls.visualizer.resetHistory()
        handler.post(meterTick)
    }

    override fun onStop() {
        handler.removeCallbacks(meterTick)
        if (pendingPersist) {
            handler.removeCallbacks(flushPersist)
            persistNow()
        }
        super.onStop()
    }

    private fun configureListeners() {
        bindSliderListeners(lowControls, Band.LOW)
        bindSliderListeners(midControls, Band.MID)
        bindValueBoxListeners(lowControls, Band.LOW)
        bindValueBoxListeners(midControls, Band.MID)
        lowControls.enabledSwitch.setOnCheckedChangeListener { _, value -> if (!bindingState) applyBandChange(Band.LOW, enabled = value) }
        midControls.enabledSwitch.setOnCheckedChangeListener { _, value -> if (!bindingState) applyBandChange(Band.MID, enabled = value) }
        lowControls.visualizer.onThresholdChanged = { applyBandChange(Band.LOW, thresholdDb = it) }
        lowControls.visualizer.onRatioChanged = { applyBandChange(Band.LOW, ratioValue = it) }
        lowControls.visualizer.onKneeChanged = { applyBandChange(Band.LOW, kneeDb = it) }
        midControls.visualizer.onThresholdChanged = { applyBandChange(Band.MID, thresholdDb = it) }
        midControls.visualizer.onRatioChanged = { applyBandChange(Band.MID, ratioValue = it) }
        midControls.visualizer.onKneeChanged = { applyBandChange(Band.MID, kneeDb = it) }
    }

    private fun bindSliderListeners(controls: BandControls, band: Band) {
        controls.threshold.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, thresholdDb = value) }
        controls.ratio.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, ratioValue = value) }
        controls.knee.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, kneeDb = value) }
        controls.attack.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, attackMs = value) }
        controls.release.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, releaseMs = value) }
        controls.makeup.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, makeupDb = value) }
    }

    /** Tap-to-edit for each value box, matching the pattern every other slider row in the app
     *  already has (CrossoverDashboardBuilder.addSliderRow, the all-pass frequency/Q rows). */
    private fun bindValueBoxListeners(controls: BandControls, band: Band) {
        promptOnClick(controls.thresholdValueText, "Threshold", controls.threshold, "dB") { applyBandChange(band, thresholdDb = it) }
        promptOnClick(controls.ratioValueText, "Ratio", controls.ratio, ":1") { applyBandChange(band, ratioValue = it) }
        promptOnClick(controls.kneeValueText, "Soft knee", controls.knee, "dB") { applyBandChange(band, kneeDb = it) }
        promptOnClick(controls.attackValueText, "Attack", controls.attack, "ms") { applyBandChange(band, attackMs = it) }
        promptOnClick(controls.releaseValueText, "Release", controls.release, "ms") { applyBandChange(band, releaseMs = it) }
        promptOnClick(controls.makeupValueText, "Makeup gain", controls.makeup, "dB") { applyBandChange(band, makeupDb = it) }
    }

    private fun promptOnClick(valueText: TextView, label: String, slider: Slider, suffix: String, onEntered: (Float) -> Unit) {
        valueText.setOnClickListener {
            val min = slider.valueFrom
            val max = slider.valueTo
            val step = slider.stepSize
            requireContext().showInputAlert(
                layoutInflater,
                label,
                "${formatPlain(min)}–${formatPlain(max)}",
                formatPlain(slider.value),
                true,
                suffix,
            ) { entered ->
                val parsed = entered?.toFloatOrNull() ?: return@showInputAlert
                val raw = parsed.coerceIn(min, max)
                // Material Slider requires its value to land on a step-aligned multiple of
                // valueFrom, or applyBandChange -> bindBandFromState's slider.value assignment
                // throws IllegalStateException -- snap to the nearest step first.
                val stored = (min + ((raw - min) / step).roundToInt() * step).coerceIn(min, max)
                onEntered(stored)
            }
        }
    }

    private fun formatPlain(value: Float): String =
        if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else String.format(Locale.ENGLISH, "%.1f", value)

    private fun applyBandChange(
        band: Band,
        enabled: Boolean? = null,
        thresholdDb: Float? = null,
        ratioValue: Float? = null,
        kneeDb: Float? = null,
        attackMs: Float? = null,
        releaseMs: Float? = null,
        makeupDb: Float? = null,
    ) {
        state = if (band == Band.LOW) {
            state.copy(
                enabled = enabled ?: state.enabled,
                thresholdDb = thresholdDb ?: state.thresholdDb,
                ratio = ratioValue ?: state.ratio,
                kneeDb = kneeDb ?: state.kneeDb,
                attackMs = attackMs ?: state.attackMs,
                releaseMs = releaseMs ?: state.releaseMs,
                makeupDb = makeupDb ?: state.makeupDb,
            )
        } else {
            state.copy(
                midEnabled = enabled ?: state.midEnabled,
                midThresholdDb = thresholdDb ?: state.midThresholdDb,
                midRatio = ratioValue ?: state.midRatio,
                midKneeDb = kneeDb ?: state.midKneeDb,
                midAttackMs = attackMs ?: state.midAttackMs,
                midReleaseMs = releaseMs ?: state.midReleaseMs,
                midMakeupDb = makeupDb ?: state.midMakeupDb,
            )
        }
        bindBandFromState(band)
        pendingPersist = true
        handler.removeCallbacks(flushPersist)
        if (SystemClock.uptimeMillis() - lastPersistMs >= PERSIST_THROTTLE_MS) persistNow()
        else handler.postDelayed(flushPersist, PERSIST_THROTTLE_MS)
    }

    private fun persistNow() {
        lastPersistMs = SystemClock.uptimeMillis()
        pendingPersist = false
        state.persistAndApply(requireContext())
    }

    /** Pushes [band]'s stored state into its own page in full -- sliders, value labels, title,
     *  enable switch, and visualizer/GR trace -- called on initial load, on reload, and after any
     *  change to that band (including changes to the other band, which is a no-op here since only
     *  the matching band's own fields are read). */
    private fun bindBandFromState(band: Band) {
        val controls = if (band == Band.LOW) lowControls else midControls
        val enabled: Boolean
        val thresholdDb: Float
        val ratioValue: Float
        val kneeDb: Float
        val attackMs: Float
        val releaseMs: Float
        val makeupDb: Float
        if (band == Band.LOW) {
            enabled = state.enabled
            thresholdDb = state.thresholdDb; ratioValue = state.ratio; kneeDb = state.kneeDb
            attackMs = state.attackMs; releaseMs = state.releaseMs; makeupDb = state.makeupDb
        } else {
            enabled = state.midEnabled
            thresholdDb = state.midThresholdDb; ratioValue = state.midRatio; kneeDb = state.midKneeDb
            attackMs = state.midAttackMs; releaseMs = state.midReleaseMs; makeupDb = state.midMakeupDb
        }
        bindingState = true
        controls.threshold.value = thresholdDb.coerceIn(controls.threshold.valueFrom, controls.threshold.valueTo)
        controls.ratio.value = ratioValue.coerceIn(controls.ratio.valueFrom, controls.ratio.valueTo)
        controls.knee.value = kneeDb.coerceIn(controls.knee.valueFrom, controls.knee.valueTo)
        controls.attack.value = attackMs.coerceIn(controls.attack.valueFrom, controls.attack.valueTo)
        controls.release.value = releaseMs.coerceIn(controls.release.valueFrom, controls.release.valueTo)
        controls.makeup.value = makeupDb.coerceIn(controls.makeup.valueFrom, controls.makeup.valueTo)
        controls.enabledSwitch.isChecked = enabled
        bindingState = false
        controls.thresholdValueText.text = String.format(Locale.ENGLISH, "%.1f dB", thresholdDb)
        controls.ratioValueText.text = String.format(Locale.ENGLISH, "%.1f:1", ratioValue)
        controls.kneeValueText.text = String.format(Locale.ENGLISH, "%.0f dB", kneeDb)
        controls.attackValueText.text = String.format(Locale.ENGLISH, "%.0f ms", attackMs)
        controls.releaseValueText.text = String.format(Locale.ENGLISH, "%.0f ms", releaseMs)
        controls.makeupValueText.text = String.format(Locale.ENGLISH, "%.1f dB", makeupDb)
        controls.bandTitle.text = if (band == Band.LOW) "Low Band Dynamics" else "Mid Band Dynamics"
        controls.visualizer.compressorEnabled = enabled
        controls.visualizer.isEnabled = enabled
        controls.visualizer.thresholdDb = thresholdDb
        controls.visualizer.ratio = ratioValue
        controls.visualizer.kneeDb = kneeDb
        controls.visualizer.makeupDb = makeupDb
        controls.grTrace.thresholdDb = thresholdDb
    }

    companion object {
        private const val PERSIST_THROTTLE_MS = 50L
    }
}
