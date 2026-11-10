package app.siphondsp.compose.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.compose.controls.CarUi

private val CardShape = RoundedCornerShape(12.dp)
private val CardBackground = Color(0xFF1C1C22)
private val CardBorder = Color(0xFF34343C)
private val BodyColor = Color(0xFFC6C5CE)

/** Background of a card that warns (the onboarding's "read this first" notice). */
val WarningCardBackground = Color(0xFF5C1B16)

/**
 * A rounded card with an optional icon, title and body (the body may carry simple HTML, as the
 * limitation strings do). Replaces the `Card` View on the Compose screens.
 */
@Composable
fun InfoCard(
    title: String?,
    body: String?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    background: Color = CardBackground,
    onClick: (() -> Unit)? = null,
    buttonLabel: String? = null,
    buttonEnabled: Boolean = true,
    onButton: () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(background, CardShape)
            .border(1.dp, CardBorder, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = if (title == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) {
                Text(title, color = Color.White, fontSize = CarUi.MinText, fontWeight = FontWeight.Bold)
            }
            if (body != null) {
                Text(AnnotatedString.fromHtml(body.replace("\n", "<br/>")), color = BodyColor, fontSize = CarUi.MinDenseText)
            }
            if (buttonLabel != null) {
                OutlinedButton(onClick = onButton, enabled = buttonEnabled) { Text(buttonLabel) }
            }
        }
    }
}

private val Limitations = listOf(
    R.string.limit_session_control_conflict_title to R.string.limit_session_control_conflict,
    R.string.limit_unsupported_apps_title to R.string.limit_unsupported_apps,
    R.string.limit_hw_accel_title to R.string.limit_hw_accel,
    R.string.limit_detect_delay_title to R.string.limit_detect_delay,
    R.string.limit_latency_title to R.string.limit_latency,
    R.string.limit_tested_devices_title to R.string.limit_tested_devices,
)

/** The rootless capture's known limitations (Settings → Troubleshooting, and onboarding). */
@Composable
fun LimitationsList() {
    val context = LocalContext.current
    for ((title, body) in Limitations) {
        InfoCard(title = context.getString(title), body = context.getString(body))
    }
}
