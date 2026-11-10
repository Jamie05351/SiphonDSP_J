package app.siphondsp.fragment

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import app.siphondsp.compose.dialogs.AppDialog
import app.siphondsp.compose.dialogs.AppDialogs
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The two Parametric EQ band-edit dialogs: the numeric value editor and the tap-to-commit choice
 * list, shown by the app's dialog host. Callers wire the result to `commitBandEdit(...)`
 * themselves via the [onCommit] / [onPick] lambdas.
 */
class PeqDialogs(private val context: Context) {
    /** Trims trailing zeros ("1.41", "1000", "0.1") for the value dialog's field + range caption. */
    private val valueFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US))

    /**
     * Numeric value editor (the same dialog the Delay / Compressor pages use). The entered text
     * is parsed and clamped to [[min], [max]] on commit; one edit is one undo entry.
     */
    fun showValueInput(
        title: String,
        current: Double,
        min: Double,
        max: Double,
        suffix: String?,
        onCommit: (Double) -> Unit,
    ) {
        AppDialogs.show(
            AppDialog.NumberInput(
                title = title,
                rangeHint = "${valueFormat.format(min)}–${valueFormat.format(max)}",
                value = valueFormat.format(current),
                suffix = suffix,
                onCommit = commit@{ entered ->
                    val parsed = entered.toDoubleOrNull()?.coerceIn(min, max) ?: return@commit
                    onCommit(parsed)
                },
            ),
        )
    }

    /**
     * Tap-to-commit choice list. Rows are glass boxes tinted with [accentColor] (the current
     * scope's accent); the current value is full-opacity, the rest dimmed. Tapping a row commits
     * immediately and dismisses -- there is no OK button.
     */
    fun showChoice(
        @StringRes titleRes: Int,
        labels: List<String>,
        currentIndex: Int,
        accentColor: Int?,
        onPick: (Int) -> Unit,
    ) {
        AppDialogs.show(
            AppDialog.Choice(
                title = context.getString(titleRes),
                labels = labels,
                currentIndex = currentIndex,
                accent = accentColor?.let { Color(it) },
                onPick = onPick,
            ),
        )
    }
}
