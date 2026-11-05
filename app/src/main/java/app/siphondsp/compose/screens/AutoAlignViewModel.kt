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
import app.siphondsp.dsp.autoalign.AutoAlignSolver
import app.siphondsp.dsp.autoalign.RewMeasurement
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Auto Align's imports, analysis and undo, kept across rotation (the imported measurements are
 * too large for saved instance state and are cheap to import again after process death).
 */
class AutoAlignViewModel(application: Application) : AndroidViewModel(application) {

    enum class Seat(val label: String) { DRIVER("Driver seat"), PASSENGER("Passenger seat") }
    enum class Slot(val label: String, val hint: String) {
        LEFT("Left only", "REW \"L\""),
        RIGHT("Right only", "REW \"R\""),
        BOTH("Both together", "REW \"L+R\" -- lines the two up in time"),
    }

    data class Imported(val fileName: String, val measurement: RewMeasurement)

    /** (seat, slot) -> the imported measurement. */
    val imports = mutableStateMapOf<Pair<Seat, Slot>, Imported>()
    /** (seat, slot) -> why the last import of it was rejected. */
    val importErrors = mutableStateMapOf<Pair<Seat, Slot>, String>()

    private var measuredWithState by mutableStateOf(AutoAlignSolver.MeasuredWith.DSP_OFF)
    /** Changing it invalidates any result: the two modes map the speakers differently. */
    var measuredWith: AutoAlignSolver.MeasuredWith
        get() = measuredWithState
        set(value) {
            if (value == measuredWithState) return
            measuredWithState = value
            invalidate()
        }
    var progress by mutableStateOf<Float?>(null)
        private set
    var result by mutableStateOf<AutoAlignSolver.Result?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** The settings Auto Align's applies overwrote, as they were before the first; null when there's nothing to undo. */
    var undo by mutableStateOf<Map<Int, Float>?>(null)
        private set

    private var job: Job? = null
    /** The DSP values the current [result] was computed for; Apply refuses if they've changed. */
    private var analysedValues: FloatArray? = null
    /** The PEQ the current [result] was computed for (it's part of the modelled chain too). */
    private var analysedPeq: BmwPeqState? = null
    /** Bumped per analysis, so a superseded run's progress and result are dropped. */
    private var runId = 0
    /** The result [apply] last wrote, so the same one isn't written twice. */
    var appliedResult by mutableStateOf<AutoAlignSolver.Result?>(null)
        private set

    /** Bumped per pick/clear of a slot, so a slow earlier read can't land over a newer choice. */
    private val slotGeneration = HashMap<Pair<Seat, Slot>, Int>()

    fun import(seat: Seat, slot: Slot, uri: Uri) {
        val key = seat to slot
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
                imports[seat to slot] = Imported(name, m)
                importErrors.remove(seat to slot)
                invalidate()
            }.onFailure {
                // The slot no longer holds what the user picked last, so it holds nothing: an
                // old file kept silently behind the error would still feed Analyse.
                imports.remove(seat to slot)
                importErrors[seat to slot] = it.message ?: "couldn't read that file"
                invalidate()
            }
        }
    }

    fun clear(seat: Seat, slot: Slot) {
        slotGeneration[seat to slot] = (slotGeneration[seat to slot] ?: 0) + 1
        imports.remove(seat to slot)
        importErrors.remove(seat to slot)
        invalidate()
    }

    /**
     * The inputs changed (a file, or how they were measured): any result, or analysis in flight,
     * describes the old ones, so drop it -- the bumped run id makes a still-finishing run's
     * progress and result land nowhere.
     */
    private fun invalidate() {
        job?.cancel()
        runId++
        progress = null
        result = null
        // [undo] survives: it's the way back to the settings from before Auto Align, and a new
        // import or analysis doesn't change what those were.
    }

    /** Seats with both a left and a right measurement. */
    fun readySeats(): List<AutoAlignSolver.Seat> = Seat.entries.mapNotNull { seat ->
        val l = imports[seat to Slot.LEFT]?.measurement ?: return@mapNotNull null
        val r = imports[seat to Slot.RIGHT]?.measurement ?: return@mapNotNull null
        AutoAlignSolver.Seat(seat.label, l, r, imports[seat to Slot.BOTH]?.measurement)
    }

    fun analyse(values: FloatArray, peq: BmwPeqState) {
        val seats = readySeats()
        if (seats.isEmpty()) return
        invalidate()
        failure = null
        if (measuredWith == AutoAlignSolver.MeasuredWith.DSP_ON && crossfeeds(values)) {
            // Mono Bass (and Virtual Bass's mono harmonics) put each side's bass in both
            // channels, so a "left only" measurement taken through them already holds the right
            // speakers too, and there's no telling the two apart.
            failure = "With SiphonDSP on, measure with Mono Bass, Virtual Bass and Virtual Centre switched off -- " +
                "they mix the two sides, so a one-side measurement isn't one side. Or measure with SiphonDSP off."
            return
        }
        val smoothed = seats.flatMap { listOfNotNull(it.left, it.right, it.both) }.filter { it.heavilySmoothed }
        if (smoothed.isNotEmpty()) {
            // Smoothing that coarse blurs the phase the solver sums by (left + right stop
            // reproducing the both-together measurement), so its answer wouldn't be one to apply.
            failure = "Re-export ${smoothed.joinToString { it.name }} at 1/12 octave or finer (1/48 is best) -- " +
                "coarser smoothing blurs the phase Auto Align needs."
            return
        }
        progress = 0f
        val snapshot = values.copyOf()
        analysedValues = snapshot
        analysedPeq = peq
        val run = runId
        job = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    AutoAlignSolver().solve(
                        seats, measuredWith, snapshot, peq,
                        isActive = { isActive },
                        onProgress = { p -> launch(Dispatchers.Main) { if (run == runId && progress != null) progress = p } },
                    )
                }
            }
            if (run != runId) return@launch
            progress = null
            outcome.onSuccess { result = it }.onFailure { failure = it.message ?: "analysis failed" }
        }
    }

    /** Stages that put one side's signal into the other (or behave differently for one side than for both). */
    private fun crossfeeds(values: FloatArray) =
        values.getOrElse(NativeBmwDspValues.INDEX_MONO_BASS_ENABLED) { 0f } >= .5f ||
            values.getOrElse(NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED) { 0f } >= .5f ||
            values.getOrElse(NativeBmwDspValues.INDEX_VIRTUAL_ENABLED) { 0f } >= .5f

    /** Writes the proposal through [commit]; remembers what it replaced for [undoApply]. */
    fun apply(values: FloatArray, peq: BmwPeqState, commit: (Map<Int, Float>) -> Boolean): Boolean {
        val current = result ?: return false
        val updates = current.updates.takeIf { it.isNotEmpty() } ?: return false
        if (current === appliedResult) {
            failure = "Already applied."
            return false
        }
        if (analysedValues?.contentEquals(values) != true || analysedPeq != peq) {
            // Settings moved since Analyse (here or on another screen): the proposal was solved
            // for the old chain, so don't write it -- ask for a fresh analysis instead.
            failure = "Your settings changed since the analysis -- tap Analyse again before applying."
            result = null
            return false
        }
        // Undo goes back to before Auto Align: keep what an earlier apply already recorded for an
        // index, and add the current value only for indices this apply touches for the first time.
        val previous = LinkedHashMap(undo.orEmpty())
        for ((index, value) in AutoAlignSolver.previousValues(updates, values)) previous.putIfAbsent(index, value)
        if (!commit(updates)) {
            failure = "Couldn't save the change."
            return false
        }
        failure = null
        undo = previous
        appliedResult = current
        return true
    }

    fun undoApply(commit: (Map<Int, Float>) -> Boolean): Boolean {
        val previous = undo ?: return false
        if (!commit(previous)) {
            failure = "Couldn't restore the previous all-pass."
            return false
        }
        undo = null
        appliedResult = null
        return true
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
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
