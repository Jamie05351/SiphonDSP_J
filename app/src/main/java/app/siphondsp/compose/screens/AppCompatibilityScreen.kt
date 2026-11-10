package app.siphondsp.compose.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import app.siphondsp.compose.controls.CarUi
import app.siphondsp.compose.settings.AppScreen
import app.siphondsp.compose.settings.MStripe

private val BodyColor = Color(0xFFC6C5CE)

/**
 * Shown when a media app made the engine lose its audio session: names the app, and offers to
 * retry or to exclude the app from capture.
 *
 * @param busy true once Retry or Exclude was chosen (both buttons disable while the service restarts).
 */
@Composable
fun AppCompatibilityScreen(
    appName: String,
    packageName: String,
    icon: Drawable?,
    busy: Boolean,
    onOpenApp: () -> Unit,
    onRetry: () -> Unit,
    onExclude: () -> Unit,
    onBack: () -> Unit,
) {
    AppScreen(title = stringResource(R.string.title_activity_app_compat), onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.app_compat_title), color = Color.White, fontSize = CarUi.MinText, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.app_compat_explanation), color = BodyColor, fontSize = CarUi.MinDenseText)
                AppRow(icon = icon, title = appName, summary = packageName, onClick = onOpenApp)
                Text(stringResource(R.string.app_compat_instruction), color = BodyColor, fontSize = CarUi.MinDenseText)
                Text(stringResource(R.string.app_compat_instruction_exclude), color = BodyColor, fontSize = CarUi.MinDenseText)
            }
            MStripe()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_compat_retry))
                }
                Button(onClick = onExclude, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_compat_exclude))
                }
            }
        }
    }
}
