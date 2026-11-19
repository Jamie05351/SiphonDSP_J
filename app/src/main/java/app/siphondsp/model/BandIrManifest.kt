package app.siphondsp.model

import org.json.JSONObject

/** Versioned interchange with the Windows designer. Frequencies are metadata, never app edits. */
data class BandIrManifest(
    val sampleRate: Int,
    val taps: Int,
    val latencyFrames: Int,
    val lowMidHz: Double,
    val midHighHz: Double,
    val files: List<String>,
) {
    fun matchesCrossovers(lowMid: Float, midHigh: Float): Boolean =
        kotlin.math.abs(lowMid - lowMidHz) <= 1.0 && kotlin.math.abs(midHigh - midHighHz) <= 1.0

    companion object {
        /**
         * The app's band corners per side, as (Low/Mid, Mid/High) pairs: one from the Low and
         * Mid upper outputs, one from the Mid and High outputs. Mid/High is the Mid upper
         * lowpass when it's on, otherwise the High highpass -- the only corner there is then.
         */
        fun appCorners(value: (Int) -> Float): List<Pair<Float, Float>> = (0..1).flatMap { side ->
            val mid = NativeBmwDspValues.OUTPUT_MID_LEFT + side
            val highHp = value(NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT + side, NativeBmwDspValues.FIELD_CROSSOVER_FREQ))
            val midHigh = if (value(NativeBmwDspValues.midUpperXoIndex(mid, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED)) >= .5f)
                value(NativeBmwDspValues.midUpperXoIndex(mid, NativeBmwDspValues.MID_UPPER_XO_FIELD_FREQ)) else highHp
            listOf(
                value(NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT + side, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)) to midHigh,
                value(NativeBmwDspValues.outputIndex(mid, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)) to highHp,
            )
        }

        fun parse(text: String): BandIrManifest {
            require(text.length <= 131072) { "Manifest is too large" }
            val json = JSONObject(text)
            require(json.getString("format") == "siphondsp-band-ir" && json.getInt("version") == 1) { "Unsupported band IR package" }
            require(json.getBoolean("correction_only")) { "This package contains crossovers; correction-only IRs are required" }
            val rate = json.getInt("sample_rate")
            val taps = json.getInt("taps")
            val latency = json.getInt("latency_frames")
            require(rate == 44100 || rate == 48000) { "Package sample rate must be 44100 or 48000 Hz" }
            require(taps in 16..65536 && latency in 0 until taps) { "Invalid FIR length or latency" }
            val xo = json.getJSONObject("crossover_hz")
            val low = xo.getDouble("low_mid")
            val high = xo.getDouble("mid_high")
            require(low.isFinite() && high.isFinite() && low >= 20 && high > low && high < rate / 2.0) { "Invalid crossover metadata" }
            val fileObject = json.getJSONObject("files")
            val files = listOf("low", "mid", "high").map { fileObject.getString(it) }
            require(files.distinct().size == 3 && files.all { it.matches(Regex("[A-Za-z0-9_-]+\\.wav")) }) { "Invalid or repeated WAV filenames" }
            return BandIrManifest(rate, taps, latency, low, high, files)
        }
    }
}
