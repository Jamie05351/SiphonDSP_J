package app.siphondsp.compose.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphondsp.R
import app.siphondsp.compose.controls.CarUi
import app.siphondsp.compose.settings.InfoCard
import app.siphondsp.compose.settings.LimitationsList
import app.siphondsp.compose.settings.MStripe
import app.siphondsp.compose.settings.WarningCardBackground

/** The onboarding pages, numbered as the wizard counts them. */
object OnboardingPages {
    const val WELCOME = 1
    const val LIMITATIONS = 2
    const val METHOD_SELECT = 3
    const val ADB_SETUP = 4
    const val RUNTIME_PERMISSIONS = 5
    const val READY = 6
}

enum class SetupMethod { None, Shizuku, Adb }

/** One step of the ADB / Shizuku instructions. */
data class SetupStep(
    @DrawableRes val icon: Int,
    val body: String,
    val button: String? = null,
    val buttonEnabled: Boolean = true,
    val onButton: () -> Unit = {},
)

/** Everything the onboarding pages show; built by the activity from its current state. */
data class OnboardingUi(
    val page: Int,
    val isFirstPage: Boolean,
    val isLastPage: Boolean,
    val shizukuSupported: Boolean,
    val showNotificationPermission: Boolean,
    val setupTitle: String,
    val setupSteps: List<SetupStep>,
)

@Composable
fun OnboardingScreen(
    ui: OnboardingUi,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onChooseMethod: (SetupMethod) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        AnimatedContent(
            targetState = ui.page,
            transitionSpec = {
                val forward = targetState > initialState
                slideInHorizontally { if (forward) it else -it } togetherWith
                    slideOutHorizontally { if (forward) -it else it }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = "onboardingPage",
        ) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 24.dp),
                ) {
                    when (page) {
                        OnboardingPages.WELCOME -> WelcomePage()
                        OnboardingPages.LIMITATIONS -> LimitationsPage()
                        OnboardingPages.METHOD_SELECT -> MethodPage(ui.shizukuSupported, onChooseMethod)
                        OnboardingPages.ADB_SETUP -> SetupPage(ui.setupTitle, ui.setupSteps)
                        OnboardingPages.RUNTIME_PERMISSIONS -> PermissionsPage(ui.showNotificationPermission)
                        else -> ReadyPage()
                    }
                }
            }
        }
        MStripe()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, enabled = !ui.isFirstPage) { Text(stringResource(R.string.onboarding_back)) }
            Spacer(Modifier.weight(1f))
            // On the method page the user continues by choosing a method.
            if (ui.page != OnboardingPages.METHOD_SELECT) {
                Button(onClick = onNext) {
                    Text(stringResource(if (ui.isLastPage) R.string.onboarding_finish else R.string.onboarding_next))
                }
            }
        }
    }
}

@Composable
private fun PageHeader(@DrawableRes icon: Int, title: String, vararg captions: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        for (caption in captions) {
            Text(
                AnnotatedString.fromHtml(caption),
                color = Color(0xFFC6C5CE),
                fontSize = CarUi.MinText,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WelcomePage() {
    val context = LocalContext.current
    PageHeader(
        R.drawable.ic_welcome_24dp,
        context.getString(R.string.onboarding_greeting),
        context.getString(R.string.onboarding_caption),
        context.getString(R.string.onboarding_warning),
    )
}

@Composable
private fun LimitationsPage() {
    PageHeader(R.drawable.ic_twotone_warning_24dp, stringResource(R.string.onboarding_limitations_title))
    InfoCard(
        title = null,
        body = stringResource(R.string.onboarding_limitations_unstable),
        icon = R.drawable.ic_twotone_error_24dp,
        background = WarningCardBackground,
    )
    LimitationsList()
}

@Composable
private fun MethodPage(shizukuSupported: Boolean, onChoose: (SetupMethod) -> Unit) {
    PageHeader(R.drawable.ic_twotone_auto_fix_high_24, stringResource(R.string.onboarding_methods_title))
    val shizukuTitle = stringResource(R.string.onboarding_methods_shizuku_title).let {
        if (shizukuSupported) it else "$it (${stringResource(R.string.onboarding_methods_unsupported_append)})"
    }
    // Shizuku is the preferred method: highlighted, and first.
    InfoCard(
        title = shizukuTitle,
        body = stringResource(R.string.onboarding_methods_rootless_shizuku),
        background = if (shizukuSupported) Color(0xFF263040) else Color(0xFF1C1C22),
        onClick = if (shizukuSupported) ({ onChoose(SetupMethod.Shizuku) }) else null,
    )
    InfoCard(
        title = stringResource(R.string.onboarding_methods_adb_title),
        body = stringResource(R.string.onboarding_methods_rootless_adb),
        onClick = { onChoose(SetupMethod.Adb) },
    )
}

@Composable
private fun SetupPage(title: String, steps: List<SetupStep>) {
    PageHeader(R.drawable.ic_twotone_security_24dp, title, stringResource(R.string.onboarding_adb_caption))
    for (step in steps) {
        InfoCard(
            title = null,
            body = step.body,
            icon = step.icon,
            buttonLabel = step.button,
            buttonEnabled = step.buttonEnabled,
            onButton = step.onButton,
        )
    }
}

@Composable
private fun PermissionsPage(showNotification: Boolean) {
    PageHeader(
        R.drawable.ic_twotone_security_24dp,
        stringResource(R.string.onboarding_perm_title),
        stringResource(R.string.onboarding_perm_caption),
    )
    InfoCard(
        title = stringResource(R.string.onboarding_perm_microphone_title),
        body = stringResource(R.string.onboarding_perm_microphone_caption),
        icon = R.drawable.ic_twotone_mic_24dp,
    )
    if (showNotification) {
        InfoCard(
            title = stringResource(R.string.onboarding_perm_notification_title),
            body = stringResource(R.string.onboarding_perm_notification_caption),
            icon = R.drawable.ic_twotone_notifications_24dp,
        )
    }
    InfoCard(
        title = stringResource(R.string.onboarding_perm_cast_title),
        body = stringResource(R.string.onboarding_perm_cast_caption),
        icon = R.drawable.ic_twotone_cast_24dp,
    )
}

@Composable
private fun ReadyPage() {
    PageHeader(
        R.drawable.ic_twotone_check_circle_24dp,
        stringResource(R.string.onboarding_finish_header),
        stringResource(R.string.onboarding_finish_caption),
    )
}
