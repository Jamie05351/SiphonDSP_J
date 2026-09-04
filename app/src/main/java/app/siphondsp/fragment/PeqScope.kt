package app.siphondsp.fragment

import app.siphondsp.R
import app.siphondsp.dsp.BmwPeqBank

/**
 * The three PEQ banks as presented by the Parametric EQ screen's Full / Low / Mid scope chips.
 *
 * Extracted verbatim from [ParametricEqualizerFragment] (where it was a private nested enum) so the
 * pure import-routing and band-editing helpers ([ApoImportRouter], [PeqBandEditor]) can share it
 * without depending on the fragment.
 */
enum class PeqScope(
    val label: String,
    val fileName: String,
    val chipId: Int,
    val bank: BmwPeqBank,
) {
    FULL("Input Correction", "input_correction_parametric_eq.txt", R.id.peq_scope_full, BmwPeqBank.FULL),
    LOW("Low Band", "low_band_parametric_eq.txt", R.id.peq_scope_low, BmwPeqBank.LOW),
    MID("Mid Band", "mid_band_parametric_eq.txt", R.id.peq_scope_mid, BmwPeqBank.MID),
}
