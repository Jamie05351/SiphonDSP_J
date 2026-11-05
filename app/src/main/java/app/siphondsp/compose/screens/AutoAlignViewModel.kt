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

    var measuredWith by mutableStateOf(AutoAlignSolver.MeasuredWith.DSP_OFF)
    var progress by mutableStateOf<Float?>(null)
        private set
    var result by mutableStateOf<AutoAlignSolver.Result?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** What [apply] overwrote, for [undo]; null when there's nothing to undo. */
    var undo by mutableStateOf<Map<Int, Float>?>(null)
        private set

    private var job: Job? = null

    fun import(seat: Seat, slot: Slot, uri: Uri) {
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
            outcome.onSuccess { (name, m) ->
                imports[seat to slot] = Imported(name, m)
                importErrors.remove(seat to slot)
                result = null
            }.onFailure {
                importErrors[seat to slot] = it.message ?: "couldn't read that file"
            }
        }
    }

    fun clear(seat: Seat, slot: Slot) {
        imports.remove(seat to slot)
        importErrors.remove(seat to slot)
        result = null
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
        job?.cancel()
        failure = null
        result = null
        progress = 0f
        val snapshot = values.copyOf()
        job = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    AutoAlignSolver().solve(
                        seats, measuredWith, snapshot, peq,
                        isActive = { isActive },
                        onProgress = { p -> launch(Dispatchers.Main) { if (progress != null) progress = p } },
                    )
                }
            }
            progress = null
            outcome.onSuccess { result = it }.onFailure { failure = it.message ?: "analysis failed" }
        }
    }

    /** Writes the proposal through [commit]; remembers what it replaced for [undoApply]. */
    fun apply(values: FloatArray, commit: (Map<Int, Float>) -> Boolean): Boolean {
        val updates = result?.updates?.takeIf { it.isNotEmpty() } ?: return false
        val previous = AutoAlignSolver.previousValues(updates, values)
        if (!commit(updates)) return false
        undo = previous
        return true
    }

    fun undoApply(commit: (Map<Int, Float>) -> Boolean): Boolean {
        val previous = undo ?: return false
        if (!commit(previous)) return false
        undo = null
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
