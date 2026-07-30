package app.siphondsp.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.model.NativeBmwCompressorState
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.view.CompressorGrTraceView
import app.siphondsp.view.NativeBmwCompressorView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import java.util.Locale

class NativeBmwCompressorFragment : Fragment() {
    private enum class Band { LOW, MID }

    private lateinit var state: NativeBmwCompressorState
    private var selectedBand = Band.LOW
    private lateinit var visualizer: NativeBmwCompressorView
    private lateinit var grTrace: CompressorGrTraceView
    private lateinit var meterText: TextView
    private lateinit var bandToggle: MaterialButtonToggleGroup
    private lateinit var enabledSwitch: SwitchCompat
    private lateinit var threshold: Slider
    private lateinit var ratio: Slider
    private lateinit var knee: Slider
    private lateinit var attack: Slider
    private lateinit var release: Slider
    private lateinit var makeup: Slider
    private lateinit var thresholdLabel: TextView
    private lateinit var ratioLabel: TextView
    private lateinit var kneeLabel: TextView
    private lateinit var attackLabel: TextView
    private lateinit var releaseLabel: TextView
    private lateinit var makeupLabel: TextView
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
        bandToggle = view.findViewById(R.id.compressor_band_toggle)
        enabledSwitch = view.findViewById(R.id.compressor_enable)
        threshold = view.findViewById(R.id.compressor_threshold)
        ratio = view.findViewById(R.id.compressor_ratio)
        knee = view.findViewById(R.id.compressor_knee)
        attack = view.findViewById(R.id.compressor_attack)
        release = view.findViewById(R.id.compressor_release)
        makeup = view.findViewById(R.id.compressor_makeup)
        configureSlider(threshold, -24f, 0f, .5f)
        configureSlider(ratio, 1f, 10f, .1f)
        configureSlider(knee, 0f, 12f, 1f)
        configureSlider(attack, 1f, 100f, 1f)
        configureSlider(release, 20f, 800f, 1f)
        configureSlider(makeup, 0f, 6f, .1f)
        thresholdLabel = view.findViewById(R.id.compressor_threshold_label)
        ratioLabel = view.findViewById(R.id.compressor_ratio_label)
        kneeLabel = view.findViewById(R.id.compressor_knee_label)
        attackLabel = view.findViewById(R.id.compressor_attack_label)
        releaseLabel = view.findViewById(R.id.compressor_release_label)
        makeupLabel = view.findViewById(R.id.compressor_makeup_label)
        state = NativeBmwCompressorState.load(requireContext())
        configureListeners()
        bindSelectedBand()
    }

    private fun configureSlider(slider: Slider, from: Float, to: Float, step: Float) {
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = step
    }

    override fun onStart() {
        super.onStart()
        state = NativeBmwCompressorState.load(requireContext())
        bindSelectedBand()
        grTrace.reset()
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
        bandToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || bindingState) return@addOnButtonCheckedListener
            selectedBand = if (checkedId == R.id.compressor_band_mid) Band.MID else Band.LOW
            grTrace.reset()
            bindSelectedBand()
        }
        enabledSwitch.setOnCheckedChangeListener { _, value -> if (!bindingState) updateBand(enabled = value) }
        threshold.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) updateBand(thresholdDb = value) }
        ratio.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) updateBand(ratioValue = value) }
        knee.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) updateBand(kneeDb = value) }
        attack.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) updateBand(attackMs = value) }
        release.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) updateBand(releaseMs = value) }
        makeup.addOnChangeListener { _, value, fromUser -> if (fromUser && !bindingState) updateBand(makeupDb = value) }
        visualizer.onThresholdChanged = { updateBand(thresholdDb = it) }
        visualizer.onRatioChanged = { updateBand(ratioValue = it) }
        visualizer.onKneeChanged = { updateBand(kneeDb = it) }
    }

    private fun updateBand(
        enabled: Boolean? = null,
        thresholdDb: Float? = null,
        ratioValue: Float? = null,
        kneeDb: Float? = null,
        attackMs: Float? = null,
        releaseMs: Float? = null,
        makeupDb: Float? = null,
    ) {
        state = if (selectedBand == Band.LOW) {
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
        bindSelectedBand()
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

    private fun bindSelectedBand() {
        if (!::visualizer.isInitialized) return
        bindingState = true
        bandToggle.check(if (selectedBand == Band.LOW) R.id.compressor_band_low else R.id.compressor_band_mid)
        val enabled: Boolean
        val thresholdDb: Float
        val ratioValue: Float
        val kneeDb: Float
        val attackMs: Float
        val releaseMs: Float
        val makeupDb: Float
        if (selectedBand == Band.LOW) {
            enabled = state.enabled; thresholdDb = state.thresholdDb; ratioValue = state.ratio
            kneeDb = state.kneeDb; attackMs = state.attackMs; releaseMs = state.releaseMs; makeupDb = state.makeupDb
        } else {
            enabled = state.midEnabled; thresholdDb = state.midThresholdDb; ratioValue = state.midRatio
            kneeDb = state.midKneeDb; attackMs = state.midAttackMs; releaseMs = state.midReleaseMs; makeupDb = state.midMakeupDb
        }
        enabledSwitch.isChecked = enabled
        threshold.value = thresholdDb.coerceIn(threshold.valueFrom, threshold.valueTo)
        ratio.value = ratioValue.coerceIn(ratio.valueFrom, ratio.valueTo)
        knee.value = kneeDb.coerceIn(knee.valueFrom, knee.valueTo)
        attack.value = attackMs.coerceIn(attack.valueFrom, attack.valueTo)
        release.value = releaseMs.coerceIn(release.valueFrom, release.valueTo)
        makeup.value = makeupDb.coerceIn(makeup.valueFrom, makeup.valueTo)
        visualizer.compressorEnabled = enabled
        visualizer.isEnabled = enabled
        visualizer.thresholdDb = thresholdDb
        visualizer.ratio = ratioValue
        visualizer.kneeDb = kneeDb
        visualizer.makeupDb = makeupDb
        grTrace.thresholdDb = thresholdDb
        thresholdLabel.text = String.format(Locale.ENGLISH, "Threshold   %.1f dB", thresholdDb)
        ratioLabel.text = String.format(Locale.ENGLISH, "Ratio   %.1f:1", ratioValue)
        kneeLabel.text = String.format(Locale.ENGLISH, "Soft knee   %.0f dB", kneeDb)
        attackLabel.text = String.format(Locale.ENGLISH, "Attack   %.0f ms", attackMs)
        releaseLabel.text = String.format(Locale.ENGLISH, "Release   %.0f ms", releaseMs)
        makeupLabel.text = String.format(Locale.ENGLISH, "Makeup gain   %.1f dB", makeupDb)
        bindingState = false
    }

    companion object {
        private const val PERSIST_THROTTLE_MS = 50L
    }
}
