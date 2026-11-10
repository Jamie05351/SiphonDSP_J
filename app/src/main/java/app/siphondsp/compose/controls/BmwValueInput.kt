package app.siphondsp.compose.controls

import android.content.Context
import app.siphondsp.compose.dialogs.AppDialog
import app.siphondsp.compose.dialogs.AppDialogs
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

private val Fmt = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

/**
 * Tap-to-type numeric entry for the boxed value readouts: a number dialog (`min–max` hint,
 * numeric keyboard, unit suffix) shown by the app's dialog host.
 *
 * [onCommit] gets the parsed value snapped to [step] and coerced into `[min, max]`; a cancelled
 * or unparseable entry is dropped.
 */
@Suppress("UnusedReceiverParameter") // called from click handlers that have a Context to hand
fun Context.showBmwNumberInput(
    label: String,
    min: Float,
    max: Float,
    current: Float,
    step: Float,
    suffix: String,
    onCommit: (Float) -> Unit,
) {
    AppDialogs.show(
        AppDialog.NumberInput(
            title = label,
            rangeHint = "${Fmt.format(min)}–${Fmt.format(max)}",
            value = Fmt.format(current),
            suffix = suffix,
            onCommit = commit@{ entered ->
                val parsed = entered.toFloatOrNull() ?: return@commit
                val snapped = if (step > 0f) {
                    (min + ((parsed - min) / step).roundToInt() * step).coerceIn(min, max)
                } else {
                    parsed.coerceIn(min, max)
                }
                onCommit(snapped)
            },
        ),
    )
}
