package app.siphondsp.fragment

import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import java.io.InputStream
import java.util.Locale

/**
 * One REW/APO file that [ApoImportRouter.routeApoFileName] mapped onto a (bank, channel), carried
 * from the import dialog to [PeqBandEditor.importRoutedApo]. [name] and [skipped] are only used
 * by the fragment's summary toast; the editor reads [scope], [channel], [bands] and [preampDb].
 */
data class RoutedApoImport(
    val name: String,
    val scope: PeqScope,
    val channel: ParametricEqChannel,
    val bands: ParametricEqBandList,
    val preampDb: Double,
    val skipped: Int,
    /** The file's Preamp before the -30..+12 dB limit; null if it had none (see ApoImportResult). */
    val preampInFileDb: Double? = null,
)

/**
 * Pure helpers for the "import a REW/APO filter set" flow: mapping an export's filename onto a
 * (bank, channel) target, and reading an import stream with a hard size ceiling.
 *
 * Extracted verbatim from [ParametricEqualizerFragment] so the routing table and the size guard
 * can be unit-tested without a fragment or the Android framework.
 */
object ApoImportRouter {

    /**
     * What an import does to the Pre EQ preamp, for its dialog and toast: "Preamp +12.0 dB",
     * with "(file says +14.5 dB, limited to +12 dB)" when the file's value was out of range.
     * Null when the file had no Preamp line -- the preamp is then left as it is on Append and
     * reset to 0 dB on Replace, which [NO_PREAMP_ON_REPLACE] describes.
     */
    fun preampSummary(appliedDb: Double, inFileDb: Double?): String? {
        if (inFileDb == null) return null
        val applied = "Preamp ${formatDb(appliedDb)}"
        return if (inFileDb == appliedDb) applied
        else "$applied (file says ${formatDb(inFileDb)}, limited to ${formatDb(appliedDb)})"
    }

    const val NO_PREAMP_ON_REPLACE = "No Preamp line: Replace sets the preamp to 0.0 dB"

    /** "+12.0 dB" / "-6.5 dB" / "0.0 dB". */
    fun formatDb(db: Double): String {
        val rounded = Math.round(db * 10.0) / 10.0
        val sign = if (rounded > 0.0) "+" else ""
        return sign + String.format(Locale.US, "%.1f dB", if (rounded == 0.0) 0.0 else rounded)
    }

    /** Hard ceiling on a single import file, matched to the user-facing "1 MB safety limit". */
    const val MAX_IMPORT_CHARS = 1_000_000

    /**
     * Map a REW/APO export filename onto a (bank, channel). "input" / "correction" / "full" go
     * to Input Correction as L+R; "low" / "mid" / "high" (or "tweeter") pick up a side from a left/right (or _l/_r,
     * -l/-r) token in the name, otherwise L+R.
     */
    fun routeApoFileName(name: String): Pair<PeqScope, ParametricEqChannel>? {
        val base = name.substringBeforeLast('.').lowercase()
        val side = when {
            Regex("""(?:^|[ _.\-])(left|l)(?:$|[ _.\-])""").containsMatchIn(base) -> ParametricEqChannel.LEFT
            Regex("""(?:^|[ _.\-])(right|r)(?:$|[ _.\-])""").containsMatchIn(base) -> ParametricEqChannel.RIGHT
            else -> null
        }
        return when {
            "input" in base || "correction" in base || "full" in base ->
                PeqScope.FULL to ParametricEqChannel.LEFT_RIGHT
            "low" in base -> PeqScope.LOW to (side ?: ParametricEqChannel.LEFT_RIGHT)
            "mid" in base -> PeqScope.MID to (side ?: ParametricEqChannel.LEFT_RIGHT)
            "high" in base || "tweeter" in base -> PeqScope.HIGH to (side ?: ParametricEqChannel.LEFT_RIGHT)
            else -> null
        }
    }

    /**
     * Read [input] fully into a String, throwing [IllegalArgumentException] the moment it would
     * exceed [MAX_IMPORT_CHARS]. The caller owns the stream (this mirrors the previous
     * `openInputStream(uri)?.use(::readImportText)` call sites).
     */
    fun readImportText(input: InputStream): String {
        val reader = input.bufferedReader()
        val output = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (output.length + count > MAX_IMPORT_CHARS) {
                throw IllegalArgumentException("The import file is larger than the 1 MB safety limit")
            }
            output.append(buffer, 0, count)
        }
        return output.toString()
    }
}
