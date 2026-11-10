package app.siphondsp.compose.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.compose.controls.CarUi
import app.siphondsp.compose.controls.bmwGlassBox
import app.siphondsp.compose.theme.BmwTheme

/** A dialog asked for from outside a composition (a click handler, a helper class). */
sealed interface AppDialog {
    /** A message with up to three buttons, and optionally a checkbox whose state they receive. */
    data class Message(
        val title: String,
        val message: String,
        val confirm: String,
        val onConfirm: (checked: Boolean) -> Unit = {},
        val dismiss: String? = null,
        val onDismiss: (checked: Boolean) -> Unit = {},
        val neutral: String? = null,
        val onNeutral: (checked: Boolean) -> Unit = {},
        val checkbox: String? = null,
        val cancelable: Boolean = true,
    ) : AppDialog

    /** A typed number: [onCommit] gets the text on OK (nothing on cancel). */
    data class NumberInput(
        val title: String,
        val rangeHint: String,
        val value: String,
        val suffix: String?,
        val onCommit: (String) -> Unit,
    ) : AppDialog

    /** Tap-to-commit choice rows (no OK button); the current one is full-opacity. */
    data class Choice(
        val title: String,
        val labels: List<String>,
        val currentIndex: Int,
        val accent: Color?,
        val onPick: (Int) -> Unit,
    ) : AppDialog
}

/** The one dialog showing, drawn by [AppDialogHost] (MainActivity hosts every screen). */
object AppDialogs {
    var current by mutableStateOf<AppDialog?>(null)
        private set

    fun show(dialog: AppDialog) {
        current = dialog
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun AppDialogHost() {
    when (val dialog = AppDialogs.current) {
        null -> Unit
        is AppDialog.Message -> MessageDialog(dialog)
        is AppDialog.NumberInput -> NumberInputDialog(dialog)
        is AppDialog.Choice -> ChoiceDialog(dialog)
    }
}

@Composable
private fun MessageDialog(dialog: AppDialog.Message) {
    var checked by remember(dialog) { mutableStateOf(false) }
    fun close(action: (Boolean) -> Unit) {
        AppDialogs.dismiss()
        action(checked)
    }
    AlertDialog(
        onDismissRequest = { if (dialog.cancelable) close(dialog.onDismiss) },
        title = { Text(dialog.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(dialog.message)
                if (dialog.checkbox != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { checked = !checked }
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text(dialog.checkbox)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { close(dialog.onConfirm) }) { Text(dialog.confirm) } },
        dismissButton = if (dialog.dismiss == null && dialog.neutral == null) null else {
            {
                Row {
                    dialog.neutral?.let { TextButton(onClick = { close(dialog.onNeutral) }) { Text(it) } }
                    dialog.dismiss?.let { TextButton(onClick = { close(dialog.onDismiss) }) { Text(it) } }
                }
            }
        },
    )
}

@Composable
private fun NumberInputDialog(dialog: AppDialog.NumberInput) {
    // The value starts selected, so typing replaces it.
    var text by remember(dialog) { mutableStateOf(TextFieldValue(dialog.value, TextRange(0, dialog.value.length))) }
    AlertDialog(
        onDismissRequest = AppDialogs::dismiss,
        title = { Text(dialog.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.copy(text = new.text.filter { it.isDigit() || it == '.' || it == '-' }) },
                singleLine = true,
                label = { Text(dialog.rangeHint) },
                suffix = dialog.suffix?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                AppDialogs.dismiss()
                dialog.onCommit(text.text)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = AppDialogs::dismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceDialog(dialog: AppDialog.Choice) {
    val accent = dialog.accent ?: BmwTheme.colors.lightBlue
    AlertDialog(
        onDismissRequest = AppDialogs::dismiss,
        title = { Text(dialog.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                dialog.labels.forEachIndexed { i, label ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = CarUi.MinTouch)
                            .alpha(if (i == dialog.currentIndex) 1f else 0.55f)
                            .bmwGlassBox(accent)
                            .clickable {
                                AppDialogs.dismiss()
                                dialog.onPick(i)
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {},
    )
}
