package app.siphondsp.fragment

import app.siphondsp.dsp.BmwPeqBank

/**
 * The three PEQ banks as presented by the Parametric EQ screen's Pre EQ / Low / Mid scope
 * control ([app.siphondsp.compose.screens.PeqScopeControl]).
 *
 * Extracted from `ParametricEqualizerFragment` (where it was a private nested enum) so the pure
 * import-routing and band-editing helpers ([ApoImportRouter], [PeqBandEditor]) can share it.
 * `fileName` is the default export filename per bank.
 */
enum class PeqScope(
    val label: String,
    val fileName: String,
    val bank: BmwPeqBank,
) {
    FULL("Pre EQ", "input_correction_parametric_eq.txt", BmwPeqBank.FULL),
    LOW("Low Band", "low_band_parametric_eq.txt", BmwPeqBank.LOW),
    MID("Mid Band", "mid_band_parametric_eq.txt", BmwPeqBank.MID),
}
