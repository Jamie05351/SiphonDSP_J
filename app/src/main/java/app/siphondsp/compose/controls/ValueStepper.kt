package app.siphondsp.compose.controls

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val StepperFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

/** Between the value box and the −/+ pill. */
internal val StepperGap = 6.dp

/** One half of the −/+ pill. */
internal val StepperHalfWidth = 40.dp

/** The whole −/+ pill: two halves and the divider between them. */
internal val StepperPillWidth = StepperHalfWidth * 2 + 1.dp

/**
 * A value you set with −/+ instead of a slider: the app's glass value box, then the PEQ list's −/+
 * pill beside it. This is what every DSP slider and knob became, because in a car a box you can
 * read at a glance and two big buttons are easier and safer than dragging a thumb, and they take
 * far less room.
 *
 * - Tap − or + for one step ([StepMath.next]): frequencies move a semitone at a time, everything
 *   else by [step].
 * - Hold one to repeat, faster the longer it is held ([StepMath.holdInterval] /
 *   [StepMath.holdMultiplier]); the value box glows while it runs.
 * - Tap the value box to type an exact value ([showBmwNumberInput]).
 *
 * Same preview / commit model as the sliders it replaces: [onPreview] on every change (live, not
 * saved), [onCommit] once a tap, a hold or a typed value is finished. A D-pad's centre button acts
 * as a tap, and holding it repeats too.
 */
@Composable
internal fun ValueStepper(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    accentColor: Color,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    boxWidth: Dp = 96.dp,
    height: Dp = 48.dp,
    textSize: TextUnit = 20.sp,
    unitSize: TextUnit = CarUi.MinText,
    enabled: Boolean = true,
    // Where a typed value goes; null sends it to [onCommit] like any other change.
    onTyped: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    var shown by remember(value) { mutableFloatStateOf(value.coerceIn(valueRange.start, valueRange.endInclusive)) }
    var holding by remember { mutableStateOf(false) }
    val musical = StepMath.isMusical(unit)

    val stepBy: (Int, Int) -> Unit = { direction, times ->
        val next = StepMath.next(shown, valueRange, step, direction, musical, times)
        if (next != shown) {
            shown = next
            onPreview(next)
        }
    }
    val multiplier: (Long) -> Int = { held -> StepMath.holdMultiplier(held, valueRange, step, musical) }

    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically) {
        val boxSource = remember { MutableInteractionSource() }
        BoxedValue(
            text = StepperFormat.format(shown),
            unit = unit,
            accentColor = accentColor,
            textSize = textSize,
            unitSize = unitSize,
            modifier = Modifier
                .width(boxWidth)
                .fillMaxHeight()
                .drawBehind {
                    if (holding) {
                        // A brighter glow round the box while a held button is running.
                        for (i in 3 downTo 1) {
                            val g = 2.dp.toPx() * i
                            drawRoundRect(
                                color = accentColor.copy(alpha = 0.14f * (4 - i)),
                                topLeft = Offset(-g, -g),
                                size = Size(size.width + 2 * g, size.height + 2 * g),
                                cornerRadius = CornerRadius(GlassBoxCornerRadius.toPx() + g),
                                style = Stroke(2.dp.toPx()),
                            )
                        }
                    }
                }
                .clickable(
                    interactionSource = boxSource,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClickLabel = "Type $label",
                ) {
                    context.showBmwNumberInput(label, valueRange.start, valueRange.endInclusive, shown, step, unit) {
                        shown = it
                        (onTyped ?: onCommit)(it)
                    }
                }
                .bmwFocusRing(boxSource),
        )
        Spacer(Modifier.width(StepperGap))
        Row(
            Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(MinusPlusFill),
        ) {
            HoldStepHalf("−", "Decrease $label", enabled, { stepBy(-1, it) }, multiplier, { holding = it }) { onCommit(shown) }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight(0.6f)
                    .align(Alignment.CenterVertically)
                    .background(MinusPlusGlyphColor),
            )
            HoldStepHalf("+", "Increase $label", enabled, { stepBy(+1, it) }, multiplier, { holding = it }) { onCommit(shown) }
        }
    }
}

/**
 * One half of the −/+ pill. A tap steps once and commits. Held past [StepMath.HOLD_DELAY_MS] it
 * repeats [onStep] until released (or the finger slides off), then commits once.
 */
@Composable
private fun HoldStepHalf(
    glyph: String,
    description: String,
    enabled: Boolean,
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
        Modifier
            .width(StepperHalfWidth)
            .fillMaxHeight()
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
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = MinusPlusGlyphColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** The stepper's full width for a [boxWidth] value box: box, gap and −/+ pill. */
internal fun stepperWidth(boxWidth: Dp): Dp = boxWidth + StepperGap + StepperPillWidth
