package app.siphondsp.compose.controls

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimum sizes for anything drawn or tapped on the 1280x480 (160dpi, so 1dp = 1px) head unit.
 * The main controls already meet these (Art* labels 16sp, values 20sp, boxes 48dp); this only
 * raises the outliers that were below them. Retune here.
 */
object CarUi {
    /** Smallest text for anything interactive or read as a label. */
    val MinText = 16.sp
    /** Smallest text for dense read-only chrome (graph axes, legends, tooltips). */
    val MinDenseText = 14.sp
    /** Smallest interactive control height. */
    val MinTouch = 48.dp
}
