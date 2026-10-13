package app.siphondsp.compose.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import app.siphondsp.R
import app.siphondsp.compose.theme.BmwTheme
import app.siphondsp.model.NativeBmwDspValues
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The DSP master volume, down the faceplate's left side (Figma "Head unit · 8 · ON · Master
 * volume"): + over a small screen with the level in dB, over −. The buttons are drawn like the
 * power button and OUT (dark disc, purple rim and glow, lit by [g]); holding one repeats. Tapping
 * the level toggles mute. [volumeDb] / [muted] are the saved values, which come back round through
 * the settings broadcast after every step, so this only ever shows what the engine has.
 */
@Composable
internal fun VolumeColumn(
    volumeDb: Float,
    muted: Boolean,
    g: Float,
    onStep: (Int) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalHomeScale.current
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(s.dp(VolumeGap)),
    ) {
        VolumeButton(stringResource(R.string.home_volume_up), plus = true, g) { onStep(1) }
        VolumeReadout(volumeDb, muted, g, onToggleMute)
        VolumeButton(stringResource(R.string.home_volume_down), plus = false, g) { onStep(-1) }
    }
}

/** A round + or − button. One step on press, then repeating while held. */
@Composable
private fun VolumeButton(label: String, plus: Boolean, g: Float, onStep: () -> Unit) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(s.dp(VolumeButtonSize))
            .drawBehind {
                drawNodeGlow(signal, g, s.dp(NodeGlow).toPx())
                val r = size.minDimension / 2
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF1F2027), HomePalette.NodeFill), center, r),
                    r,
                )
                val rim = s.dp(3f + g).toPx()
                drawCircle(lerp(HomePalette.NodeEdgeOff, signal, g), r - rim / 2, style = Stroke(rim))
                val glyph = lerp(HomePalette.Caption, Color(0xFFE9D5FF), g)
                val arm = s.dp(17f).toPx()
                val stroke = s.dp(5.5f).toPx()
                drawLine(glyph, center - Offset(arm, 0f), center + Offset(arm, 0f), stroke, StrokeCap.Round)
                if (plus) drawLine(glyph, center - Offset(0f, arm), center + Offset(0f, arm), stroke, StrokeCap.Round)
            }
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    step()
                    val repeat = scope.launch {
                        delay(RepeatDelayMs)
                        while (true) {
                            step()
                            delay(RepeatEveryMs)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                }
            },
    )
}

/**
 * The level on its own small screen: "VOLUME", the dB in large type, and a bar along the
 * −80..0 dB range. Muted, the number dims and the caption reads MUTED. Tapping toggles mute.
 */
@Composable
private fun VolumeReadout(volumeDb: Float, muted: Boolean, g: Float, onToggleMute: () -> Unit) {
    val s = LocalHomeScale.current
    val signal = BmwTheme.colors.sliderHeadroom
    val shape = RoundedCornerShape(s.dp(14f))
    val whole = if (volumeDb.isFinite()) volumeDb.roundToInt() else null
    val number = whole?.let { if (it == 0) "0" else it.toString().replace('-', '−') } ?: "--"
    val lit = if (muted) 0f else g
    val muteLabel = stringResource(R.string.home_volume_mute)
    val state = if (muted) stringResource(R.string.home_volume_muted) else "$number dB"
    Column(
        Modifier
            .size(s.dp(VolumeScreen))
            .clip(shape)
            .background(HomePalette.Panel)
            .border(s.dp(2f), HomePalette.PanelEdge, shape)
            .clickable(role = Role.Switch, onClickLabel = muteLabel, onClick = onToggleMute)
            .semantics { stateDescription = state }
            .padding(horizontal = s.dp(12f), vertical = s.dp(12f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(if (muted) R.string.home_volume_muted else R.string.home_volume),
            color = if (muted) HomePalette.Ceiling else HomePalette.Caption,
            fontSize = s.sp(11f),
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(letterSpacing = 0.1.em),
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = number,
                color = lerp(HomePalette.Dim, Color.White, lit),
                fontSize = s.sp(36f),
                fontWeight = FontWeight.Bold,
                style = TextStyle(fontFeatureSettings = "tnum", letterSpacing = (-0.02).em),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = "dB",
                color = lerp(HomePalette.Dim, Color(0xFFC084FC), lit),
                fontSize = s.sp(14f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.alignByBaseline().padding(start = s.dp(3f)),
            )
        }
        val min = NativeBmwDspValues.MASTER_VOLUME_MIN_DB
        val max = NativeBmwDspValues.MASTER_VOLUME_MAX_DB
        val fraction = ((whole ?: min.toInt()) - min) / (max - min)
        Canvas(Modifier.fillMaxWidth().height(s.dp(5f))) {
            val corner = CornerRadius(size.height / 2)
            drawRoundRect(HomePalette.IdleFill, cornerRadius = corner)
            val w = size.width * fraction.coerceIn(0f, 1f)
            if (w > 0f) {
                val colour = lerp(HomePalette.Idle, signal, lit)
                drawRoundRect(colour.copy(alpha = 0.25f), Offset(0f, -size.height), Size(w, size.height * 3), CornerRadius(size.height * 1.5f))
                drawRoundRect(colour, size = Size(w, size.height), cornerRadius = corner)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (mark in listOf(min, max)) {
                Text(
                    text = if (mark == 0f) "0" else mark.roundToInt().toString().replace('-', '−'),
                    color = HomePalette.Muted,
                    fontSize = s.sp(9f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(s.dp(24f)),
                )
            }
        }
    }
}

// In design units (Figma frame 8): buttons the size between OUT and the power button, and the
// small screen between them, with the same gap above and below it.
internal const val VolumeButtonSize = 72f
internal const val VolumeScreen = 112f
internal const val VolumeGap = 20f
private const val RepeatDelayMs = 400L
private const val RepeatEveryMs = 110L
