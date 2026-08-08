# Native BMW output architecture: routing, input correction, all-pass

Replaces the fixed low/mid stereo topology in `NativeBmwDspProcessor` with a generic
four-output-channel architecture, while keeping the previous audible behaviour and final
stereo reconstruction bit-identical under default settings.

## Final signal path

```text
stereo capture (l, r)
  -> DC blocker (per physical input side)
  -> input-correction preamp + PEQ, Left/Right              [formerly "Full Range" PEQ]
  -> headroom
  -> routing matrix (Front L/R -> Low L, Low R, Mid L, Mid R)
  -> per output: subsonic LR4 HPF (low only) -> crossover LPF/HPF
  -> mono-bass blend (low outputs only, combines Low L + Low R when enabled)
  -> per-band PEQ (Low bank / Mid bank, shared L/R banks, unchanged from before)
  -> per-output all-pass (up to 2 sections)
  -> per-output delay (unchanged range/values)
  -> per-band dynamics (compressor, shared L/R per band, unchanged)
  -> per-output gain, polarity, mute
  -> reconstruct: Final Left = Low Left + Mid Left, Final Right = Low Right + Mid Right
  -> tilt -> post gain -> limiter
```

Output identifiers are stable and fixed at four: `LowLeft`, `LowRight`, `MidLeft`, `MidRight`
(`NativeBmwRouting::OutputId`). "Left"/"Right" here name the *internal* processing chain
(the one driven by L-suffixed config indices), not the physical speaker side -- native's
`processFrame` still ends with `l = oR; r = oL`, so the internal-left chain ends up on the
physical right output. This was true before this change too; see `BmwSignalChain`'s KDoc on
the Kotlin side for the equivalent explanation.

## Deviations from a literal per-output rewrite

- **PEQ stays per-band, not per-output.** The Low/Mid PEQ editor is still one bank per band
  (matching today's UI), so `lowPeq_`/`midPeq_` remain shared `PeqBank`s with internal L/R
  coefficient lists; each output just calls that bank's `processLeft()`/`processRight()`.
  Only the crossover filter, delay, gain, polarity, mute and the two all-pass sections
  actually live inside each `OutputRuntime`.
- **Mute/polarity are still band-level controls.** The UI exposes one Low invert/mute and one
  Mid invert/mute (not four independent ones), so `OutputRuntime::muted`/`polarityInverted`
  are cached copies driven from the shared `Params` fields via `rebuildPolarityAndMute()`,
  not independently configurable per output yet. The four-output model is structurally ready
  for that later; nothing here blocks it.
- **Routing is not modelled in the response graph.** `BmwResponseCalculator` computes the
  four bands directly (as it always has) rather than through a routing indirection -- a
  divergence there is a display-only concern under non-default routing, not an audible one,
  and default routing reproduces the graph exactly. All-pass *is* modelled in the graph
  (`BiquadCascade.addAllPass`), since that changes the audible response.
- **Mono-bass is not modelled in the response graph**, same as before this change -- it mixes
  L and R together, which doesn't fit a per-channel independent transfer function.

## Persistence and migration

The native config array grew from 46 to 86 floats (`NativeBmwDspValues.SIZE`):
indices 0-45 unchanged, 46-53 the routing matrix (`[Front L, Front R]` per output), 54-85 two
all-pass sections per output (`[enabled, order, frequencyHz, Q]`).

`NativeBmwDspStore` already keys saved state by index rather than position/length (see its
class doc), so an older save simply leaves every new index at `NativeBmwDspValues.DEFAULTS`
-- identity routing, all-pass disabled. No explicit migration step was needed; this was
verified with a dedicated test (`NativeBmwDspStoreTest`) rather than assumed.

A `configure()` call that fails validation (non-finite/out-of-range routing or all-pass
values) is rejected in full -- the native processor keeps its last-known-good in-memory
state. It never partially applies a bad update.

## Realtime safety

- No allocation, locking or file access in `processFrame()`/`process()`.
- All-pass and routing coefficients are only (re)built from `configure()` or
  `setSampleRate()`, never per-sample.
- `RoutingMatrix::process()` and `sumToStereo()` both guard against a non-finite coefficient
  product by falling back to silence for that one output/side, so a malformed routing value
  can't propagate `NaN`/`Inf` into the mix.
- `processFrame()` additionally sanitises the final pre-limiter sum (`ftz(oL)`/`ftz(oR)`)
  before it reaches the limiter and the sample-format conversion -- a defence added while
  investigating a report of audio dying (until session restart) shortly after a stray
  non-finite sample, on top of the existing per-biquad `ftz()` calls.

## Validation

- `:app:testRootlessFullDebugUnitTest` -- 139 tests, all passing (113 pre-existing + 26 new
  covering routing identity/reconstruction/no-crossfeed, all-pass unity magnitude, all-pass
  phase behaviour and centre-frequency crossing, invalid frequency/Q rejection, disabled
  all-pass as identity, and old-save-file routing/all-pass migration).
- `:app:assembleRootlessFullDebug` -- builds clean, including the native CMake/NDK arm64
  build.
- Existing `BmwSignalChainModelTest` suite (pre-existing behavioural regression tests for the
  response model) still passes unmodified in behaviour, only widened to the new 86-value
  array size.

## Remaining limitations

- All-pass and routing UI is intentionally restrained (per-preference-screen controls, not a
  patch bay) -- see `dsp_native_bmw_preferences.xml`.
- Group delay in the response graph is a per-point finite-difference of unwrapped phase, not
  a separately modelled quantity -- expect visible noise near sharp notches, which is why it
  is opt-in rather than always shown.
- Mute/polarity remain band-level (Low/Mid), not independently switchable per exact output;
  see "Deviations" above.
