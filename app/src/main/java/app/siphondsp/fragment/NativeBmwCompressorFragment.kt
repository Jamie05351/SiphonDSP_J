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
import com.google.android.material.slider.Slider
import java.util.Locale

class NativeBmwCompressorFragment : Fragment() {
    private enum class Band { LOW, MID }

    /** One band's full set of parameter sliders/value labels -- each band gets its own permanent
     *  instances (inflated once, on its own pager page) rather than one shared set that gets
     *  rebound, since both pages stay attached simultaneously under ViewPager2. */
    private class BandControls(page: View) {
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
    }

    private lateinit var state: NativeBmwCompressorState
    private var selectedBand = Band.LOW
    private lateinit var visualizer: NativeBmwCompressorView
    private lateinit var grTrace: CompressorGrTraceView
    private lateinit var meterText: TextView
    private lateinit var bandTitle: TextView
    private lateinit var enabledSwitch: SwitchCompat
    private lateinit var lowControls: BandControls
    private lateinit var midControls: BandControls
    private val handler = Handler(Looper.getMainLooper())
    private var bindingState = false
    private var pendingPersist = false
    private var lastPersistMs = 0L
    private val flushPersist = Runnable { persistNow() }

    private val meterTick = object : Runnable {
        override fun run() {
            val meter = RootlessAudioProcessorService.nativeBmwCompressorMeter()
            val offset = if (selectedBand == Band.LOW) 0 else 3
            if (meter != null && meter.size >= 6) {
                val input = meter[offset]
                val output = meter[offset + 1]
                val reduction = meter[offset + 2]
                visualizer.setMeter(input, output, reduction)
                grTrace.pushFrame(input, output, reduction)
                meterText.text = String.format(
                    Locale.ENGLISH,
                    "%s   Input %.1f dBFS   Output %.1f dBFS   GR %.1f dB",
                    if (selectedBand == Band.LOW) "LOW" else "MID",
                    input, output, reduction,
                )
            } else {
                meterText.text = "Native engine is not running"
            }
            handler.postDelayed(this, 33L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_native_bmw_compressor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        visualizer = view.findViewById(R.id.compressor_visualizer)
        grTrace = view.findViewById(R.id.compressor_gr_trace)
        meterText = view.findViewById(R.id.compressor_meter_text)
        bandTitle = view.findViewById(R.id.compressor_band_title)
        enabledSwitch = view.findViewById(R.id.compressor_enable)

        // Each band's 6 sliders live on its own swipeable pager page, inflated at runtime from
        // the same page_compressor_band.xml -- rather than a toggle switching one shared set of
        // sliders between bands, swiping the pager itself moves between bands. The visualizer/
        // title/enable switch above stay a fixed header, updated to match whichever band is
        // currently swiped to.
        val pagerContainer = view.findViewById<FrameLayout>(R.id.compressor_slider_pager_container)
        val lowPage = layoutInflater.inflate(R.layout.page_compressor_band, pagerContainer, false)
        val midPage = layoutInflater.inflate(R.layout.page_compressor_band, pagerContainer, false)
        pagerContainer.addView(
            DspPager.build(requireContext(), listOf(lowPage, midPage)) { position ->
                selectedBand = if (position == 0) Band.LOW else Band.MID
                grTrace.reset()
                visualizer.resetHistory()
                refreshHeader()
            },
        )

        lowControls = BandControls(lowPage)
        midControls = BandControls(midPage)
        listOf(lowControls, midControls).forEach { controls ->
            configureSlider(controls.threshold, -24f, 0f, .5f)
            configureSlider(controls.ratio, 1f, 10f, .1f)
            configureSlider(controls.knee, 0f, 12f, 1f)
            configureSlider(controls.attack, 1f, 100f, 1f)
            configureSlider(controls.release, 20f, 800f, 1f)
            configureSlider(controls.makeup, 0f, 6f, .1f)
        }

        state = NativeBmwCompressorState.load(requireContext())
        configureListeners()
        bindBandFromState(Band.LOW)
        bindBandFromState(Band.MID)
        refreshHeader()
    }

    private fun configureSlider(slider: Slider, from: Float, to: Float, step: Float) {
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = step
        // Styled directly here rather than left to BmwDashboardSkin.styleWorkspace()'s later
        // recursive walk: the non-visible band page is off-screen at that point, and ViewPager2
        // doesn't guarantee it's attached to the view hierarchy yet, so the walk can miss it.
        BmwDashboardSkin.styleSlider(slider)
    }

    override fun onStart() {
        super.onStart()
        state = NativeBmwCompressorState.load(requireContext())
        bindBandFromState(Band.LOW)
        bindBandFromState(Band.MID)
        refreshHeader()
        grTrace.reset()
        visualizer.resetHistory()
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
        enabledSwitch.setOnCheckedChangeListener { _, value -> if (!bindingState) applyBandChange(selectedBand, enabled = value) }
        visualizer.onThresholdChanged = { applyBandChange(selectedBand, thresholdDb = it) }
        visualizer.onRatioChanged = { applyBandChange(selectedBand, ratioValue = it) }
        visualizer.onKneeChanged = { applyBandChange(selectedBand, kneeDb = it) }
    }

    private fun bindSliderListeners(controls: BandControls, band: Band) {
        controls.threshold.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, thresholdDb = value) }
        controls.ratio.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, ratioValue = value) }
        controls.knee.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, kneeDb = value) }
        controls.attack.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, attackMs = value) }
        controls.release.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, releaseMs = value) }
        controls.makeup.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) applyBandChange(band, makeupDb = value) }
    }

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
        if (band == selectedBand) refreshHeader()
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

    /** Pushes [band]'s stored state into its own sliders/value labels -- called on initial load,
     *  on reload, and after any change to that band (including changes to the other band, which
     *  is a no-op here since only the matching band's own fields are read). */
    private fun bindBandFromState(band: Band) {
        val controls = if (band == Band.LOW) lowControls else midControls
        val thresholdDb: Float
        val ratioValue: Float
        val kneeDb: Float
        val attackMs: Float
        val releaseMs: Float
        val makeupDb: Float
        if (band == Band.LOW) {
            thresholdDb = state.thresholdDb; ratioValue = state.ratio; kneeDb = state.kneeDb
            attackMs = state.attackMs; releaseMs = state.releaseMs; makeupDb = state.makeupDb
        } else {
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
        controls.thresholdValueText.text = String.format(Locale.ENGLISH, "%.1f dB", thresholdDb)
        controls.ratioValueText.text = String.format(Locale.ENGLISH, "%.1f:1", ratioValue)
        controls.kneeValueText.text = String.format(Locale.ENGLISH, "%.0f dB", kneeDb)
        controls.attackValueText.text = String.format(Locale.ENGLISH, "%.0f ms", attackMs)
        controls.releaseValueText.text = String.format(Locale.ENGLISH, "%.0f ms", releaseMs)
        controls.makeupValueText.text = String.format(Locale.ENGLISH, "%.1f dB", makeupDb)
        bindingState = false
    }

    /** Refreshes the persistent header (title, enable switch, visualizer, GR trace) to match
     *  [selectedBand] -- the only widgets still shared between bands rather than duplicated per
     *  page, so they need to follow whichever band's page is currently swiped into view. */
    private fun refreshHeader() {
        if (!::visualizer.isInitialized) return
        val enabled: Boolean
        val thresholdDb: Float
        val ratioValue: Float
        val kneeDb: Float
        val makeupDb: Float
        if (selectedBand == Band.LOW) {
            enabled = state.enabled; thresholdDb = state.thresholdDb; ratioValue = state.ratio
            kneeDb = state.kneeDb; makeupDb = state.makeupDb
        } else {
            enabled = state.midEnabled; thresholdDb = state.midThresholdDb; ratioValue = state.midRatio
            kneeDb = state.midKneeDb; makeupDb = state.midMakeupDb
        }
        bindingState = true
        enabledSwitch.isChecked = enabled
        bindingState = false
        visualizer.compressorEnabled = enabled
        visualizer.isEnabled = enabled
        visualizer.thresholdDb = thresholdDb
        visualizer.ratio = ratioValue
        visualizer.kneeDb = kneeDb
        visualizer.makeupDb = makeupDb
        grTrace.thresholdDb = thresholdDb
        bandTitle.text = if (selectedBand == Band.LOW) "Low Band Dynamics" else "Mid Band Dynamics"
    }

    companion object {
        private const val PERSIST_THROTTLE_MS = 50L
    }
}
