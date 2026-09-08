package app.siphondsp.compose.state

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.siphondsp.fragment.PeqBandEditResult
import app.siphondsp.fragment.PeqBandEditor
import app.siphondsp.fragment.PeqScope
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.service.RootlessAudioProcessorService
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import app.siphondsp.view.BmwDashboardSkin
import timber.log.Timber
import java.util.UUID

// Lowest sample rate RootlessAudioProcessorService ever opens the recorder at -- validate
// against it when the service isn't running so a near-Nyquist band can't pass here and then
// silently fail once the service starts lower.
private const val MIN_ASSUMED_SAMPLE_RATE = 44_100f

/**
 * Compose state layer for the three-bank Parametric EQ (roadmap Phase 10a). Ports
 * `ParametricEqualizerFragment`'s state + the `applyCandidate` mutation funnel, minus the undo
 * history (the Compose PEQ screen drops Undo/Redo -- see `docs/PEQ_COMPOSE_PLAN.md`). The visible
 * band list is derived from `peqState` + `selectedScope`, not explicitly rebound.
 */
class PeqStateHolder internal constructor(private val appContext: Context) {

    /** Always reassigned wholesale (never mutated in place) so composition tracks it. */
    var peqState: BmwPeqState by mutableStateOf(BmwPeqState.load(appContext))
        private set

    var selectedScope: PeqScope by mutableStateOf(PeqScope.FULL)

    private val selectedByScope = mutableStateMapOf<PeqScope, UUID?>()

    var selectedUuid: UUID?
        get() = selectedByScope[selectedScope]
        set(value) { selectedByScope[selectedScope] = value }

    /** Bands for the current scope -- a live view into [peqState], recomputed on read. */
    val visibleBands: ParametricEqBandList
        get() = PeqBandEditor.bandsFor(peqState, selectedScope)

    /** Per-scope cell accent (app-wide Pre EQ=white / Low=blue / Mid=yellow). */
    val scopeAccentArgb: Int
        get() = when (selectedScope) {
            PeqScope.FULL -> android.graphics.Color.WHITE
            PeqScope.LOW -> BmwDashboardSkin.LIGHT_BLUE
            PeqScope.MID -> BmwDashboardSkin.MID_BAND_YELLOW
        }

    /**
     * The single mutation funnel -- ported from `ParametricEqualizerFragment.applyCandidate`
     * (minus undo history). Coerces `enabled = true`, validates at the live (or lowest assumed)
     * sample rate, then pushes to the running engine or persists to disk; toasts + returns false
     * on any rejection, leaving the previous state active.
     */
    fun applyCandidate(rawCandidate: BmwPeqState, source: String): Boolean {
        val candidate = rawCandidate.copy(enabled = true)
        val sampleRate = RootlessAudioProcessorService.nativeBmwPeqSampleRate() ?: MIN_ASSUMED_SAMPLE_RATE
        candidate.validate(sampleRate)?.let { validation ->
            Timber.e("$source ${selectedScope.label} validation failed: $validation")
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
            "$source scope=${selectedScope.label} full=${candidate.fullRangeBands.size} " +
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
        peqState = candidate
        appContext.sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        return true
    }

    /** Reload from disk (resume / after an external edit the receiver reports). */
    fun refreshFromDisk() {
        peqState = BmwPeqState.load(appContext)
    }

    /** Adopt a state produced elsewhere (import / backup restore) without re-validating here --
     *  the caller is expected to have run [applyCandidate] already; this just syncs the snapshot. */
    fun setSnapshot(state: BmwPeqState) {
        peqState = state.copy(enabled = true)
    }

    // --- convenience edits, all funnelling through applyCandidate ---

    fun addBand() {
        val candidate = peqState.deepCopy()
        val band = ParametricEqBand(
            1000.0, 0.0, 1.41, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT,
        )
        PeqBandEditor.bandsFor(candidate, selectedScope).add(band)
        if (applyCandidate(candidate, "add")) selectedUuid = band.uuid
    }

    /** Clear the current scope (Full also resets preamp to 0 dB). */
    fun resetScope() {
        val candidate = peqState.deepCopy()
        val cleared = ParametricEqBandList()
        if (selectedScope == PeqScope.FULL) cleared.deserialize(Constants.DEFAULT_PEQ)
        PeqBandEditor.replaceScopeBands(candidate, selectedScope, cleared)
        applyCandidate(
            if (selectedScope == PeqScope.FULL) candidate.copy(preampDb = 0f) else candidate,
            "reset",
        )
    }

    /** Instant-commit a single-band edit in the active scope -- deep-copy, swap band [index] for
     *  [transform]'s result, select it, funnel through [applyCandidate]. */
    fun commitBandEdit(index: Int, source: String, transform: (ParametricEqBand) -> ParametricEqBand) {
        val candidate = peqState.deepCopy()
        val list = PeqBandEditor.bandsFor(candidate, selectedScope)
        if (index !in list.indices) return
        val updated = transform(list[index])
        list[index] = updated
        selectedUuid = updated.uuid
        applyCandidate(candidate, source)
    }

    fun setPreamp(preampDb: Float) {
        applyCandidate(peqState.deepCopy().copy(preampDb = preampDb), "preamp")
    }

    fun deleteBand(index: Int) {
        val candidate = peqState.deepCopy()
        val list = PeqBandEditor.bandsFor(candidate, selectedScope)
        if (index !in list.indices) return
        list.removeAt(index)
        applyCandidate(candidate, "delete")
    }

    /**
     * Apply a [PeqBandEditor] result (the filter-tools ops -- duplicate / move / copy / split).
     * `Changed` → [applyCandidate] + select; `Overflow` → toast; `Ignored` → nothing;
     * `NoMatchingChannel` → the caller shows its own "No <label> filters" message.
     */
    fun apply(result: PeqBandEditResult): PeqBandEditResult {
        when (result) {
            is PeqBandEditResult.Changed ->
                if (applyCandidate(result.candidate, result.undoSource)) {
                    result.select?.let { selectedUuid = it }
                }
            is PeqBandEditResult.Overflow -> toast(PeqBandEditor.overflowToast(result))
            PeqBandEditResult.Ignored, PeqBandEditResult.NoMatchingChannel -> Unit
        }
        return result
    }

    private fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun rememberPeqState(): PeqStateHolder {
    val context = LocalContext.current
    return remember(context) { PeqStateHolder(context.applicationContext) }
}
