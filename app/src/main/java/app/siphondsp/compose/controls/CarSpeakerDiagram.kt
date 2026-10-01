package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.siphondsp.R
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The three driver types in the car, one per crossover band. */
enum class SpeakerKind { TWEETER, MID, WOOFER }

// Positions as fractions of car_top (1400x703). Right-hand-drive car, front at the top, so the
// screen-left driver of each pair is the Left channel. Tweeters sit in the mirror triangles, mids low on
// the door cards (spread apart for tapping), woofers under the front seat cushions; heads at the headrests.
private val LeftPos = mapOf(
    SpeakerKind.TWEETER to Offset(0.245f, 0.233f),
    SpeakerKind.MID to Offset(0.126f, 0.445f),
    SpeakerKind.WOOFER to Offset(0.380f, 0.682f),
)
private val RightPos = mapOf(
    SpeakerKind.TWEETER to Offset(0.761f, 0.245f),
    SpeakerKind.MID to Offset(0.880f, 0.449f),
    SpeakerKind.WOOFER to Offset(0.621f, 0.682f),
)
private val DriverHead = Offset(0.619f, 0.810f)
private val PassengerHead = Offset(0.379f, 0.810f)

// Diameter as a fraction of the diagram width.
private fun SpeakerKind.sizeFraction() = when (this) {
    SpeakerKind.TWEETER -> 0.030f
    SpeakerKind.MID -> 0.075f
    SpeakerKind.WOOFER -> 0.110f
}

/** Aspect ratio of the car cut-out (1400 x 703). */
const val CarDiagramAspect = 1400f / 703f

/**
 * Live car diagram for the Gains & Delay pages. The car cut-out with the six drivers as real
 * objects: the [selected] band's two drivers are lit in [accent] with a path line from the driver's
 * seat, and the rest are dimmed. Tapping a driver calls [onSelect] with its band's kind.
 *
 * [seats] are the listening positions the path lines start from (driver, or both front seats), captioned on
 * the map. [title], if given, is shown as a badge in the corner in [accent].
 *
 * This composable is stateless: it only *reports* a tap through [onSelect]. Whoever owns the band
 * (the pager on Gains & Delay) must change it, and the new [selected] / [accent] / [title] then flow
 * back in. If tapping a driver does nothing, check that [onSelect] reaches that owner.
 *
 * [label] returns the text to pin beside a driver (`isLeft` = the screen-left channel), or null for
 * none; it is read while drawing, so a changing value only redraws the canvas. [level] (0..1) makes
 * the selected drivers' cones pulse; leave it null (the default) for a static diagram, which also
 * means no per-frame redraw loop runs.
 *
 * Assets: res/drawable-nodpi/car_top.webp, spk_mid.webp, spk_woofer.webp. Tweeters are drawn in
 * code. Size it with `Modifier.width(...)`; the height follows from [CarDiagramAspect].
 */
@Composable
fun CarSpeakerDiagram(
    selected: SpeakerKind,
    accent: Color,
    onSelect: (SpeakerKind) -> Unit,
    modifier: Modifier = Modifier,
    seats: List<ListeningSeat> = listOf(ListeningSeat.DRIVER),
    title: String? = null,
    label: (SpeakerKind, Boolean) -> String? = { _, _ -> null },
    level: ((SpeakerKind, Boolean) -> Float)? = null,
) {
    val mid = ImageBitmap.imageResource(R.drawable.spk_mid)
    val woofer = ImageBitmap.imageResource(R.drawable.spk_woofer)
    val measurer = rememberTextMeasurer()
    // Always call the latest callback, even if the pointer-input block outlives a recomposition.
    val currentOnSelect by rememberUpdatedState(onSelect)
    var frame by remember { mutableLongStateOf(0L) }
    if (level != null) {
        LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    }

    Box(
        modifier
            .aspectRatio(CarDiagramAspect)
            .semantics { contentDescription = "Speaker map. Tap a driver to choose its band." }
            // D-pad / rotary: focus the map, then left/up = previous band, right/down = next band.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val all = SpeakerKind.entries
                val here = all.indexOf(selected)
                val next = when (event.key) {
                    Key.DirectionLeft, Key.DirectionUp -> all.getOrNull(here - 1)
                    Key.DirectionRight, Key.DirectionDown -> all.getOrNull(here + 1)
                    else -> return@onKeyEvent false
                }
                next?.let(currentOnSelect)
                next != null
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    var best: SpeakerKind? = null
                    var bestDistance = Float.MAX_VALUE
                    SpeakerKind.entries.forEach { kind ->
                        listOf(LeftPos, RightPos).forEach { map ->
                            val c = map.getValue(kind)
                            val reach = maxOf(kind.sizeFraction() * w / 2f, w * 0.05f) * 1.15f
                            val d = hypot(p.x - c.x * w, p.y - c.y * h)
                            if (d < reach && d < bestDistance) { best = kind; bestDistance = d }
                        }
                    }
                    best?.let(currentOnSelect)
                }
            },
    ) {
        Image(painterResource(R.drawable.car_top), contentDescription = null, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val t = if (level != null) frame / 1_000_000f else 0f // ms; only ticks when animated
            val w = size.width
            val h = size.height
            val heads = seats.map { seat ->
                (if (seat == ListeningSeat.DRIVER) DriverHead else PassengerHead).let { Offset(it.x * w, it.y * h) }
            }

            // Distance pills sit halfway along the path; with two seats, halfway to the point between them.
            val labelAnchor = heads.reduce { acc, o -> acc + o } / heads.size.toFloat()

            // Path lines first, so the drivers sit on top of them.
            heads.forEach { head ->
                SpeakerKind.entries.forEach { kind ->
                    listOf(true, false).forEach { left ->
                        val c = (if (left) LeftPos else RightPos).getValue(kind).let { Offset(it.x * w, it.y * h) }
                        if (kind == selected) {
                            drawLine(accent.copy(alpha = 0.16f), head, c, strokeWidth = w * 0.012f, cap = StrokeCap.Round)
                            drawLine(accent, head, c, strokeWidth = w * 0.004f, cap = StrokeCap.Round)
                        } else {
                            drawLine(Color.White.copy(alpha = 0.10f), head, c, strokeWidth = 1.5f)
                        }
                    }
                }
            }
            seats.zip(heads).forEach { (seat, head) ->
                drawCircle(Color.White.copy(alpha = 0.9f), w * 0.011f, head)
                drawCircle(Color.White.copy(alpha = 0.35f), w * 0.024f, head, style = Stroke(1.5f))
                measurer.measure(
                    if (seat == ListeningSeat.DRIVER) "DRIVER" else "PASSENGER",
                    TextStyle(fontSize = CarUi.MinDenseText, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.55f)),
                ).let { caption ->
                    drawText(caption, topLeft = Offset(head.x - caption.size.width / 2f, head.y + w * 0.034f))
                }
            }

            SpeakerKind.entries.forEach { kind ->
                listOf(true, false).forEachIndexed { i, left ->
                    val c = (if (left) LeftPos else RightPos).getValue(kind).let { Offset(it.x * w, it.y * h) }
                    val r = kind.sizeFraction() * w / 2f
                    val on = kind == selected
                    val drive = if (on) (level?.invoke(kind, left) ?: 0f).coerceIn(0f, 1f) else 0f
                    val pulse = 1f + 0.05f * drive * sin(t / 70f + i * 1.7f)

                    if (on) {
                        for (g in 3 downTo 1) {
                            drawCircle(
                                accent.copy(alpha = 0.05f + 0.10f * (4 - g) / 3f),
                                r * 1.08f + r * 0.05f * g, c, style = Stroke(r * 0.16f * g),
                            )
                        }
                        drawCircle(accent, r * 1.1f, c, style = Stroke(maxOf(2f, r * 0.07f)))
                    }
                    scale(pulse, pivot = c) {
                        val a = if (on) 1f else 0.5f
                        when (kind) {
                            SpeakerKind.TWEETER -> drawTweeter(c, r, a)
                            SpeakerKind.MID -> drawSpeaker(mid, c, r, a)
                            SpeakerKind.WOOFER -> drawSpeaker(woofer, c, r, a)
                        }
                    }
                    if (on) {
                        label(kind, left)?.let { text ->
                            val layout = measurer.measure(
                                text,
                                TextStyle(fontSize = CarUi.MinDenseText, fontWeight = FontWeight.Medium, color = Color.White),
                            )
                            val m = (c + labelAnchor) / 2f
                            val pw = layout.size.width + layout.size.height * 0.8f
                            val ph = layout.size.height * 1.15f
                            drawRoundRect(Color(0xE0060708), Offset(m.x - pw / 2, m.y - ph / 2), Size(pw, ph), CornerRadius(ph / 2))
                            drawRoundRect(accent, Offset(m.x - pw / 2, m.y - ph / 2), Size(pw, ph), CornerRadius(ph / 2), style = Stroke(1.5f))
                            drawText(layout, topLeft = Offset(m.x - layout.size.width / 2f, m.y - layout.size.height / 2f))
                        }
                    }
                }
            }

            title?.let { text ->
                val layout = measurer.measure(
                    text,
                    TextStyle(fontSize = CarUi.MinText, fontWeight = FontWeight.Bold, color = accent),
                )
                val padX = layout.size.height * 0.7f
                val padY = layout.size.height * 0.28f
                val bw = layout.size.width + padX * 2f
                val bh = layout.size.height + padY * 2f
                val bx = w * 0.02f
                val by = h * 0.03f
                drawRoundRect(accent.copy(alpha = 0.16f), Offset(bx - 3f, by - 3f), Size(bw + 6f, bh + 6f), CornerRadius(bh / 2f + 3f), style = Stroke(6f))
                drawRoundRect(Color(0xF0060708), Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2f))
                drawRoundRect(accent, Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2f), style = Stroke(1.5f))
                drawText(layout, topLeft = Offset(bx + padX, by + padY))
            }
        }
    }
}

private fun DrawScope.drawSpeaker(image: ImageBitmap, c: Offset, r: Float, alpha: Float) {
    drawImage(
        image,
        dstOffset = IntOffset((c.x - r).roundToInt(), (c.y - r).roundToInt()),
        dstSize = IntSize((r * 2).roundToInt(), (r * 2).roundToInt()),
        alpha = alpha,
    )
}

/** Sail-panel tweeter: chrome ring, black surround, glossy dome. */
private fun DrawScope.drawTweeter(c: Offset, r: Float, alpha: Float) {
    drawCircle(
        Brush.linearGradient(
            listOf(Color(0xFFD8D8DC), Color(0xFF3A3A3E), Color(0xFFB8B8BC)),
            start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r),
        ),
        r, c, alpha,
    )
    drawCircle(Color(0xFF08080A), r * 0.78f, c, alpha)
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF9EA2AA), 0.35f to Color(0xFF30333A), 1f to Color(0xFF0C0D10),
            center = Offset(c.x - r * 0.22f, c.y - r * 0.25f), radius = r * 0.62f,
        ),
        r * 0.62f, c, alpha,
    )
}
