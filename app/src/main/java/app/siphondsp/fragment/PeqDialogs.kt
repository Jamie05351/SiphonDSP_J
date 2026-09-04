package app.siphondsp.fragment

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import app.siphondsp.R
import app.siphondsp.databinding.DialogPeqChoiceBinding
import app.siphondsp.utils.extensions.ContextExtensions.showInputAlert
import app.siphondsp.view.BmwDashboardSkin
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The two Parametric EQ band-edit dialogs, lifted verbatim out of [ParametricEqualizerFragment]:
 * the numeric value editor and the bespoke tap-to-commit choice list. Callers keep wiring the
 * result to `commitBandEdit(...)` themselves via the [onCommit] / [onPick] lambdas.
 */
class PeqDialogs(
    private val context: Context,
    private val layoutInflater: LayoutInflater,
) {
    /** Trims trailing zeros ("1.41", "1000", "0.1") for the value dialog's field + range caption. */
    private val valueFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US))

    /**
     * Numeric value editor -- the exact same [showInputAlert] dialog the Delay / Compressor
     * pages use (grey field, compact "min-max" range hint, Cancel/OK). The entered text is
     * parsed and clamped to [[min], [max]] on commit; one edit is one undo entry.
     */
    fun showValueInput(
        title: String,
        current: Double,
        min: Double,
        max: Double,
        suffix: String?,
        onCommit: (Double) -> Unit,
    ) {
        context.showInputAlert(
            layoutInflater,
            title,
            "${valueFormat.format(min)}–${valueFormat.format(max)}",
            valueFormat.format(current),
            true,
            suffix,
        ) { entered ->
            val parsed = entered?.toDoubleOrNull()?.coerceIn(min, max) ?: return@showInputAlert
            onCommit(parsed)
        }
    }

    /**
     * Bespoke tap-to-commit choice list (see dialog_peq_choice.xml). Rows are glass boxes tinted
     * with [accentColor] (the current scope's accent); the current value is full-opacity, the
     * rest dimmed. Tapping a row commits immediately and dismisses -- there is no OK button.
     */
    fun showChoice(
        @StringRes titleRes: Int,
        labels: List<String>,
        currentIndex: Int,
        accentColor: Int?,
        onPick: (Int) -> Unit,
    ) {
        val dialogBinding = DialogPeqChoiceBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_SiphonDSP_GlassDialog)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .create()
        val density = context.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        labels.forEachIndexed { i, label ->
            val row = TextView(context).apply {
                text = label
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
                background = BmwDashboardSkin.glassBoxDrawable(context, accentColor = accentColor)
                setTextColor(accentColor ?: BmwDashboardSkin.LIGHT_BLUE)
                alpha = if (i == currentIndex) 1f else 0.55f
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onPick(i)
                }
            }
            dialogBinding.choiceContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (6 * density).toInt() },
            )
        }
        dialog.show()
    }
}
