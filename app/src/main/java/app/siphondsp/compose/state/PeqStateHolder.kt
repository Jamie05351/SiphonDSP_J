package app.siphondsp.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.siphondsp.fragment.PeqBandEditResult
import app.siphondsp.fragment.PeqBandEditor
import app.siphondsp.fragment.PeqScope
import app.siphondsp.model.BmwPeqRepository
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.utils.Constants
import app.siphondsp.view.BmwDashboardSkin
import org.koin.compose.koinInject
import java.util.UUID

/**
 * Screen-scoped facade over [BmwPeqRepository]: which scope/band is currently selected is
 * UI-local and stays here (recreating this per PEQ edit would lose the user's selection), while
 * the actual PEQ data ([peqState]) is a mirror of the shared repository, kept in sync by
 * [rememberPeqState]'s collector below as well as by this class's own writes.
 */
class PeqStateHolder internal constructor(
    private val repo: BmwPeqRepository,
) {
    /** Always reassigned wholesale (never mutated in place) so composition tracks it. */
    var peqState: BmwPeqState by mutableStateOf(repo.peq.value)
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

    /** Delegates to [BmwPeqRepository.applyCandidate]; adopts the result on success so this
     *  holder's own view updates immediately rather than waiting for the next collected emission. */
    fun applyCandidate(rawCandidate: BmwPeqState, source: String): Boolean {
        val ok = repo.applyCandidate(rawCandidate, source, selectedScope.label)
        if (ok) peqState = repo.peq.value
        return ok
    }

    /** Reload the active session, or disk when offline (resume / external update). */
    fun refreshFromDisk() {
        repo.refreshFromDisk()
        peqState = repo.peq.value
    }

    /** Adopt a state produced elsewhere (import / backup restore) without re-validating here --
     *  the caller is expected to have run [applyCandidate] already; this just syncs the snapshot. */
    fun setSnapshot(state: BmwPeqState) {
        repo.setSnapshot(state)
        peqState = repo.peq.value
    }

    /** Called by [rememberPeqState]'s collector when the shared repository changes for a reason
     *  other than this holder's own writes above (e.g. a broadcast-triggered refresh). */
    internal fun syncFromRepo(state: BmwPeqState) {
        peqState = state
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
            is PeqBandEditResult.Overflow -> repo.toast(PeqBandEditor.overflowToast(result))
            PeqBandEditResult.Ignored, PeqBandEditResult.NoMatchingChannel -> Unit
        }
        return result
    }
}

@Composable
fun rememberPeqState(): PeqStateHolder {
    val repo = koinInject<BmwPeqRepository>()
    val holder = remember(repo) { PeqStateHolder(repo) }
    val peq by repo.peq.collectAsStateWithLifecycle()
    LaunchedEffect(peq) { holder.syncFromRepo(peq) }
    return holder
}
