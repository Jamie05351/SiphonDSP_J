# Native BMW output architecture: 2-way → 3-way crossover (Low/Mid/High)

Extends the four-output-channel architecture in `docs/NATIVE_BMW_OUTPUT_ARCHITECTURE.md` with a
third band (High), and turns Mid from HPF-only into a true bandpass. Ships on a dedicated branch
(`feat/3way-crossover`, off `master`), landed as a sequence of small, independently verified PRs
against that branch — see the approved implementation plan for the full phase breakdown. This doc
is Phase 0: it nails down the exact index table and the one rule every later phase must not
violate.

## Why a third band needs more than "just add two outputs"

Today, Low is LPF-only and Mid is HPF-only — there is no upper bound on Mid, so everything above
the single split frequency lands in Mid unfiltered at the top end. A proper 3-way crossover needs
Mid to become a bandpass (HPF at the Low/Mid corner, LPF at the new Mid/High corner) while Low
stays LPF-only and the new High band becomes HPF-only at the upper corner. That's a filter-topology
change to `OutputRuntime`, not just more entries in an array.

## The one rule: `kOutputCount` growing 4→6 must not move any existing schema offset

`kRoutingBase=46`, `kAllPassBase=54`, `kOutputConfigBase=87`, `kOutputConfigWidth=13` are today
*derived* from `NativeBmwRouting::kOutputCount`. If `kOutputCount` becomes 6 and those stay
derived, every index from 87 onward — the entire 139→205 tail (measurement-mute stopband, stage
delay, the multiband compressor, per-bus limiters, the master limiter, the measurement generator)
— silently shifts, and every existing user's saved config is misread on their next launch. That
is not acceptable.

**Fix:** `kLegacyOutputCount = 4` is a separate constant. `kRoutingBase`, `kAllPassBase`,
`kOutputConfigBase`, `kOutputConfigWidth` are derived from `kLegacyOutputCount`, not from
`kOutputCount`, and stay pinned at 46/54/87/13 forever. `kOutputCount` becomes 6 for in-memory
runtime array sizing only (`outputs_`, `outputConfigs_`, `outputDynamics_`, `RoutingMatrix`) —
none of that is persisted directly; it's rebuilt from the flat float array on every `configure()`
call. Every new persisted byte goes at the **tail**, starting at index 205, exactly like every
prior growth (46→86→139→205).

## Index table (final numbers for this growth — supersedes any earlier draft)

Existing schema is unchanged, indices `0..204`, `SIZE` currently `205`.

### Phase 2 — Mid upper crossover corner (tail growth #1: `205..209`, `SIZE` 205 → 210)

Two-output sub-block (Mid Left, Mid Right only — Low and High don't use it), width 2:

| Index | Name | Meaning |
|---|---|---|
| 205 | `INDEX_MID_UPPER_XO` (Mid Left, field 0) | Mid Left's upper (Mid/High) corner, Hz |
| 206 | `INDEX_MID_UPPER_XO` (Mid Left, field 1) | Mid Left's upper corner enabled (0/1) |
| 207 | `INDEX_MID_UPPER_XO` (Mid Right, field 0) | Mid Right's upper corner, Hz |
| 208 | `INDEX_MID_UPPER_XO` (Mid Right, field 1) | Mid Right's upper corner enabled (0/1) |
| 209 | `INDEX_MID_UPPER_XO_MIGRATED` | Kotlin-only migration marker |

`midUpperXoIndex(output, field)` helper, separate from `outputIndex()` — mirrors the existing
`subsonicEnabled` pattern (a bool gate + a Hz field, defaulting to disabled so an old save's Mid
stays byte-identical HPF-only until a user turns the upper corner on). No legacy scalar mirror is
needed — the UI writes both L and R directly via a `midUpperPair(field)` helper, the same way
`lowPair()`/`midPair()` already write today's crossover fields directly, never through the
`INDEX_LOW/MID_CROSSOVER_FREQ` legacy scalars (those exist only to seed migration).

Native: `OutputRuntime` gains `crossover3`, `crossover4` (`Biquad`). `rebuildMidCrossover()`
builds the existing HPF pair into slots 1/2 unconditionally (unchanged), then — only if the
upper-corner enabled flag is set — builds an LPF pair into slots 3/4 at the upper corner Hz, using
the same `crossoverType` switch table already used for Low/Mid. `processMidCrossover()` cascades
through slots 3/4 only when enabled.

### Phase 3 — High band (tail growth #2: `210..261`, `SIZE` 210 → 262)

| Index | Name | Meaning |
|---|---|---|
| 210 | `INDEX_HIGH_XO_PASS` | Global bypass — skip the entire High band chain (mirrors `INDEX_LPF_PASS`/`INDEX_HPF_PASS`) |
| 211 | `INDEX_HIGH_GAIN_L` | |
| 212 | `INDEX_HIGH_GAIN_R` | |
| 213 | `INDEX_HIGH_DELAY_L` | |
| 214 | `INDEX_HIGH_DELAY_R` | |
| 215..218 | routing sub-block | High Left `[fromFrontL, fromFrontR]`, High Right `[fromFrontL, fromFrontR]` |
| 219..234 | all-pass sub-block | 2 outputs × 2 sections × `[enabled, order, freq, q]` |
| 235..260 | output-config sub-block | 2 outputs × 13-wide (same layout as `OUTPUT_CONFIG_WIDTH`: crossoverFreq [High's HPF corner, mirrors Mid's upper-corner value], crossoverType, subsonicEnabled/Freq [carried but ignored, same as Mid today], mute, invert, compressor 7-tuple) |
| 261 | `INDEX_HIGH_BAND_MIGRATED` | Kotlin-only migration marker — seeds High **muted/disabled by default** (same conservative opt-in launch as the multiband compressor and per-bus limiters) |

`highOutputIndex(output, field)` — a separate helper from `outputIndex()`, which stays
`OUTPUT_COUNT=4` forever per the offset-freeze rule above. High Left/Right get their own `OutputId`
values (`4`, `5`) for native runtime purposes only; nothing about that numbering is persisted.

Native: `configure()` reads the new tail offsets into `outputs_[HighLeft/HighRight]`,
`outputConfigs_[HighLeft/HighRight]`. `rebuildHighCrossover()`/`processHighCrossover()` mirror
Mid's original HPF-only code (before Phase 2's bandpass addition) at the shared upper corner.
`processFrame()` gets a High branch parallel to Low/Mid; `sumToStereo()` gains a third term.
`captureTruthSnapshot()`'s output block count grows 4 → 6.

### Phase 4 — High-band PEQ (no `SIZE` growth)

PEQ bands are a separate ABI (`configurePeq(enabled, preampDb, fullBands, lowBands, midBands, ...)`
over `double[]`, not the flat `float[205..262]` config array). High-band PEQ adds a 4th
`(const double*, size_t)` pair to that call and to `BmwPeqState`'s own persistence — no index table
entry here, but the same "never break an old save" discipline applies to `BmwPeqState`'s backup
format.

## The "3-way on/off" master toggle

Not a new persisted index. The UI writes to both existing flags at once: off = every output's
`midUpperXoIndex(output, 1)` set to 0 **and** `INDEX_HIGH_XO_PASS` set to 1 — which is exactly
today's 2-way behavior, bit-identical. On = both cleared/enabled per the user's saved band
settings.

## Persistence and migration

Same discipline as every prior growth: `NativeBmwDspStore` keys by index, so an old (210- or
205-length) save simply leaves the new tail at `DEFAULTS` — Mid upper corner disabled, High muted.
`migrateMidUpperCrossoverIfNeeded()` and `migrateHighBandIfNeeded()` follow the exact template of
`migrateMbcIfNeeded()`/`migrateMasterLimiterIfNeeded()`: gated by their own marker, force the
feature OFF for existing users even if a later default changes, never touched again once the
marker is set.

## Realtime safety

Unchanged from the existing architecture doc's guarantees: no allocation/locking/file access in
`processFrame()`/`process()`; all new coefficients only (re)built from `configure()`/
`setSampleRate()`; `configure()` rejects a bad update in full rather than partially applying it,
for every new field exactly as for the existing ones.

## Validation

Each phase adds native + JVM tests widening the existing suites (`native-tests`,
`NativeBmwSchemaAgreementTest.kt`, `NativeBmwDspStoreTest.kt`, `BmwSignalChainModelTest.kt`) and
requires physical BMW head-unit verification before merge — this project's history of
emulator-passed/head-unit-broken regressions (and the post-#340 audio outage) makes emulator-only
sign-off unacceptable for anything touching `processFrame()`'s actual filter output. See the full
phase breakdown in the approved implementation plan for what each phase's tests must cover.

## Remaining limitations (carried over, plus new ones from this growth)

- All-pass and routing UI stays restrained (per-preference-screen controls, not a patch bay),
  same as the existing 4-output model.
- High-bus brick-wall limiter, and extending measurement-mute / the measurement generator's
  Acoustic Timing Reference split to a third band, are explicitly deferred — scoped as their own
  follow-up, not part of this growth.
