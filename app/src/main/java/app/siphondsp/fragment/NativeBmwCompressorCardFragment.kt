package app.siphondsp.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import app.siphondsp.R
import app.siphondsp.activity.NativeBmwCompressorActivity
import app.siphondsp.model.NativeBmwCompressorState
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale

class NativeBmwCompressorCardFragment : Fragment() {
    private lateinit var enabledSwitch: SwitchCompat
    private lateinit var summary: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var bindingState = false
    private val meterTick = object : Runnable {
        override fun run() {
            RootlessAudioProcessorService.nativeBmwCompressorMeter()?.takeIf { it.size >= 3 }?.let {
                updateSummary(NativeBmwCompressorState.load(requireContext()), it[2])
            }
            handler.postDelayed(this, 80L)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = bindState()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_native_bmw_compressor_card, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        enabledSwitch = view.findViewById(R.id.compressor_card_switch)
        summary = view.findViewById(R.id.compressor_card_summary)
        view.findViewById<View>(R.id.compressor_card_header).setOnClickListener {
            startActivity(Intent(requireContext(), NativeBmwCompressorActivity::class.java))
        }
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingState) NativeBmwCompressorState.load(requireContext())
                .copy(enabled = checked).persistAndApply(requireContext())
        }
        bindState()
    }

    override fun onStart() {
        super.onStart()
        requireContext().registerLocalReceiver(
            receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED)
        )
        bindState()
        handler.post(meterTick)
    }

    override fun onStop() {
        handler.removeCallbacks(meterTick)
        requireContext().unregisterLocalReceiver(receiver)
        super.onStop()
    }

    private fun bindState() {
        if (!::enabledSwitch.isInitialized) return
        val state = NativeBmwCompressorState.load(requireContext())
        bindingState = true
        enabledSwitch.isChecked = state.enabled
        updateSummary(
            state,
            RootlessAudioProcessorService.nativeBmwCompressorMeter()?.getOrNull(2) ?: 0f,
        )
        bindingState = false
    }

    private fun updateSummary(state: NativeBmwCompressorState, gainReductionDb: Float) {
        summary.text = String.format(
            Locale.ENGLISH,
            "%.1f dB · %.1f:1 · %.0f/%.0f ms · GR %.1f dB",
            state.thresholdDb,
            state.ratio,
            state.attackMs,
            state.releaseMs,
            gainReductionDb,
        )
    }

    companion object {
        fun newInstance() = NativeBmwCompressorCardFragment()
    }
}
