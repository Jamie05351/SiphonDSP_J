package app.siphondsp.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

// Lowest sample rate RootlessAudioProcessorService ever opens the recorder at -- validate
// against it when the service isn't running so a near-Nyquist band can't pass here and then
// silently fail once the service starts lower.
private const val MIN_ASSUMED_SAMPLE_RATE = 44_100f

/**
 * Single process-wide source of truth for the three-bank Parametric EQ state (`BmwPeqState`).
 * Replaces the write/validate/apply logic that used to live directly on `PeqStateHolder` (now a
 * thin per-screen facade over this) so every reader -- the PEQ editor, the Crossovers & Tilt
 * response graph, anything added later -- observes the same, always-current state instead of each
 * keeping its own snapshot.
 *
 * Registered as a Koin singleton (see `MainApplication.kt`).
 */
class BmwPeqRepository(private val appContext: Context) {
    private val _peq = MutableStateFlow(BmwPeqState.load(appContext))
    val peq: StateFlow<BmwPeqState> = _peq.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshFromDisk()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_PARAMETRIC_EQ_CHANGED)
            addAction(Constants.ACTION_PRESET_LOADED)
            addAction(Constants.ACTION_BACKUP_RESTORED)
        }
        appContext.registerLocalReceiver(receiver, filter)
    }

    fun refreshFromDisk() {
        _peq.value = BmwPeqState.load(appContext)
    }

    /** Adopt a state produced elsewhere (import / backup restore) without re-validating here --
     *  the caller is expected to have run [applyCandidate] already; this just syncs the snapshot. */
    fun setSnapshot(state: BmwPeqState) {
        _peq.value = state.copy(enabled = true)
    }

    /**
     * The single mutation funnel -- ported from the old `PeqStateHolder.applyCandidate`
     * (originally `ParametricEqualizerFragment.applyCandidate`, minus undo history). Coerces
     * `enabled = true`, validates at the live (or lowest assumed) sample rate, then pushes to the
     * running engine or persists to disk; toasts + returns false on any rejection, leaving the
     * previous state active.
     */
    fun applyCandidate(rawCandidate: BmwPeqState, source: String, scopeLabel: String): Boolean {
        val candidate = rawCandidate.copy(enabled = true)
        val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: MIN_ASSUMED_SAMPLE_RATE
        candidate.validate(sampleRate)?.let { validation ->
            Timber.e("$source $scopeLabel validation failed: $validation")
            toast("$validation; previous PEQ remains active")
            return false
        }
        // Boolean?: null = service not running (persist); false = running but handle not ready
        // (must still persist, not route into applyNativeBmwPeq which would drop the edit).
        val serviceAvailable = RootlessAudioProcessorService.nativeBmwPeqHandleReady() == true
        val result = if (serviceAvailable) {
            RootlessAudioProcessorService.applyNativeBmwPeq(candidate)
        } else {
            candidate.persist(appContext)
        }
        Timber.d(
            "$source scope=$scopeLabel full=${candidate.fullRangeBands.size} " +
                "low=${candidate.lowBandBands.size} mid=${candidate.midBandBands.size} " +
                "serviceAvailable=$serviceAvailable result=$result",
        )
        if (!result) {
            toast(
                if (serviceAvailable) {
                    "BMW PEQ configuration rejected; previous state remains active"
                } else {
                    "BMW PEQ could not be saved; previous state remains active"
                },
            )
            return false
        }
        _peq.value = candidate
        appContext.sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        return true
    }

    /** Exposed for callers (e.g. [app.siphondsp.compose.state.PeqStateHolder]) that need to
     *  surface their own rejection message using the same short-toast convention as this class. */
    fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }
}
