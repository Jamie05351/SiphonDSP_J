package app.siphondsp.fragment

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import java.util.UUID

/** Outcome of a [PeqBandEditor] operation. The fragment turns each case into a toast and/or an
 *  `applyCandidate(...)` call, keeping every op's existing before/after-commit selection ordering. */
sealed interface PeqBandEditResult {
    /** Hand [candidate] to `applyCandidate([undoSource])`. [select], when non-null, is the band
     *  the caller should mark selected (same value the fragment used to set inline). */
    data class Changed(
        val candidate: BmwPeqState,
        val undoSource: String,
        val select: UUID? = null,
    ) : PeqBandEditResult

    /** A capacity check failed. Render via [PeqBandEditor.overflowToast]. */
    data class Overflow(val scope: PeqScope, val alreadyFull: Boolean) : PeqBandEditResult

    /** Stale / out-of-range index on a move: do nothing, silently (the old `return true`). */
    data object Ignored : PeqBandEditResult

    /** No band carried the requested channel; caller shows the "No <label> filters" toast. */
    data object NoMatchingChannel : PeqBandEditResult
}

/**
 * Pure band-list surgery for the Parametric EQ "filter tools" menu -- duplicate / move / copy a
 * filter, copy a whole bank, copy or split by channel. Every function deep-copies the supplied
 * [BmwPeqState], mutates the copy, and reports what the caller should do; nothing here touches
 * Android, persistence, undo history or selection state.
 *
 * Extracted verbatim from [ParametricEqualizerFragment]; bodies are unchanged bar the two
 * capacity messages now living in [overflowToast] and [copyChannelFilters]'s empty-source check
 * moving ahead of a mutation loop it made no difference to (the candidate is discarded either way).
 */
object PeqBandEditor {

    fun bandsFor(state: BmwPeqState, scope: PeqScope): ParametricEqBandList = when (scope) {
        PeqScope.FULL -> state.fullRangeBands
        PeqScope.LOW -> state.lowBandBands
        PeqScope.MID -> state.midBandBands
    }

    fun replaceScopeBands(state: BmwPeqState, scope: PeqScope, replacement: ParametricEqBandList) {
        bandsFor(state, scope).apply {
            clear()
            addAll(replacement)
        }
    }

    fun overflowToast(result: PeqBandEditResult.Overflow): String =
        if (result.alreadyFull) {
            "${result.scope.label} already has the maximum ${BmwPeqState.MAX_BANDS} filters"
        } else {
            "${result.scope.label} would exceed the maximum ${BmwPeqState.MAX_BANDS} filters"
        }

    fun copyWholeScope(
        state: BmwPeqState,
        from: PeqScope,
        to: PeqScope,
        append: Boolean,
    ): PeqBandEditResult {
        val candidate = state.deepCopy()
        val source = bandsFor(candidate, from).toList()
        val destination = bandsFor(candidate, to)
        val resultingSize = (if (append) destination.size else 0) + source.size
        if (resultingSize > BmwPeqState.MAX_BANDS) {
            return PeqBandEditResult.Overflow(to, alreadyFull = false)
        }
        if (!append) destination.clear()
        source.forEach { destination.add(it.copyWithUuid(UUID.randomUUID())) }
        return PeqBandEditResult.Changed(
            candidate,
            if (append) "copy-scope-append" else "copy-scope-replace",
        )
    }

    fun duplicateFilter(state: BmwPeqState, scope: PeqScope, index: Int): PeqBandEditResult {
        val candidate = state.deepCopy()
        val bands = bandsFor(candidate, scope)
        if (bands.size >= BmwPeqState.MAX_BANDS) {
            return PeqBandEditResult.Overflow(scope, alreadyFull = true)
        }
        val duplicate = bands[index].copyWithUuid(UUID.randomUUID())
        bands.add(index + 1, duplicate)
        return PeqBandEditResult.Changed(candidate, "duplicate", select = duplicate.uuid)
    }

    fun copyFilter(
        state: BmwPeqState,
        from: PeqScope,
        index: Int,
        to: PeqScope,
    ): PeqBandEditResult {
        val candidate = state.deepCopy()
        val destination = bandsFor(candidate, to)
        if (destination.size >= BmwPeqState.MAX_BANDS) {
            return PeqBandEditResult.Overflow(to, alreadyFull = true)
        }
        val copied = bandsFor(candidate, from)[index].copyWithUuid(UUID.randomUUID())
        destination.add(copied)
        return PeqBandEditResult.Changed(candidate, "copy-filter", select = copied.uuid)
    }

    fun moveFilter(state: BmwPeqState, scope: PeqScope, from: Int, to: Int): PeqBandEditResult {
        val candidate = state.deepCopy()
        val bands = bandsFor(candidate, scope)
        if (from !in bands.indices || to !in bands.indices) return PeqBandEditResult.Ignored
        val band = bands.removeAt(from)
        bands.add(to, band)
        return PeqBandEditResult.Changed(candidate, "reorder", select = band.uuid)
    }

    fun copyChannelFilters(
        state: BmwPeqState,
        scope: PeqScope,
        source: ParametricEqChannel,
        destination: ParametricEqChannel,
        replaceBoth: Boolean,
    ): PeqBandEditResult {
        val candidate = state.deepCopy()
        val bands = bandsFor(candidate, scope)
        val matching = bands.filter { it.channel == source }
        if (bands.size + matching.size > BmwPeqState.MAX_BANDS) {
            return PeqBandEditResult.Overflow(scope, alreadyFull = false)
        }
        if (matching.isEmpty()) return PeqBandEditResult.NoMatchingChannel
        if (replaceBoth) {
            val sourceIds = matching.mapTo(mutableSetOf()) { it.uuid }
            bands.removeAll { it.uuid in sourceIds }
        }
        matching.forEach { band ->
            bands.add(
                ParametricEqBand(
                    band.frequency,
                    band.gain,
                    band.q,
                    band.filterType,
                    destination,
                    UUID.randomUUID(),
                )
            )
            if (replaceBoth) {
                bands.add(
                    ParametricEqBand(
                        band.frequency,
                        band.gain,
                        band.q,
                        band.filterType,
                        ParametricEqChannel.RIGHT,
                        UUID.randomUUID(),
                    )
                )
            }
        }
        return PeqBandEditResult.Changed(candidate, "copy-channel")
    }

    private fun ParametricEqBand.copyWithUuid(uuid: UUID) = ParametricEqBand(
        frequency, gain, q, filterType, channel, uuid,
    )
}
