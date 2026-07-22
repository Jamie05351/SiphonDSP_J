package app.siphondsp.model

import android.os.Bundle
import androidx.databinding.ObservableArrayList
import app.siphondsp.interop.ParametricBiquadProcessor
import app.siphondsp.utils.extensions.CompatExtensions.getSerializableAs
import timber.log.Timber
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*

/**
 * Result of parsing an EqualizerAPO format string.
 * @param skippedFilters number of malformed or unsupported filter lines that were skipped
 * @param preampDb the parsed Preamp value in dB (0.0 if not present)
 */
data class ApoImportResult(
    val skippedFilters: Int,
    val preampDb: Double
)

class ParametricEqBandList : ObservableArrayList<ParametricEqBand>() {
    private val dfFreq = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private val dfGain = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private val dfQ = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    init {
        dfFreq.maximumFractionDigits = 2
        dfGain.maximumFractionDigits = 6
        dfQ.maximumFractionDigits = 4
    }

    /**
     * Internal serialization format for SharedPreferences.
     * Format: "PEQ: freq gain q type channel; ..."
     *
     * The channel field is optional when reading, so legacy four-field presets
     * remain compatible and default to LEFT_RIGHT.
     */
    fun serialize(): String {
        val sb = StringBuilder("PEQ: ")
        for (band in this) {
            sb.append(
                "${dfFreq.format(band.frequency)} ${dfGain.format(band.gain)} " +
                    "${dfQ.format(band.q)} ${band.filterType.code} ${band.channel.code}; "
            )
        }
        return sb.toString()
    }

    fun deserialize(str: String) {
        clear()

        str.replace("PEQ:", "", ignoreCase = true)
            .replace("\n", " ")
            .split(";")
            .map { it.trim() }
            .filter(String::isNotBlank)
            .forEach { entry ->
                val parts = entry.split(Regex("\\s+")).filter(String::isNotBlank)
                val freq = parts.getOrNull(0)?.toDoubleOrNull()
                val gain = parts.getOrNull(1)?.toDoubleOrNull()
                val q = parts.getOrNull(2)?.toDoubleOrNull()
                val type = parts.getOrNull(3)?.toIntOrNull()
                val channel = parts.getOrNull(4)?.toIntOrNull() ?: ParametricEqChannel.LEFT_RIGHT.code

                if (freq != null && gain != null && q != null && type != null) {
                    add(
                        ParametricEqBand(
                            freq,
                            gain,
                            q,
                            ParametricEqFilterType.fromCode(type),
                            ParametricEqChannel.fromCode(channel),
                        )
                    )
                }
            }
    }

    /**
     * Equalizer APO has no standard per-channel suffix on individual filter
     * lines. Channel-specific bands are therefore exported in explicit channel
     * sections understood by Equalizer APO:
     *
     * Channel: ALL
     * Filter 1: ...
     * Channel: L
     * Filter 2: ...
     * Channel: R
     * Filter 3: ...
     */
    fun toApoString(preampDb: Double = 0.0): String {
        val sb = StringBuilder()
        sb.appendLine("Preamp: ${dfGain.format(preampDb)} dB")

        var index = 1
        for (channel in ParametricEqChannel.entries) {
            val channelBands = filter { it.channel == channel }
            if (channelBands.isEmpty()) continue

            sb.appendLine("Channel: ${channel.apoLabel()}")
            for (band in channelBands) {
                sb.appendLine(
                    "Filter ${index++}: ON ${band.filterType.apoLabel} " +
                        "Fc ${dfFreq.format(band.frequency)} Hz " +
                        "Gain ${dfGain.format(band.gain)} dB Q ${dfQ.format(band.q)}"
                )
            }
        }
        return sb.toString()
    }

    fun fromApoString(text: String): ApoImportResult {
        clear()
        var skipped = 0
        var preampDb = 0.0
        var activeChannel = ParametricEqChannel.LEFT_RIGHT

        val filterRegex = Regex(
            """Filter\s+\d+:\s+ON\s+(\S+)\s+Fc\s+([-+]?\d*\.?\d+)\s+Hz\s+Gain\s+([-+]?\d*\.?\d+)\s+dB\s+Q\s+([-+]?\d*\.?\d+)""",
            RegexOption.IGNORE_CASE
        )
        val preampRegex = Regex(
            """Preamp:\s*([-+]?\d*\.?\d+)\s*dB""",
            RegexOption.IGNORE_CASE
        )
        val channelRegex = Regex("""Channel:\s*(.+)""", RegexOption.IGNORE_CASE)

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue

            if (trimmed.startsWith("Preamp", ignoreCase = true)) {
                val match = preampRegex.find(trimmed)
                if (match == null) {
                    skipped++
                } else {
                    val parsed = match.groupValues[1].toDoubleOrNull()
                    if (parsed == null || !parsed.isFinite()) skipped++
                    else preampDb = parsed.coerceIn(-30.0, 0.0)
                }
                continue
            }

            if (trimmed.startsWith("Channel", ignoreCase = true)) {
                val label = channelRegex.find(trimmed)?.groupValues?.getOrNull(1)
                val parsed = label?.let(::parseApoChannel)
                if (parsed == null) skipped++ else activeChannel = parsed
                continue
            }

            if (!trimmed.startsWith("Filter", ignoreCase = true)) {
                Timber.d("fromApoString: skipping unrecognized line: $trimmed")
                skipped++
                continue
            }

            val match = filterRegex.find(trimmed)
            if (match == null) {
                skipped++
                continue
            }

            val filterType = ParametricEqFilterType.fromApoLabel(match.groupValues[1])
            val freq = match.groupValues[2].toDoubleOrNull()
            val gain = match.groupValues[3].toDoubleOrNull()
            val q = match.groupValues[4].toDoubleOrNull()

            if (filterType == null || freq == null || gain == null || q == null ||
                !freq.isFinite() || !gain.isFinite() || !q.isFinite() ||
                freq !in 20.0..20000.0 || gain !in -30.0..30.0 ||
                q !in ParametricBiquadProcessor.MIN_Q..ParametricBiquadProcessor.MAX_Q
            ) {
                skipped++
                continue
            }

            add(ParametricEqBand(freq, gain, q, filterType, activeChannel))
        }

        return ApoImportResult(skippedFilters = skipped, preampDb = preampDb)
    }

    fun fromBundle(bundle: Bundle) {
        clear()

        val freq = bundle.getDoubleArray(STATE_FREQ) ?: return
        val gain = bundle.getDoubleArray(STATE_GAIN) ?: return
        val q = bundle.getDoubleArray(STATE_Q) ?: return
        val types = bundle.getIntArray(STATE_TYPE) ?: return
        val channels = bundle.getIntArray(STATE_CHANNEL)
        val uuids = bundle.getSerializableAs<Array<UUID>>(STATE_UUID)

        val count = minOf(freq.size, gain.size, q.size, types.size)
        for (i in 0 until count) {
            add(
                ParametricEqBand(
                    freq[i],
                    gain[i],
                    q[i],
                    ParametricEqFilterType.fromCode(types[i]),
                    ParametricEqChannel.fromCode(channels?.getOrNull(i) ?: 0),
                    uuids?.getOrNull(i) ?: UUID.randomUUID(),
                )
            )
        }
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        val freqArr = DoubleArray(size)
        val gainArr = DoubleArray(size)
        val qArr = DoubleArray(size)
        val typeArr = IntArray(size)
        val channelArr = IntArray(size)
        val uuidArr = arrayListOf<UUID>()

        for ((i, band) in withIndex()) {
            freqArr[i] = band.frequency
            gainArr[i] = band.gain
            qArr[i] = band.q
            typeArr[i] = band.filterType.code
            channelArr[i] = band.channel.code
            uuidArr.add(band.uuid)
        }

        bundle.putDoubleArray(STATE_FREQ, freqArr)
        bundle.putDoubleArray(STATE_GAIN, gainArr)
        bundle.putDoubleArray(STATE_Q, qArr)
        bundle.putIntArray(STATE_TYPE, typeArr)
        bundle.putIntArray(STATE_CHANNEL, channelArr)
        bundle.putSerializable(STATE_UUID, uuidArr.toTypedArray())
        return bundle
    }

    private fun ParametricEqChannel.apoLabel(): String = when (this) {
        ParametricEqChannel.LEFT_RIGHT -> "ALL"
        ParametricEqChannel.LEFT -> "L"
        ParametricEqChannel.RIGHT -> "R"
    }

    private fun parseApoChannel(value: String): ParametricEqChannel? {
        val tokens = value.uppercase(Locale.ENGLISH)
            .split(Regex("[,\\s]+"))
            .filter(String::isNotBlank)
            .toSet()

        return when {
            tokens.any { it in setOf("ALL", "L+R", "LR", "STEREO") } -> ParametricEqChannel.LEFT_RIGHT
            tokens == setOf("L") || tokens == setOf("LEFT") -> ParametricEqChannel.LEFT
            tokens == setOf("R") || tokens == setOf("RIGHT") -> ParametricEqChannel.RIGHT
            tokens.contains("L") && tokens.contains("R") -> ParametricEqChannel.LEFT_RIGHT
            else -> null
        }
    }

    companion object {
        private const val STATE_FREQ = "peq_freq"
        private const val STATE_GAIN = "peq_gain"
        private const val STATE_Q = "peq_q"
        private const val STATE_TYPE = "peq_type"
        private const val STATE_CHANNEL = "peq_channel"
        private const val STATE_UUID = "peq_uuid"
    }
}
