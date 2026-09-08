# Analyzer/graph visual spec

Implementation-ready detail for the "Visual design direction: analyzer/graph polish" and
"Gains & Delay: car cutout diagram" sections of `COMPOSE_MIGRATION_ROADMAP.md`. Read that
document first for the *why*; this document is the *how*, written so whoever picks up that
roadmap phase can build directly from it without re-deriving decisions already made.

Scope: this is a rendering/visual pass on top of Compose ports that don't exist yet
(`ParametricEqSurface`, `NativeBmwDspResponseView`, the Compressor graph, and the Gains & Delay
car cutout are all still View-based as of this writing). Apply this spec at the point each of
those screens is actually ported to Compose -- don't attempt to retrofit it onto the View
versions.

## 1. Real blur glow (replaces `strokeNeon()`'s fake wide-stroke glow)

Current View-system technique (`ParametricEqSurface.strokeNeon()`): draws the same path a
second time, ~3.4x the stroke width, at ~16% of the original alpha, underneath the crisp line.
Reads as a hard-edged translucent band.

Compose replacement: use `RenderEffect`/`BlurEffect` via `Modifier.graphicsLayer { renderEffect
= ... }`, the same mechanism `BmwSlider`'s focus ring uses (see
`app/src/main/java/app/siphondsp/compose/controls/BmwSlider.kt`, the `focused` branch of
`BmwSliderTrack`). Requires API 31+ (`Build.VERSION_CODES.S`); this app's actual head-unit
target is Android 12 (API 31), so this is fine in practice, but follow the same pattern
`BmwSlider` uses: real blur on 31+, a plain unblurred stroke as fallback below that (minSdk is
29) rather than crashing or omitting the glow entirely.

Suggested blur radius: 6-10dp for the main summed-curve glow (larger than the slider's 4dp
focus ring, since this is a bigger visual element covering more of the screen) -- tune by eye
once it's actually rendering; there's no exact "correct" value being ported from anywhere,
the View version never had real blur to match.

Apply real blur glow to:
- The summed/combined response curve (PEQ, Crossovers & Tilt) -- this is the primary visual
  element, give it the most glow.
- Filter/band node halos (see section 3).

Do NOT apply glow to:
- Individual per-filter overlay curves (`drawFilterOverlays`) -- these stay thin, subtle,
  low-opacity, no glow, so they don't compete with the summed curve. This matches the existing
  View-system intent (see that function's alpha handling: 130 normal / 235 selected), just
  carried forward.
- Grid lines.

## 2. Gradient area fill under the summed curve

New addition -- the View version only strokes the curve, never fills under it. Add a
`Brush.verticalGradient` (or `linearGradient` from the curve's path to the bottom of the graph)
fill from the curve's own color at ~25-35% alpha at the curve's y-position, fading to fully
transparent at the graph's bottom edge (or at 0dB reference line for graphs where that's more
meaningful than the bottom edge -- decide per-graph when implementing; PEQ's gain axis is
symmetric around 0dB so fading toward 0dB rather than the bottom edge likely reads better
there).

Draw this BEHIND the curve stroke and its glow, so the crisp line + glow sit on top of the
fill, not the reverse.

Applies to: PEQ summed curve, Crossovers & Tilt summed curve. Does not directly apply to the
Compressor graph (see section 6, different treatment).

## 3. Filter/band nodes: glass treatment

Current (`ParametricEqSurface.drawBankNodes`): flat `nodeFillPaint` circle, flat low-alpha
`nodeHaloPaint` ring underneath when highlighted, flat `nodeRingPaint` outline for
right-channel bands.

Replacement, modeled on `GlassSwitchThumbDrawable`'s thumb treatment (see
`app/src/main/java/app/siphondsp/view/BmwSkinDrawables.kt` lines ~497-578) but adapted --
that thumb is a fixed on/off status light with only two color states; a PEQ node needs an
arbitrary per-band color instead, so adapt the *technique* (radial gradient fill, blurred glow
ring, crisp ring, highlight arc), not the literal on/off color logic:

- Fill: `Brush.radialGradient`, centered slightly above the node's vertical center (matches
  `GlassSwitchThumbDrawable`'s `circleRect.top + circleRect.height() * 0.42f` convention --
  gives a consistent "lit from above" look across every glass-styled element in the app),
  from a lightened version of the band's color (blend toward white, ~30%) at the center to the
  band's actual color at the edge.
- Real blurred glow ring (section 1's technique) in the band's color, shown when
  highlighted/selected (replaces the flat `nodeHaloPaint`).
- Crisp ring + border on top, same layering as `GlassSwitchThumbDrawable` (`ringPaint` then
  `borderPaint`).
- Small highlight arc (top-left, ~200-270 degrees, matching `GlassSwitchThumbDrawable`'s
  `highlightPath` arc geometry) for the "glass sphere" read.
- Keep the existing right-channel dark ring convention (`nodeRingPaint` equivalent) for
  accessibility/colorblind distinction between L/R bands -- don't drop this, it's solving a
  real readability problem, not decoration.
- Keep the existing node number label (`nodeTextPaint`) and its luminance-based black/white
  contrast logic exactly as-is.

## 4. Grid hierarchy

Current: uniform-weight, uniform-low-alpha grid lines throughout (all three graphs).

Replacement: not all gridlines equal.
- 0dB reference line (PEQ, Crossovers & Tilt): brighter/more opaque than other gridlines --
  roughly 2-3x the alpha of regular gridlines.
- Octave-boundary vertical lines (where the graph marks them, e.g. 100Hz/1k/10k on a log
  frequency axis), if present: slightly brighter than in-between gridlines, not as bright as
  the 0dB line.
- Everything else: recede further than current -- lower the existing alpha somewhat so the
  brightened reference lines actually read as emphasized rather than just "one more line at
  the same weight."

## 5. Panel depth (glass bezel + vignette)

Wrap each graph's container in the same glass-panel treatment already used elsewhere (see
`GlassBoxDrawable` in `BmwSkinDrawables.kt` for the View-system equivalent -- adapt the same
inset-shadow/rim-light technique to Compose rather than porting that class directly, since it's
a general-purpose box and the graphs may need their own sizing/aspect ratio).

Add a subtle vignette: a radial gradient overlay, transparent in the center, darkening toward
the panel's corners/edges (~10-15% additional black at the very corners is a reasonable
starting point -- tune by eye). Purpose is to keep the background from reading as flat, not to
meaningfully obscure the corners.

## 6. Compressor graph: gradient-tinted zones + optional GR trace

Current: flat, hard-edged solid-color rectangular zones per compressor band (see the
uploaded screenshot -- distinct flat blue/purple/olive/red blocks).

Replacement:
- Each band's zone becomes a vertical gradient tint (band color at low alpha near the top,
  fading toward transparent toward the bottom, or vice versa -- match whichever direction
  reads as "energy falling off" for a gain-reduction display, likely fading toward the bottom
  since GR is measured downward from 0).
- Apply the same grid-hierarchy treatment (section 4) on top of the tinted zones.
- If/when the compressor engine exposes live per-band gain-reduction data (check
  `BmwSignalChain`/whatever exposes compressor state at implementation time -- not confirmed
  available as of this spec), draw an actual live GR trace line on top of the static zones,
  with the same real-blur-glow treatment as the PEQ/Crossovers summed curve. If no live GR data
  is available, skip this and ship the static gradient zones alone -- don't fabricate fake
  live-looking motion.

## 7. Spectrum overlay smoothing (PEQ background spectrum)

Current: raw instantaneous spectrum redrawn every frame (see `NativeBmwDspResponseView`'s
`spectrumFillPaint`/`spectrumPaint`, alpha 28/105 -- already fairly subtle, but jitters frame to
frame with no persistence).

Replacement:
- Lower opacity further from current (ambient-context level, not a competing visual element).
- Add basic smoothing: exponential moving average across frames (e.g. `displayed = lerp
  (displayed, raw, 0.3f)` per frame, tune the factor by eye) rather than redrawing raw data
  directly -- removes jitter.
- Add peak-hold: track a per-frequency-bin peak value that only decays slowly (e.g. -X dB per
  second) when the live level drops below it, drawn as a thin separate line/dot above the live
  trace. This is what makes a real spectrum analyzer feel "alive" rather than "animated" --
  the peak markers create a sense of history/momentum the live trace alone doesn't.
- Desaturate the color further (greyer, less saturated than the main curves) so it stays
  visually subordinate.

## 8. Gains & Delay: distance/delay readout overlay (decided direction -- see roadmap)

For each of the 4 speaker positions already marked on the car interior photo:

- Draw a thin line from the speaker's marked position to a single shared "listening position"
  point on the diagram. Use the app's existing ~60/40 driver/passenger-weighted reference
  point (see [[e60-dsp]] project notes on multi-seat tuning philosophy) for where that point
  sits -- do not add a second/driver-only and passenger-only point; one shared reference point
  keeps the diagram legible.
- Label positioned along or at the end of each line, showing:
  - The channel's current delay value in ms (already available per-channel from
    `NativeBmwDspValues` -- same values the Gains & Delay sliders already read/write).
  - The equivalent physical distance the delay represents, computed as
    `distance_mm = delay_ms * SPEED_OF_SOUND_MM_PER_MS`. Use `343000mm/s` (~343 m/s, standard
    dry-air speed of sound at ~20C) as the reference constant -- i.e. `SPEED_OF_SOUND_MM_PER_MS
    = 343f` (343 mm per ms). This is a fixed constant, not temperature-compensated; note that
    simplification in a code comment when implementing rather than silently treating it as
    exact.
- Lines and labels update live as their corresponding Delay slider is dragged -- this needs to
  share state with whatever Compose port of the Gains & Delay sliders exists at that point, not
  be a separate/disconnected readout.
- Explicitly out of scope (per roadmap decision): animated wavefront arcs, and any change to
  the car photo/background art itself. This overlay adds information on top of the existing
  visual, it doesn't replace or restyle it (beyond the general glass-panel/vignette treatment
  from section 5, applied to the surrounding UI panels as it is everywhere else, not to the
  photo itself).

## Summary table

| Graph | Real blur glow | Gradient fill | Glass nodes | Grid hierarchy | Panel depth | Other |
|---|---|---|---|---|---|---|
| PEQ | Yes (summed curve) | Yes | Yes | Yes | Yes | Spectrum smoothing + peak-hold |
| Crossovers & Tilt | Yes (summed curve) | Yes | N/A (no draggable nodes on this graph) | Yes | Yes | -- |
| Compressor | Optional (GR trace, if data available) | Zone tint (different technique, see #6) | N/A | Yes | Yes | -- |
| Gains & Delay car cutout | No | No | No | N/A | Yes (surrounding panels only) | Distance/delay readout lines (#8) |
