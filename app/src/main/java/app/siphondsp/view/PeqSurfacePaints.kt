package app.siphondsp.view

import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint

/**
 * Every configured [Paint], colour and the per-band palette [ParametricEqSurface] draws with,
 * lifted out of the view's body verbatim so its ~330 lines of declarative paint setup no longer
 * sit between the reader and the actual behaviour. Field names are unchanged; the view now
 * reaches them as `paints.<name>`.
 *
 * [themeTextColor] / [themeAccentColor] are the two `?android:attr` colours the view resolves
 * once (via its own `themeColor(...)`) and hands in, so this class needs no `Context`.
 */
class PeqSurfacePaints(
    private val density: Float,
    themeTextColor: Int,
    themeAccentColor: Int,
) {
    // Dry (pre-DSP) reference trace -- a solid, clearly readable line. It's the "before" anchor
    // the eye holds the live wet trace against, so a barely-there dashed hint defeated the point;
    // the wet trace is drawn brighter and with a glow on top, so this still reads as secondary.
    val dryStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeTextColor
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        alpha = 205
    }
    // Shaded gap between the dry and wet traces: the app's brand green where the filters are
    // adding energy at that frequency right now, brand red where they're cutting it. This is
    // what actually shows what the filters are doing to the live audio, rather than two
    // independently overlaid lines -- so it's drawn with real weight, not a faint wash.
    val spectrumBoostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_GREEN
        style = Paint.Style.FILL
        alpha = 125
    }
    val spectrumCutFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BmwDashboardSkin.M_RED
        style = Paint.Style.FILL
        alpha = 125
    }

    // No solid background fill any more (see drawUnifiedSystem) -- the workspace's own designed
    // background shows through instead. bgBottomColor survives as nodeRingPaint's stroke colour
    // below, a leftover dark tone that still reads correctly against either background.
    val bgBottomColor = Color.rgb(13, 13, 15)
    val unifiedGridColor = Color.rgb(58, 60, 66)
    val unifiedTextColor = Color.rgb(176, 178, 186)
    // Neon revision of the existing colour map: same identities (Full = white, Low = blue,
    // Mid = yellow/amber, sum = white) but pushed to full-chroma so the thin sharp lines below
    // actually read on the dark workspace instead of sitting there as dull grey-ish traces.
    val bankColorFull = Color.rgb(255, 255, 255)
    val bankColorLow = Color.rgb(0, 209, 255)
    val bankColorMid = Color.rgb(255, 224, 0)
    val sumColor = Color.rgb(255, 255, 255)

    // Per-band palette (FabFilter Pro-Q-style): each filter gets its own colour, cycling by its
    // GLOBAL number (Input Correction, then Low, then Mid -- see bankNumberOffset), so no two
    // filters on the graph share a colour until there are more than perBandPalette.size of them.
    // The SAME colour is used for that filter's node dot, its isolated-response overlay line,
    // its shaded fill, and its tap-info card, so "this dot" and "this shape" are unmistakably the
    // same filter. Neon, to match the line revision above.
    val perBandPalette = intArrayOf(
        Color.rgb(255, 23, 68),   // red
        Color.rgb(224, 64, 251),  // violet
        Color.rgb(41, 121, 255),  // blue
        Color.rgb(0, 230, 118),   // green
        Color.rgb(255, 145, 0),   // orange
        Color.rgb(0, 229, 255),   // cyan
        Color.rgb(255, 64, 129),  // pink
        Color.rgb(198, 255, 0),   // lime
    )

    val unifiedGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = unifiedGridColor
    }
    val unifiedZeroPaint = Paint(unifiedGridPaint).apply { strokeWidth = 1.6f * density; alpha = 200 }
    val unifiedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = unifiedTextColor
        textSize = 9.5f * density
    }
    val unifiedLegendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = unifiedTextColor
        textSize = 10.5f * density
    }
    val crossoverShadePaint = Paint().apply {
        style = Paint.Style.FILL
        color = unifiedTextColor
        alpha = 18
    }
    // Per-band filled region + its outline (see drawPerBandFills) -- color AND alpha are both
    // set per band on every draw call (setColor() overwrites alpha too), so nothing meaningful
    // is configured here beyond style/stroke width.
    val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val bandStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    // Scratch paint for the neon "bloom" under every curve: the real line is drawn thin and
    // sharp, then this is stroked wide and translucent beneath it (configured per call in
    // [ParametricEqSurface.strokeNeon]) so a 1px line still reads as glowing rather than
    // hairline-faint.
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    val lowBranchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = bankColorLow
    }
    val midBranchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = bankColorMid
    }
    // Right-channel branch curves: same colour, dashed -- matches the solid-L / dashed-R
    // convention the sum curve and the right-only band nodes already use.
    val lowBranchPaintDashed = Paint(lowBranchPaint).apply {
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    val midBranchPaintDashed = Paint(midBranchPaint).apply {
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    val sumPaintSolid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * density
        color = sumColor
    }
    val sumPaintDashed = Paint(sumPaintSolid).apply {
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    // MAGNITUDE_PHASE overlay: same colour family as the sum curve it accompanies, thinner and
    // dashed so it reads as an annotation on its own implicit degree scale, not a second sum.
    val sumPhaseOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = sumColor
        alpha = 170
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    // Light blue (matches the app's accent colour) -- the grey used everywhere else in this
    // unified view reads as barely-visible background noise for a live spectrum trace.
    val spectrumAccentColor = Color.rgb(79, 195, 247)
    // Vertical gradient under the wet trace: accent near the trace fading to nothing toward the
    // floor, so the fill gives the trace body without flattening into a solid slab. The shader is
    // rebuilt in drawUnifiedSpectrum whenever the plot's top/bottom change.
    val unifiedSpectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = spectrumAccentColor // fallback until the gradient shader is installed
    }
    var spectrumFillShaderTop = Float.NaN
    var spectrumFillShaderBottom = Float.NaN
    val unifiedSpectrumStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = spectrumAccentColor
        alpha = 190
    }
    val unifiedOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        alpha = 90
    }
    val unifiedOverlayDashEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    val nodeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val nodeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = bgBottomColor
    }
    val nodeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * density
    }
    // Tapped-node info card (see drawInfoCard): a dark rounded panel, edged in that band's own
    // palette colour, holding its type / freq / gain / Q / channel / bank for a few seconds.
    val infoCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(18, 19, 22)
    }
    val infoCardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
    }
    val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 234, 240)
        textSize = 10f * density
    }
    val tiltHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeAccentColor
    }
    val tiltHandleDimPaint = Paint(tiltHandlePaint).apply { alpha = 100 }
    val tiltLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = unifiedTextColor
        textAlign = Paint.Align.LEFT
        textSize = 9.5f * density
    }
    val meterTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = unifiedGridColor
    }
    val meterRmsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bankColorLow
    }
    val meterPeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = sumColor
    }
    val meterHoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = sumColor
    }
    val meterLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = unifiedTextColor
        textAlign = Paint.Align.CENTER
        textSize = 8.5f * density
    }
}
