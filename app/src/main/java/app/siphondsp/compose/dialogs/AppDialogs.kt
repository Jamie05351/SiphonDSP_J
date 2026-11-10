package app.siphondsp.compose.dialogs

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.siphondsp.compose.controls.CarUi
import app.siphondsp.compose.controls.bmwGlassBox
import app.siphondsp.compose.theme.BmwTheme

/**
 * The three looks the View dialogs had, kept for the Compose ones:
 * - [Standard]: the opaque dark panel (`#141418`, 28dp corners) every plain dialog used;
 * - [Workspace]: the DSP workspaces' value dialogs, a 40% panel over a light 0.2 scrim so the
 *   workspace shows through (was AppTheme.AlertDialogTheme.Workspace);
 * - [Glass]: the PEQ choice list's compact sheet (`#1B1B21`, 20dp corners, 16dp padding, at least
 *   46% / 72% of the screen wide; was ThemeOverlay.SiphonDSP.GlassDialog).
 */
enum class DialogStyle { Standard, Workspace, Glass }

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
        val style: DialogStyle = DialogStyle.Standard,
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

// The View theme's colours (themes.xml / colors_siphon.xml before the Compose move).
private val StandardSurface = Color(0xFF141418)
private val WorkspaceSurface = Color(0x66141418)
private val GlassSurface = Color(0xFF1B1B21)
private val FieldColor = Color(0xFF33333C)
private val OnSurfaceVariant = Color(0xFFC6C5CE)

// MaterialAlertDialog's default scrim, and the workspace theme's lighter one.
private const val STANDARD_DIM = 0.32f
private const val WORKSPACE_DIM = 0.2f

/** The window and panel of one dialog in [style]: title, scrolling [content], then [buttons]. */
@Composable
private fun DialogFrame(
    style: DialogStyle,
    title: String,
    onDismissRequest: () -> Unit,
    cancelable: Boolean = true,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = style == DialogStyle.Glass
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = cancelable,
            dismissOnClickOutside = cancelable,
            usePlatformDefaultWidth = !glass,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(if (style == DialogStyle.Workspace) WORKSPACE_DIM else STANDARD_DIM) }

        val configuration = LocalConfiguration.current
        val minWidth = if (glass) {
            val fraction = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 0.46f else 0.72f
            (configuration.screenWidthDp * fraction).dp
        } else {
            0.dp
        }
        Surface(
            shape = RoundedCornerShape(if (glass) 20.dp else 28.dp),
            color = when (style) {
                DialogStyle.Standard -> StandardSurface
                DialogStyle.Workspace -> WorkspaceSurface
                DialogStyle.Glass -> GlassSurface
            },
            contentColor = Color.White,
            modifier = Modifier.widthIn(min = minWidth),
        ) {
            val padding = if (glass) 16.dp else 24.dp
            Column(Modifier.padding(top = padding, start = padding, end = padding, bottom = if (buttons != null) 8.dp else padding)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
                if (buttons != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End,
                        content = buttons,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = BmwTheme.colors.lightBlue) }
}

@Composable
private fun MessageDialog(dialog: AppDialog.Message) {
    var checked by remember(dialog) { mutableStateOf(false) }
    fun close(action: (Boolean) -> Unit) {
        AppDialogs.dismiss()
        action(checked)
    }
    DialogFrame(
        style = DialogStyle.Standard,
        title = dialog.title,
        onDismissRequest = { if (dialog.cancelable) close(dialog.onDismiss) },
        cancelable = dialog.cancelable,
        buttons = {
            dialog.neutral?.let { DialogButton(it) { close(dialog.onNeutral) } }
            Spacer(Modifier.weight(1f))
            dialog.dismiss?.let { DialogButton(it) { close(dialog.onDismiss) } }
            DialogButton(dialog.confirm) { close(dialog.onConfirm) }
        },
    ) {
        Text(dialog.message, color = OnSurfaceVariant)
        if (dialog.checkbox != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { checked = !checked }
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    colors = CheckboxDefaults.colors(checkedColor = BmwTheme.colors.lightBlue),
                )
                Text(dialog.checkbox, color = Color.White)
            }
        }
    }
}

@Composable
private fun NumberInputDialog(dialog: AppDialog.NumberInput) {
    // The value starts selected, so typing replaces it.
    var text by remember(dialog) { mutableStateOf(TextFieldValue(dialog.value, TextRange(0, dialog.value.length))) }
    DialogFrame(
        style = dialog.style,
        title = dialog.title,
        onDismissRequest = AppDialogs::dismiss,
        buttons = {
            DialogButton(stringResource(android.R.string.cancel)) { AppDialogs.dismiss() }
            DialogButton(stringResource(android.R.string.ok)) {
                AppDialogs.dismiss()
                dialog.onCommit(text.text)
            }
        },
    ) {
        // The filled grey field of dialog_textinput.xml: min–max as its label, the unit as suffix.
        TextField(
            value = text,
            onValueChange = { new -> text = new.copy(text = new.text.filter { it.isDigit() || it == '.' || it == '-' }) },
            singleLine = true,
            label = { Text(dialog.rangeHint) },
            suffix = dialog.suffix?.let { { Text(it, color = OnSurfaceVariant) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = FieldColor,
                unfocusedContainerColor = FieldColor,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedLabelColor = OnSurfaceVariant,
                unfocusedLabelColor = OnSurfaceVariant,
                cursorColor = BmwTheme.colors.lightBlue,
                focusedIndicatorColor = BmwTheme.colors.lightBlue,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
        )
    }
}

@Composable
private fun ChoiceDialog(dialog: AppDialog.Choice) {
    val accent = dialog.accent ?: BmwTheme.colors.lightBlue
    DialogFrame(
        style = DialogStyle.Glass,
        title = dialog.title,
        onDismissRequest = AppDialogs::dismiss,
    ) {
        dialog.labels.forEachIndexed { i, label ->
            if (i > 0) Spacer(Modifier.height(6.dp))
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
}
