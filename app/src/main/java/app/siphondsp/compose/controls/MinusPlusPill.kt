package app.siphondsp.compose.controls

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Grey glyphs and divider, as on the PEQ filter list. */
internal val MinusPlusGlyphColor = Color(0xFF9AA1AB)
internal val MinusPlusFill = Color(0xFF23272F)

/**
 * The app's −/+ stepper: one pill split by a thin divider, rather than two separate buttons, so it
 * stays compact next to the value box it steps. Every −/+ in the app is this -- [ValueStepper]
 * (the DSP pages), the PEQ filter list and the Delay / Gain / Mono Bass cards -- so they all behave
 * the same:
 *
 * - A tap steps once ([onStep] with direction -1 or +1, times 1), then [onCommit].
 * - Held past [StepMath.HOLD_DELAY_MS] it repeats until released (or the finger slides off),
 *   accelerating: repeats come faster ([StepMath.holdInterval]) and each moves further
 *   ([holdMultiplier], times > 1). [onCommit] runs once, on release. While it runs [onHoldingChange]
 *   is true, so [onStep] can preview (live, unsaved) and [onCommit] save once.
 *
 * [label] names the value for accessibility ("Left Mid delay" -> "Decrease Left Mid delay"); include
 * enough to tell repeated steppers apart, e.g. the channel and band.
 */
@Composable
internal fun MinusPlusPill(
    onStep: (direction: Int, times: Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    halfWidth: Dp = 40.dp,
    label: String? = null,
    glyphColor: Color = MinusPlusGlyphColor,
    enabled: Boolean = true,
    holdMultiplier: (heldMs: Long) -> Int = StepMath::defaultHoldMultiplier,
    onHoldingChange: (Boolean) -> Unit = {},
) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(MinusPlusFill),
    ) {
        HoldStepHalf(
            "−", label?.let { "Decrease $it" }, glyphColor, enabled, Modifier.width(halfWidth).fillMaxHeight(),
            { onStep(-1, it) }, holdMultiplier, onHoldingChange, onCommit,
        )
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight(0.6f)
                .align(Alignment.CenterVertically)
                .background(glyphColor),
        )
        HoldStepHalf(
            "+", label?.let { "Increase $it" }, glyphColor, enabled, Modifier.width(halfWidth).fillMaxHeight(),
            { onStep(+1, it) }, holdMultiplier, onHoldingChange, onCommit,
        )
    }
}

/**
 * One half of the −/+ pill. A tap steps once and commits. Held past [StepMath.HOLD_DELAY_MS] it
 * repeats [onStep] until released (or the finger slides off), then commits once. A D-pad's centre
 * button acts as a tap, and holding it repeats too.
 */
@Composable
private fun HoldStepHalf(
    glyph: String,
    description: String?,
    tint: Color,
    enabled: Boolean,
    modifier: Modifier,
    onStep: (times: Int) -> Unit,
    multiplier: (heldMs: Long) -> Int,
    onHoldingChange: (Boolean) -> Unit,
    onCommit: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val times by rememberUpdatedState(multiplier)
    val commit by rememberUpdatedState(onCommit)
    val holdingChange by rememberUpdatedState(onHoldingChange)
    // Whether this press has started repeating: its click is then not a tap, and its release commits.
    var repeated by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        var repeat: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    repeated = false
                    repeat?.cancel()
                    repeat = scope.launch {
                        delay(StepMath.HOLD_DELAY_MS)
                        repeated = true
                        holdingChange(true)
                        val start = System.currentTimeMillis()
                        while (isActive) {
                            val held = System.currentTimeMillis() - start
                            step(times(held))
                            delay(StepMath.holdInterval(held))
                        }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    repeat?.cancel()
                    repeat = null
                    if (repeated) {
                        holdingChange(false)
                        commit()
                    }
                }
            }
        }
    }

    Box(
        modifier
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
            ) {
                // A press that repeated has already stepped and commits on release.
                if (!repeated) {
                    step(1)
                    commit()
                }
            }
            .bmwFocusRing(source)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
