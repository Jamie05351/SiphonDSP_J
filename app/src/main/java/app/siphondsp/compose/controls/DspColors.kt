package app.siphondsp.compose.controls

import androidx.compose.ui.graphics.Color

/**
 * The DSP screens' shared colours: each module's accent (the front page's chain cards and the
 * sidebar's rail cards use these as their first border colour), the PEQ card's second colour, and
 * the rail cards' idle label.
 */
object DspColors {
    val Peq = Color(0xFF0A8EDF)
    val Delay = Color(0xFF2BB46B)
    val Xover = Color(0xFFF0A608)
    val XoverLow = Color(0xFFFF6A00)
    val Comp = Color(0xFFEA1A26)
    val Allpass = Color(0xFFAE4AF6)
    val BandMagenta = Color(0xFFD64BE0)
    val Label = Color(0xFFC9CCCF)
}
