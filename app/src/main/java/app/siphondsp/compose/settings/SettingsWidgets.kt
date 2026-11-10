package app.siphondsp.compose.settings

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.BmwDropdown
import app.siphondsp.compose.controls.BmwSwitch
import app.siphondsp.compose.controls.CarUi
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.utils.preferences.Preferences
import kotlin.reflect.KClass

// Building blocks for the app's own (non-DSP) screens: Settings, Blocklist, App compatibility,
// Measurement capture, Onboarding. They replace the AndroidX Preference widgets and the
// toolbar + ///M stripe header the View layouts used.

private val ScreenBackground = Color(0xFF101014)
private val HeaderBackground = Color(0xFF141418)
private val SummaryColor = Color(0xFFC6C5CE)
private val RowPadding = 16.dp

/** A full-screen page: the slim header (back button, centred title, ///M stripe) over [content]. */
@Composable
fun AppScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(ScreenBackground),
    ) {
        AppScreenHeader(title, onBack, actions)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
        ) { content() }
    }
}

@Composable
fun AppScreenHeader(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(HeaderBackground)
            .statusBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = CarUi.MinTouch),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_workspace_back_32dp),
                        contentDescription = LocalContext.current.getString(R.string.back),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 64.dp),
            )
            Row(Modifier.align(Alignment.CenterEnd)) { actions() }
        }
        MStripe()
    }
}

/** The BMW ///M tri-colour stripe under every header. */
@Composable
fun MStripe(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(3.dp),
    ) {
        for (colour in listOf(Color(0xFF0066B1), Color(0xFF2B2B8F), Color(0xFFE22718))) {
            Spacer(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(colour),
            )
        }
    }
}

/** A scrolling column of setting rows, kept to a readable width on wide head-unit screens. */
@Composable
fun SettingsColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
fun SettingsCategory(title: String) {
    Text(
        text = title,
        color = BmwTheme.colors.lightBlue,
        fontSize = CarUi.MinDenseText,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = RowPadding, end = RowPadding, top = 20.dp, bottom = 4.dp),
    )
}

/**
 * One setting row: optional icon, title, optional summary and an optional [trailing] control.
 * Clickable when [onClick] is set.
 */
@Composable
fun SettingsItem(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    iconTint: Color = Color.White,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CarUi.MinTouch + 16.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = RowPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = CarUi.MinText)
            if (!summary.isNullOrBlank()) {
                Text(summary, color = SummaryColor, fontSize = CarUi.MinDenseText)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summaryOn: String? = null,
    summaryOff: String? = null,
    enabled: Boolean = true,
) {
    SettingsItem(
        title = title,
        summary = if (checked) summaryOn else summaryOff,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            BmwSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, contentDescription = title)
        },
    )
}

/** A choice between fixed [entries]; [values] are what is stored. */
@Composable
fun SettingsChoiceItem(
    title: String,
    entries: List<String>,
    values: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    summary: String? = null,
) {
    SettingsItem(
        title = title,
        summary = summary,
        trailing = {
            BmwDropdown(
                options = entries,
                selectedIndex = values.indexOf(selected).coerceAtLeast(0),
                onSelect = { onSelect(values[it]) },
                modifier = Modifier.width(220.dp),
            )
        },
    )
}

/** A stepped slider; [onCommit] runs once, when the finger lifts. */
@Composable
fun SettingsSliderItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var current by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) current else value
    Column(Modifier.padding(horizontal = RowPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = CarUi.MinText, modifier = Modifier.weight(1f))
            Text("${shown.toInt()} $unit", color = SummaryColor, fontSize = CarUi.MinText)
        }
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                current = (Math.round(it / step) * step).coerceIn(range.start, range.endInclusive)
            },
            onValueChangeFinished = {
                dragging = false
                onCommit(current)
            },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).toInt() - 1,
        )
    }
}

/** A number typed into a dialog. */
@Composable
fun SettingsNumberItem(title: String, value: String, onCommit: (String) -> Unit, enabled: Boolean = true) {
    var editing by remember { mutableStateOf(false) }
    SettingsItem(title = title, summary = value, enabled = enabled, onClick = { editing = true })
    if (editing) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotEmpty(),
                    onClick = {
                        editing = false
                        onCommit(text)
                    },
                ) { Text(LocalContext.current.getString(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(LocalContext.current.getString(android.R.string.cancel)) }
            },
        )
    }
}

/** A message with an OK button (replaces `Context.showAlert` on the Compose screens). */
@Composable
fun MessageDialog(title: String, message: CharSequence, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message.toString()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(LocalContext.current.getString(android.R.string.ok)) } },
    )
}

/** A pending [MessageDialog]: `show(title, message)` from a click handler, [Render] once per screen. */
class MessageDialogState {
    private var message by mutableStateOf<Pair<String, CharSequence>?>(null)

    fun show(title: String, text: CharSequence) {
        message = title to text
    }

    @Composable
    fun Render() {
        message?.let { (title, text) -> MessageDialog(title, text) { message = null } }
    }
}

@Composable
fun rememberMessageDialogState() = remember { MessageDialogState() }

/**
 * The live value of one preference: recomposes when it changes, from this screen or anywhere
 * else (the service, a backup restore).
 */
@Composable
fun <T : Any> Preferences.AbstractPreferences.collectAsState(@StringRes key: Int, type: KClass<T>): State<T> {
    val keyName = remember(key) { context.getString(key) }
    val state = remember(key) { mutableStateOf(get(key, null, type)) }
    DisposableEffect(this, key) {
        // Held here, strongly: SharedPreferences only keeps a weak reference to its listeners.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == keyName) state.value = get(key, null, type)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        state.value = get(key, null, type)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
inline fun <reified T : Any> Preferences.AbstractPreferences.collectAsState(@StringRes key: Int): State<T> =
    collectAsState(key, T::class)
