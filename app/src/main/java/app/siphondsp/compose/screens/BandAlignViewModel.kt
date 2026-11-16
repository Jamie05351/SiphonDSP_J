package app.siphondsp.compose.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.bandalign.BandAlignSolver
import app.siphondsp.dsp.bandalign.RewMeasurement
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.MeasurementNeutral
import app.siphondsp.model.NativeBmwDspValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Band Align's imports, analysis and undo, kept across rotation (the imported measurements are
 * too large for saved instance state and are cheap to import again after process death).
 */
class BandAlignViewModel(application: Application) : AndroidViewModel(application) {

    enum class Seat(val label: String) { DRIVER("Driver seat"), PASSENGER("Passenger seat") }
    enum class Slot(val label: String, val hint: String) {
        LOW("Low only", "Mid and High muted"),
        MID("Mid only", "Low and High muted"),
        BOTH("Low + Mid", "optional -- High muted; checks the timing"),
    }

    data class Key(val seat: Seat, val side: BmwOutputChannel, val slot: Slot)
    data class Imported(val fileName: String, val measurement: RewMeasurement)

    val imports = mutableStateMapOf<Key, Imported>()
    val importErrors = mutableStateMapOf<Key, String>()

    var analysing by mutableStateOf(false)
        private set
    var results by mutableStateOf<List<BandAlignSolver.SideResult>?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** The settings Band Align's applies overwrote, as they were before the first; null when there's nothing to undo. */
    var undo by mutableStateOf<Map<Int, Float>?>(null)
        private set
    /** The results [apply] last wrote, so the same ones aren't written twice. */
    var appliedResults by mutableStateOf<List<BandAlignSolver.SideResult>?>(null)
        private set

    /** True right after an apply cleared the imports, until a new file is imported. */
    var measuredBeforeApply by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var analysedValues: FloatArray? = null
    /** The PEQ the current [results] were computed for: its Low and Mid filters shape what was measured. */
    private var analysedPeq: BmwPeqState? = null
    private var runId = 0
    /** What Band Align's applies last wrote, per index; Undo restores only sides that still hold it. */
    private var applied: Map<Int, Float> = emptyMap()
    private val slotGeneration = HashMap<Key, Int>()

    fun import(key: Key, uri: Uri) {
        val generation = (slotGeneration[key] ?: 0) + 1
        slotGeneration[key] = generation
        viewModelScope.launch {
            val app = getApplication<Application>()
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "measurement.txt"
                    val text = app.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readNBytesCompat(MAX_BYTES + 1)
                        require(bytes.size <= MAX_BYTES) { "$name is too large for a measurement export" }
                        String(bytes, Charsets.UTF_8)
                    } ?: throw IllegalStateException("couldn't open $name")
                    name to RewMeasurement.parse(name, text).getOrThrow()
                }
            }
            if (slotGeneration[key] != generation) return@launch
            outcome.onSuccess { (name, m) ->
                imports[key] = Imported(name, m)
                importErrors.remove(key)
                measuredBeforeApply = false
            }.onFailure {
                // The slot no longer holds what was picked last, so it holds nothing.
                imports.remove(key)
                importErrors[key] = it.message ?: "couldn't read that file"
            }
            invalidate()
        }
    }

    fun clear(key: Key) {
        slotGeneration[key] = (slotGeneration[key] ?: 0) + 1
        imports.remove(key)
        importErrors.remove(key)
        invalidate()
    }

    private fun invalidate() {
        job?.cancel()
        runId++
        analysing = false
        results = null
        failure = null
    }

    /** Per side, the seats with both a Low-only and a Mid-only measurement. */
    fun readySides(): Map<BmwOutputChannel, List<BandAlignSolver.SeatMeasurements>> =
        BmwOutputChannel.entries.associateWith { side ->
            Seat.entries.mapNotNull { seat ->
                val low = imports[Key(seat, side, Slot.LOW)]?.measurement ?: return@mapNotNull null
                val mid = imports[Key(seat, side, Slot.MID)]?.measurement ?: return@mapNotNull null
                BandAlignSolver.SeatMeasurements(seat.label, low, mid, imports[Key(seat, side, Slot.BOTH)]?.measurement)
            }
        }.filterValues { it.isNotEmpty() }

    fun analyse(values: FloatArray, peq: BmwPeqState) {
        val sides = readySides()
        if (sides.isEmpty()) return
        invalidate()
        failure = null
        notReadyReason(values)?.let {
            failure = it
            return
        }
        val smoothed = sides.values.flatten().flatMap { listOfNotNull(it.low, it.mid, it.both) }.filter { it.heavilySmoothed }
        if (smoothed.isNotEmpty()) {
            failure = "Re-export ${smoothed.joinToString { it.name }} at 1/12 octave or finer (1/48 is best) -- " +
                "coarser smoothing blurs the phase Band Align needs."
            return
        }
        val snapshot = values.copyOf()
        analysedValues = snapshot
        analysedPeq = peq
        analysing = true
        val run = runId
        job = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val solver = BandAlignSolver()
                    sides.map { (side, seats) ->
                        solver.solveSide(
                            side, seats,
                            BandAlignSolver.currentSetting(snapshot, side),
                            BandAlignSolver.scoreBand(snapshot, side),
                            isActive = { isActive },
                        )
                    }
                }
            }
            if (run != runId) return@launch
            analysing = false
            outcome.onSuccess { results = it }.onFailure { failure = it.message ?: "analysis failed" }
        }
    }

    /**
     * Why the tune as set now can't be analysed (or have a result applied), or null. A band left
     * muted from measuring would be solved against silence, and a band in passthrough skips its
     * delay and polarity altogether.
     */
    fun notReadyReason(values: FloatArray): String? {
        val muted = buildList {
            if (values[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE] >= .5f) add("Band isolation")
            for ((label, output) in listOf(
                "Low left" to NativeBmwDspValues.OUTPUT_LOW_LEFT, "Low right" to NativeBmwDspValues.OUTPUT_LOW_RIGHT,
                "Mid left" to NativeBmwDspValues.OUTPUT_MID_LEFT, "Mid right" to NativeBmwDspValues.OUTPUT_MID_RIGHT,
            )) if (values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_MUTE)] >= .5f) add(label)
        }
        if (muted.isNotEmpty()) return "Unmute ${muted.joinToString()} first -- Band Align solves for the tune as it plays."
        if (MeasurementNeutral.isOn(values)) {
            return "Switch Measurement neutral off first -- it bypasses the band delays and polarity being solved for."
        }
        if (values[NativeBmwDspValues.INDEX_CROSSOVER_BYPASS] >= .5f) {
            return "Switch the crossover back on -- with it off there are no bands to align."
        }
        // These feed one side's signal into the other side's bands, so a sweep on one channel
        // plays through both sides' Low (or Mid), and a "left Low" measurement isn't the left
        // Low alone -- shifting all of it by the left delay would predict a sum that never plays.
        val mixing = buildList {
            if (values[NativeBmwDspValues.INDEX_MONO_BASS_ENABLED] >= .5f) add("Mono Bass")
            if (values[NativeBmwDspValues.INDEX_VIRTUAL_ENABLED] >= .5f) add("Virtual Centre")
            if (values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] >= .5f) add("Virtual Bass")
        }
        if (mixing.isNotEmpty()) {
            return "Switch ${mixing.joinToString(" and ")} off while measuring and analysing -- they mix the two " +
                "sides, so a one-side measurement isn't one side. Switch them back on after applying."
        }
        return null
    }

    /** Writes every side's proposal through [commit]; remembers what it replaced for [undoApply]. */
    fun apply(values: FloatArray, peq: BmwPeqState, commit: (Map<Int, Float>) -> Boolean): Boolean {
        val current = results ?: return false
        val updates = LinkedHashMap<Int, Float>().apply { current.forEach { putAll(it.updates) } }
        if (updates.isEmpty()) return false
        if (current === appliedResults) {
            failure = "Already applied."
            return false
        }
        if (analysedValues?.contentEquals(values) != true || analysedPeq != peq) {
            failure = "Your settings changed since the analysis -- tap Analyse again before applying."
            results = null
            return false
        }
        // Undo goes back to before Band Align: an index still holding what the last apply wrote
        // keeps its older snapshot; one edited by hand since gets that hand edit as its snapshot,
        // so a second apply's Undo returns to it rather than past it.
        val previous = LinkedHashMap(undo.orEmpty())
        for (index in updates.keys) {
            val stillApplied = applied[index]?.let { abs(values[index] - it) < UNDO_TOLERANCE } == true
            if (!(stillApplied && previous.containsKey(index))) previous[index] = values[index]
        }
        if (!commit(updates)) {
            failure = "Couldn't save the change."
            return false
        }
        failure = null
        undo = previous
        applied = LinkedHashMap(applied).apply { putAll(updates) }
        appliedResults = current
        // The imported files were measured with the settings just replaced. Analysed again they
        // would be read as measured with the new ones and the same correction proposed twice, so
        // they're dropped: the next analysis needs a fresh measurement of the new tune.
        for (key in imports.keys.toList()) clear(key)
        measuredBeforeApply = true
        return true
    }

    /**
     * Puts back what Band Align replaced, side by side, but only where both of that side's
     * settings (Low delay and polarity) still hold what Band Align wrote: a side edited by hand
     * since is left as the user set it. Returns what to tell the user, or null if it couldn't save.
     */
    fun undoApply(values: FloatArray, commit: (Map<Int, Float>) -> Boolean): String? {
        val previous = undo ?: return null
        val bySide = previous.keys.groupBy { index ->
            BmwOutputChannel.entries.first { side ->
                index == BandAlignSolver.delayIndex(side) || index == BandAlignSolver.invertIndex(side)
            }
        }
        val untouched = bySide.filterValues { indices ->
            indices.all { i -> applied[i]?.let { a -> values.getOrNull(i)?.let { abs(it - a) < UNDO_TOLERANCE } } == true }
        }
        val restore = previous.filterKeys { k -> untouched.values.any { k in it } }
        val kept = bySide.size - untouched.size
        if (restore.isNotEmpty() && !commit(restore)) {
            failure = "Couldn't restore the previous Low delay and polarity."
            return null
        }
        undo = null
        applied = emptyMap()
        appliedResults = null
        return when {
            kept == 0 -> "Restored the Low delay and polarity from before Band Align"
            restore.isEmpty() -> "Nothing restored: you've changed those settings since Band Align"
            else -> "Restored the side you haven't changed since; left the other as you set it"
        }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        /** Half the delay step: a value within this still holds what Band Align wrote. */
        const val UNDO_TOLERANCE = 0.005f
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
