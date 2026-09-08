package app.siphondsp.compose.controls

import android.content.Context
import android.view.LayoutInflater
import app.siphondsp.R
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

private val Fmt = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

/**
 * Tap-to-type numeric entry for the boxed value readouts, matching the View dashboard: the same
 * `showInputAlert` dialog (workspace theme, `min–max` hint, numeric keyboard, unit suffix) that
 * `CrossoverDashboardBuilder.createBoxedValueText` / `buildMiniValueRow` open on a value-box tap.
 * Kept as a plain Android dialog invoked from a Compose click handler rather than rebuilt in
 * Compose, so styling stays identical.
 *
 * [onCommit] gets the parsed value snapped to [step] and coerced into `[min, max]`; a cancelled
 * or unparseable entry is dropped.
 */
fun Context.showBmwNumberInput(
    label: String,
    min: Float,
    max: Float,
    current: Float,
    step: Float,
    suffix: String,
    onCommit: (Float) -> Unit,
) {
    showInputAlert(
        LayoutInflater.from(this),
        label,
        "${Fmt.format(min)}–${Fmt.format(max)}",
        Fmt.format(current),
        true,
        suffix,
        R.style.AppTheme_AlertDialogTheme_Workspace,
    ) { entered ->
        val parsed = entered?.toFloatOrNull() ?: return@showInputAlert
        val snapped = if (step > 0f) {
            (min + ((parsed - min) / step).roundToInt() * step).coerceIn(min, max)
        } else {
            parsed.coerceIn(min, max)
        }
        onCommit(snapped)
    }
}
