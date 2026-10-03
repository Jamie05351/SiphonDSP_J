# Native BMW virtual channels: virtual-source stage (virtual centre first)

Adds channels that don't physically exist. The head unit has exactly two physical outputs
(`RootlessAudioProcessorService` opens `CHANNEL_OUT_STEREO`; `processFrame()` ends in
`sumToStereo()`; the factory amp splits L/R to the six drivers), so a virtual channel is:

1. **derived** from the stereo signal inside the engine,
2. **processed on its own** (level, per-side delay, polarity, all-pass), and
3. **fed back** into the internal L and R chains before routing, so it then goes through every
   band's crossover/PEQ/delay/all-pass like the rest of that side.

The first virtual source is the **centre**, for the multi-seat (both front seats) tune: centre
content (vocals, dialogue) normally collapses towards whichever door speaker is nearer, and time
alignment can't fix that for two seats at once (`SpeakerGeometryMath.targetDistanceCm(MULTI)`
leaves each seat half its L/R gap off centre). Giving the centre its own per-side timing and
phase lets it be steered and decorrelated without moving the instruments.

## Signal path

```text
DC -> input PEQ -> headroom -> MBC
  -> [NEW] VirtualSourceStage (skipped entirely while disabled -- how it ships)
       C        = w * (L + R) / 2                     (CentreExtractor, zero latency)
       L_res    = L - C,  R_res = R - C
       L'       = sideGain * L_res + centreGain * feedL(C)
       R'       = sideGain * R_res + centreGain * feedR(C)
  -> routing_.process() -> band chains -> polarity/mute -> sumToStereo -> tilt ... (unchanged)
```

`feedL`/`feedR` are a `VirtualFeed` each: gain, polarity, fractional delay (0-10 ms,
`StageDelay`), and one optional first/second-order all-pass section
(`NativeBmwRouting::AllPassSection`, the same design the per-output all-pass uses).

### Transparency contract

With the stage enabled and every control neutral (all levels 0 dB, delays 0, polarity normal,
all-pass off) the output equals the input up to float rounding: `L' = (L - C) + C = L`. With the
stage disabled it is skipped, so the output is **bit-identical** to before this feature. Both are
covered by native tests.

## The centre extractor

Zero-latency adaptive matrix, per sample:

- A **detector sidechain** band-limits L/R (2nd-order Butterworth HPF + LPF, default 150 Hz -
  8 kHz). It only *measures*; the audio path itself is never filtered, which is what keeps the
  transparency contract.
- One-pole smoothed powers `E[L^2]`, `E[R^2]`, `E[LR]` over a fixed 25 ms window (long enough
  to cover a few periods at the 150 Hz default HPF, short enough to follow a vocal entering).
- Raw centre weight `w = clamp(2 E[LR] / (E[L^2] + E[R^2]), 0, 1)`: 1 for a centred mono
  source, 0 for uncorrelated or anti-phase content, and it falls with L/R level imbalance
  (a hard-panned source has `E[LR] = 0`).
- `w` is smoothed with separate attack/release times to avoid pumping and zipper noise.
- Every state update is flushed with `NativeBmwRouting::flushDenormal`; a non-finite input
  yields a zero centre for that sample (and resets the detector), mirroring
  `RoutingMatrix::process()`.

Adding another source later (mono sum, ambience/side, rear) is a new `VirtualSourceId`, an
extractor, and a new tail block -- nothing in the band chains changes.

## Index table (tail growth 266 -> 288)

Same rule as every prior growth: every new persisted slot goes at the **tail**; no existing
offset moves. Mirrored in `NativeBmwDspSchema.h` (`kVirtual*`) and `NativeBmwDspValues.kt`.

| Index | Name | Range | Default |
|---|---|---|---|
| 266 | `INDEX_VIRTUAL_ENABLED` | 0/1 | 0 |
| 267 | `INDEX_VIRTUAL_DETECT_HPF` | 20 - 1000 Hz | 150 |
| 268 | `INDEX_VIRTUAL_DETECT_LPF` | 1000 - 20000 Hz | 8000 |
| 269 | `INDEX_VIRTUAL_ATTACK` | 1 - 500 ms | 10 |
| 270 | `INDEX_VIRTUAL_RELEASE` | 10 - 2000 ms | 200 |
| 271 | `INDEX_VIRTUAL_CENTRE_LEVEL` | -24 - +6 dB | 0 |
| 272 | `INDEX_VIRTUAL_SIDE_LEVEL` | -24 - +6 dB | 0 |
| 273..279 | centre feed **Left** | see feed fields | |
| 280..286 | centre feed **Right** | see feed fields | |
| 287 | `INDEX_VIRTUAL_MIGRATED` | Kotlin-only marker | |

Feed fields (`virtualFeedIndex(side, field)`, width 7, Left first):

| Field | Meaning | Range | Default |
|---|---|---|---|
| 0 | gain | -24 - +6 dB | 0 |
| 1 | delay | 0 - 10 ms | 0 |
| 2 | polarity inverted | 0/1 | 0 |
| 3 | all-pass enabled | 0/1 | 0 |
| 4 | all-pass frequency | 20 - 20000 Hz | 1000 |
| 5 | all-pass Q | 0.1 - 30 | 0.7071 |
| 6 | all-pass order | 1 = 1st, 2 = 2nd (same encoding as the per-output all-pass) | 2 |

`configure()` rejects the whole update on any non-finite slot (same `clampIn`/`flagIn`
discipline as the rest of the array), and requires the detector HPF to sit below the LPF.
An all-pass section that can't be built at the current sample rate degrades to identity, as the
per-output all-pass already does.

`migrateVirtualIfNeeded()` forces the stage **off** once for existing saves (template:
`migrateMbcIfNeeded()`), so nobody's sound changes until they turn it on.

## Two-seat centring, in terms of these controls

- **Both seats:** delays 0, a complementary all-pass pair (e.g. Left 2nd-order at ~700 Hz,
  Right 2nd-order at ~2.5 kHz) so the centre reaches the two sides with a frequency-dependent
  phase difference. This decorrelates the centre between the doors, so neither seat locks onto
  its nearer speaker through the precedence effect. Exact defaults are settled by ear on the
  head unit (Phase 4).
- **Driver:** delay the near-side centre feed by the driver seat's L/R path difference from
  `SpeakerGeometryState`, which centres the vocal for the driver without moving the band.
- **Side level** below 0 dB focuses the image on the centre; above 0 dB widens it.

## Live meter

`readVirtualMeter()` -> 2 floats: `[centreWeight (0..1), centreRmsDb]`, lock-free (atomics
published from the audio thread), same discipline as `readCompressorMeter()`. Idle `[0, -60]`
while disabled.

## Not modelled in the response graph

The extraction is signal-dependent (it depends on how correlated the music is), so like the old
mono-bass blend it has no fixed transfer function and `BmwResponseCalculator` doesn't model it.

## Phases

0. This doc.
1. Native `NativeBmwVirtual.h/.cpp`, schema constants, `configure()` read/validate, the
   `processFrame()` hook, the meter, native tests. No UI.
2. Kotlin schema, migration, store, JNI meter, JVM tests.
3. CENTRE page (Gains/Delay pager, next to ALIGN; `VirtualCentreScreen`) with Both seats /
   Driver / Custom presets (`VirtualCentrePreset`), centre/side level, per-side delay, the
   spread all-pass pair and a live "centre found" meter. Detector band and attack/release keep
   their defaults and aren't on the page.
4. On-device tuning on the head unit to settle the spread defaults. As with every
   `processFrame()` change, physical head-unit sign-off before release.
