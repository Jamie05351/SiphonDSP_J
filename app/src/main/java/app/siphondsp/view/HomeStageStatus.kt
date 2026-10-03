package app.siphondsp.view

import app.siphondsp.model.NativeBmwDspValues

/**
 * What the front page's MBC squares and all-pass boxes show, read from the DSP values.
 *
 * [mbcBands] has one entry per MBC band (low to high): true while that band is compressing, which
 * needs both the MBC stage and the band itself switched on.
 *
 * [allPass] has one entry per output, in [OUTPUTS] order, with the frequency of each all-pass
 * section switched on there (empty while the output has none on).
 */
data class HomeStageStatus(
    val mbcBands: List<Boolean>,
    val allPass: List<AllPassOutput>,
) {
    data class AllPassOutput(val label: String, val frequenciesHz: List<Float>) {
        val on: Boolean get() = frequenciesHz.isNotEmpty()
    }

    companion object {
        /** The six outputs, as the all-pass page orders them, with their box labels. */
        val OUTPUTS = listOf(
            NativeBmwDspValues.OUTPUT_LOW_LEFT to "LO L",
            NativeBmwDspValues.OUTPUT_LOW_RIGHT to "LO R",
            NativeBmwDspValues.OUTPUT_MID_LEFT to "MID L",
            NativeBmwDspValues.OUTPUT_MID_RIGHT to "MID R",
            NativeBmwDspValues.OUTPUT_HIGH_LEFT to "HI L",
            NativeBmwDspValues.OUTPUT_HIGH_RIGHT to "HI R",
        )

        val OFF = HomeStageStatus(
            mbcBands = List(NativeBmwDspValues.MBC_BAND_COUNT) { false },
            allPass = OUTPUTS.map { (_, label) -> AllPassOutput(label, emptyList()) },
        )

        fun from(values: FloatArray): HomeStageStatus {
            if (values.size < NativeBmwDspValues.SIZE) return OFF
            val mbcOn = values[NativeBmwDspValues.INDEX_MBC_ENABLED] >= 0.5f
            val bands = List(NativeBmwDspValues.MBC_BAND_COUNT) { band ->
                mbcOn && values[NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_ENABLED)] >= 0.5f
            }
            val allPass = OUTPUTS.map { (output, label) ->
                val frequencies = (0 until NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT).mapNotNull { section ->
                    val base = allPassSectionBase(output, section)
                    // A section is [enabled, order, frequencyHz, Q].
                    values[base + 2].takeIf { values[base] >= 0.5f }
                }
                AllPassOutput(label, frequencies)
            }
            return HomeStageStatus(bands, allPass)
        }

        /** High's all-pass block lives in the schema tail, not the legacy four-output block. */
        private fun allPassSectionBase(output: Int, section: Int): Int =
            if (output == NativeBmwDspValues.OUTPUT_HIGH_LEFT || output == NativeBmwDspValues.OUTPUT_HIGH_RIGHT) {
                NativeBmwDspValues.highAllPassIndex(output, section, 0)
            } else {
                NativeBmwDspValues.INDEX_ALL_PASS +
                    (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) *
                    NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
            }
    }
}
