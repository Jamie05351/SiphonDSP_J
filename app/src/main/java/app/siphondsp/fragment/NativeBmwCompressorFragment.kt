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
import com.google.android.material.slider.Slider
import java.util.Locale

class NativeBmwCompressorFragment : Fragment() {
    private lateinit var state: NativeBmwCompressorState
    private lateinit var visualizer: NativeBmwCompressorView
    private lateinit var grTrace: CompressorGrTraceView
    private lateinit var meterText: TextView
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
            if (meter != null && meter.size >= 3) {
                visualizer.setMeter(meter[0], meter[1], meter[2])
                grTrace.pushFrame(meter[0], meter[1], meter[2])
                meterText.text = String.format(
                    Locale.ENGLISH,
                    "Input %.1f dBFS   Output %.1f dBFS   Gain reduction %.1f dB",
                    meter[0], meter[1], meter[2],
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
        enabledSwitch = view.findViewById(R.id.compressor_enable)
        threshold = view.findViewById(R.id.compressor_threshold)
        ratio = view.findViewById(R.id.compressor_ratio)
        knee = view.findViewById(R.id.compressor_knee)
        attack = view.findViewById(R.id.compressor_attack)
        release = view.findViewById(R.id.compressor_release)
        makeup = view.findViewById(R.id.compressor_makeup)
        configureSlider(threshold, -18f, 0f, 0.5f)
        configureSlider(ratio, 1f, 10f, 0.1f)
        configureSlider(knee, 0f, 12f, 1f)
        configureSlider(attack, 1f, 50f, 1f)
        configureSlider(release, 20f, 400f, 1f)
        configureSlider(makeup, 0f, 6f, 0.1f)
        thresholdLabel = view.findViewById(R.id.compressor_threshold_label)
        ratioLabel = view.findViewById(R.id.compressor_ratio_label)
        kneeLabel = view.findViewById(R.id.compressor_knee_label)
        attackLabel = view.findViewById(R.id.compressor_attack_label)
        releaseLabel = view.findViewById(R.id.compressor_release_label)
        makeupLabel = view.findViewById(R.id.compressor_makeup_label)
        bindState(NativeBmwCompressorState.load(requireContext()))
        configureListeners()
    }

    private fun configureSlider(slider: Slider, from: Float, to: Float, step: Float) {
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = step
    }

    override fun onStart() {
        super.onStart()
        bindState(NativeBmwCompressorState.load(requireContext()))
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
        enabledSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!bindingState) commit(state.copy(enabled = enabled))
        }
        threshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingState) commit(state.copy(thresholdDb = value))
        }
        ratio.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingState) commit(state.copy(ratio = value))
        }
        knee.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingState) commit(state.copy(kneeDb = value))
        }
        attack.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingState) commit(state.copy(attackMs = value))
        }
        release.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingState) commit(state.copy(releaseMs = value))
        }
        makeup.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !bindingState) commit(state.copy(makeupDb = value))
        }
        visualizer.onThresholdChanged = { commit(state.copy(thresholdDb = it)) }
        visualizer.onRatioChanged = { commit(state.copy(ratio = it)) }
        visualizer.onKneeChanged = { commit(state.copy(kneeDb = it)) }
    }

    /**
     * Visual feedback (sliders, transfer curve) updates every call; persistence -- SharedPreferences
     * writes, a broadcast, and a native reconfigure -- is throttled to PERSIST_THROTTLE_MS so a
     * continuous graph drag doesn't fire that work at touch-event rate. A trailing postDelayed
     * flush guarantees the final dragged value is never lost, and onStop() flushes immediately if
     * the fragment goes away before that timer fires.
     */
    private fun commit(next: NativeBmwCompressorState) {
        state = next
        bindState(state)
        pendingPersist = true
        handler.removeCallbacks(flushPersist)
        if (SystemClock.uptimeMillis() - lastPersistMs >= PERSIST_THROTTLE_MS) {
            persistNow()
        } else {
            handler.postDelayed(flushPersist, PERSIST_THROTTLE_MS)
        }
    }

    private fun persistNow() {
        lastPersistMs = SystemClock.uptimeMillis()
        pendingPersist = false
        state.persistAndApply(requireContext())
    }

    private fun bindState(next: NativeBmwCompressorState) {
        if (!::visualizer.isInitialized) return
        state = next
        bindingState = true
        enabledSwitch.isChecked = state.enabled
        threshold.value = state.thresholdDb.coerceIn(threshold.valueFrom, threshold.valueTo)
        ratio.value = state.ratio.coerceIn(ratio.valueFrom, ratio.valueTo)
        knee.value = state.kneeDb.coerceIn(knee.valueFrom, knee.valueTo)
        attack.value = state.attackMs.coerceIn(attack.valueFrom, attack.valueTo)
        release.value = state.releaseMs.coerceIn(release.valueFrom, release.valueTo)
        makeup.value = state.makeupDb.coerceIn(makeup.valueFrom, makeup.valueTo)
        visualizer.compressorEnabled = state.enabled
        visualizer.isEnabled = state.enabled
        visualizer.thresholdDb = state.thresholdDb
        visualizer.ratio = state.ratio
        visualizer.kneeDb = state.kneeDb
        visualizer.makeupDb = state.makeupDb
        grTrace.thresholdDb = state.thresholdDb
        thresholdLabel.text = String.format(Locale.ENGLISH, "Threshold   %.1f dB", state.thresholdDb)
        ratioLabel.text = String.format(Locale.ENGLISH, "Ratio   %.1f:1", state.ratio)
        kneeLabel.text = String.format(Locale.ENGLISH, "Soft knee   %.0f dB", state.kneeDb)
        attackLabel.text = String.format(Locale.ENGLISH, "Attack   %.0f ms", state.attackMs)
        releaseLabel.text = String.format(Locale.ENGLISH, "Release   %.0f ms", state.releaseMs)
        makeupLabel.text = String.format(Locale.ENGLISH, "Makeup gain   %.1f dB", state.makeupDb)
        bindingState = false
    }

    companion object {
        private const val PERSIST_THROTTLE_MS = 50L
    }
}
