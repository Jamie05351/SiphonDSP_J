# Parametric EQ — Compose port sub-plan (roadmap Phase 10)

The dedicated sub-plan the roadmap's Phase 10 calls for. By now every other DSP
workspace screen is Compose (`compose/screens/{TonalityTilt, HeadroomOutput,
GainsDelay, OutputAllPass, CompressorScreens, CrossoverPagesScreens}`), the
component set is proven, and `BmwDspState` established the "screen owns hoisted
state + a single mutation funnel" shape. The PEQ screen is the last port and the
biggest single one.

**This revision bakes in the scope decisions from 2026-09-09** (see §7).

## 1. What's there today

`ParametricEqualizerFragment` — ~1020 lines — plus ~14 supporting files
(`Peq*`, `BmwPeq*`, `ParametricEqSurface` + its `PeqGraphMath` / `PeqPlotGeometry`
/ `PeqSurfacePaints`), ~3000 lines total. It uses:

- **ViewBinding** (`FragmentParametricEqBinding`) + a `RecyclerView`
  (`ParametricEqBandAdapter`, `item_peq_band_list*.xml`) for the filter list.
- **`ParametricEqSurface`** — a ~1100-line custom `View`: spectrum + magnitude /
  phase / group-delay curves, per-band nodes, node-tap selects the band.
  Drag-to-adjust on the graph was already removed upstream ("it only ever nudged
  filters by accident") — nodes are **tap-only** already.
- **`cards_pager`** — a landscape-only `ViewPager` swiping between a **GRAPH** and
  a **LIST** page; portrait stacks them.
- **State:** `peqState: BmwPeqState` (three band lists — `fullRangeBands` /
  `lowBandBands` / `midBandBands` — plus `preampDb`), `selectedScope`
  (`PeqScope.FULL/LOW/MID`), `selectedBandByScope: Map<PeqScope, UUID?>`,
  `history: PeqStateHistory` (undo/redo).
- **`applyCandidate(candidate, source, recordHistory, preserveScroll)`** — the
  single mutation funnel: `copy(enabled = true)` → `validate(sampleRate)` (toast
  + bail on failure) → if `nativeBmwPeqHandleReady() == true`
  `applyNativeBmwPeq(candidate)` else `candidate.persist(context)` → toast on a
  rejected result → push to `history` → `bindScope()`.
- **Action chips:** scope (Pre EQ / Low Band / Mid Band), Reset, Import file,
  Export file, Edit as string, Graph options, Undo, Redo, Filter tools,
  Preset export, Preset import, Diagnostics, Backup export, Backup import.
- **Filter tools** popup (`showFilterTools` + `PeqBandEditor`): Duplicate
  selected, Move up / Move down (this is the list-reorder mechanism), Copy
  selected → other scope, Copy all → other scope, Copy all L→R / R→L, Split Both
  into L + R.
- **File I/O** via `registerForActivityResult`: APO/REW `.txt` multi-import
  (`PeqApoImport` + `ApoImportRouter` route by filename), `.txt` export, JSON
  preset export/import (`BmwPeqPreset`), private backup export/import
  (`PrivatePeqBackup`), diagnostic-report export.
- **Dialogs:** `PeqDialogs(context, layoutInflater)` — fragment-independent
  already: value input (frequency / gain / Q), filter-type picker, channel
  picker, yes/no confirms.
- **`BroadcastReceiver`** for `ACTION_NATIVE_BMW_DSP_UPDATED` and backup-restore
  results.

The "unsaved edit guard" the comments mention (`canSwitchDspScreens()`) is a stub
that always returns `true` — nothing to port.

## 2. Keep as `AndroidView` (interop, not port)

`ParametricEqSurface` stays a `View`, hosted via `AndroidView`. Same decision as
`CompressorSurface` / `CrossoverHandoffSurface` / `MbcBandGrMeter` — a large
precision drag surface, no functional benefit from a rewrite, real regression
risk (the memory records its Compose migration was dropped twice).
`PeqGraphMath` / `PeqPlotGeometry` / `PeqSurfacePaints` come along as its
internals. Bridge:

- **In:** `peqState`, the selected band UUID, the channel-display / curve-mode
  enums, and a new `nodeAlpha: Float` (§4).
- **Out:** `onNodeTapped(band, anchorPx)` → Compose shows a detail callout (§4)
  **and** the state holder sets `selectedBandByScope[scope]`.

Small additions to `ParametricEqSurface`: a `setNodeAlpha(Float)` setter that
scales the node draw alpha (hit-testing stays active while faded), and the
`onNodeTapped` callback carrying the tapped node's screen position.

`PeqDialogs` also stays **interop** — it's already a fragment-independent class
taking `context` + `LayoutInflater`; call it from Compose click handlers (same
pattern as `showBmwNumberInput`). Porting it to Compose `AlertDialog` is a
possible later cleanup, not Phase 10.

## 3. State holder

PEQ's state is typed (`BmwPeqState`), not the `FloatArray` `BmwDspState` model,
with its own persistence. It gets its own holder:

```
rememberPeqState(): PeqStateHolder
```

- Fields: `peqState` (`mutableStateOf<BmwPeqState>`), `selectedScope`
  (`rememberSaveable`), `selectedBandByScope`.
- **`applyCandidate(candidate, source)`** — moved in near-verbatim, minus
  everything undo-related (§7): validate → native push or persist → toast on
  fail. `bindScope()` disappears — the visible list is a *derived* value
  (`bandsForScope(peqState, selectedScope)`), recomputed on read.
- Ops that today call `applyCandidate`: `add`, `reset` (scope-aware),
  `commitBandEdit(index, source, transform)`, and every filter-tools op via
  `PeqBandEditor` (all already return a candidate + a "select" uuid).
- Derived: `visibleBands`, `selectedUuid`, `scopeAccent`.
- The `RootlessAudioProcessorService` PEQ calls stay exactly as-is.

**Sizing: medium** (was "large" — undo history and its coalescing are gone).

## 4. Graph node behaviour (new — from the 2026-09-09 direction)

- **Tap a node → detail callout.** The surface reports `onNodeTapped(band,
  anchorPx)`; Compose renders a small `Popup` near that point showing the band's
  frequency / gain / Q / filter type / channel. Dismisses on outside tap or after
  a few seconds. The tap still selects the band (list-row highlight +
  `animateScrollToItem`).
- **Nodes fade after 6s idle.** Compose owns the timer: an interaction counter
  (bumped on node tap, any filter edit, scope switch) drives a
  `LaunchedEffect(interactionTick)` that waits 6s then animates `nodeAlpha`
  1 → 0 over ~400ms; each interaction resets it to 1. `nodeAlpha` is pushed into
  the surface via `setNodeAlpha`. The curves themselves don't fade — only the
  draggable/tappable node dots — so the response shape stays readable while the
  clutter recedes. Hit-testing for node tap stays live while faded.

## 5. The filter list

`LazyColumn(key = { it.uuid })` — a header row, an "add band" row, and per-band
rows (frequency / gain / Q / type / channel), each cell tap-to-edit via
`PeqDialogs` interop. Row styling from `item_peq_band_list*.xml`.

- **No drag-to-reorder.** Filter tools' "Move up / Move down" is the reorder
  mechanism and stays; a reorderable `LazyColumn` (no first-party support, the
  plan's old top risk) is not needed.
- Selected-band highlight + `LazyListState.animateScrollToItem` on node tap;
  `rememberSaveable` for scroll position (no `preserveScroll` plumbing).

## 6. Sub-phases

Each a reviewable PR that builds and (from 10c on) runs.

### 10a — `PeqStateHolder` + scope
`rememberPeqState()` + `applyCandidate` + `add` / `reset` / `commitBandEdit` +
the `PeqBandEditor` ops + derived values, and the **scope segmented control**
(`BmwSegmentedControl`, 3 options — already built). Tiny harness / `@Preview`
exercises add / edit / scope switch / reset. **Medium.**
- *Investigate first:* exact `bindScope` side effects beyond deriving the list
  (`previewTitle` text, graph re-bind ordering).

### 10b — the filter list
The keyed `LazyColumn` (§5) + rows + `PeqDialogs` interop for tap-to-edit +
selected highlight + scroll-to-selected. **Large.**

### 10c — graph + graph options + node behaviour
`AndroidView(ParametricEqSurface)` wired to the holder, the `setNodeAlpha` fade
timer + tap-detail callout (§4), and the graph-options menu (channel display /
curve mode) as a Compose `DropdownMenu`. First runnable milestone: graph + list +
scope + edits landing. **Medium–large.**

### 10d — assemble `ParametricEqScreen` + wire the fragment
Graph/List mode toggle (`BmwSegmentedControl` GRAPH/LIST + `AnimatedContent`).
`ParametricEqualizerFragment` → `FrameLayout` + `ComposeView`; **delete
`fragment_parametric_eq.xml` and every portrait code path** (`collapsePreview`,
the weighted-chain code, `ORIENTATION_LANDSCAPE` branching) — portrait is dead
weight on the 1280×480 head unit. Keep `activity_parametric_eq.xml`. **Medium.**

### 10e — file I/O + reset wording
`rememberLauncherForActivityResult` for: APO/REW `.txt` `OpenMultipleDocuments`
(→ `PeqApoImport.handleApoImport`), `.txt` `CreateDocument` export, private
backup `CreateDocument` / `OpenDocument` (`PrivatePeqBackup`), diagnostic-report
`CreateDocument`. `reset` confirm dialog. Reword the backup-restore confirmation
(drop "This can be undone" — there's no undo). **Medium.**
- `PeqApoImport` / `ApoImportRouter` / `PrivatePeqBackup` are already
  fragment-independent — the `Host` interface needs `context` / `activeScope` /
  `state` / `applyCandidate`, all on the holder.

### 10f — receiver + expanded diagnostics
`DisposableEffect` registering the `ACTION_NATIVE_BMW_DSP_UPDATED` +
backup-restore `BroadcastReceiver`. Plus **expand `PeqDiagnosticReport`** (§8).
**Small–medium.**

## 7. Dropped from the port (2026-09-09 direction)

| Dropped | Consequence |
|---|---|
| **Portrait layout** | Delete `fragment_parametric_eq.xml` + `collapsePreview` + weighted-chain + orientation branching. Landscape-only. |
| **Undo / Redo** | No `PeqStateHistory` in the port. `applyCandidate` loses `recordHistory` + `history.push`; no `performUndo/Redo`, no `refreshActionChips`. Reword the "This can be undone" lines in the reset / backup-restore confirms. |
| **Edit as string** (`chip_edit_string`) | `performEditAsString` gone. `ParametricEqBandList.toApoString` / `fromApoString` stay (file import/export use them). |
| **Preset export / import** (`chip_preset_*`) | `performPresetImport/Export` + launchers gone. `BmwPeqPreset` class stays — `PrivatePeqBackup` uses it. |
| **Drag-to-reorder (list & graph)** | Filter tools' Move up/down is the reorder path. Kills the reorderable-`LazyColumn` risk. |

**Kept action set:** Pre EQ / Low Band / Mid Band (scope), Reset, Import, Export,
Graph options, Filter tools, Diagnostics, Backup export, Restore backup.

## 8. Expanded diagnostics (§10f)

`PeqDiagnosticReport.create` today emits: app version / commit / build type /
build time, Android + device + ABIs + screen, DSP service active, native handle
ready, sample rate, PEQ state format, PEQ enabled, per-bank filter counts, last
restore / fallback / error, last backup restore, last-known-good timestamp, and a
privacy note (no audio, filter values, file contents, paths, usernames, account
ids).

Rename the header to a broader "SiphonDSP DSP diagnostic" and add — all
structural / privacy-safe, keeping the same stance:

- **Per-bank filter-type histogram** — counts by `ParametricEqFilterType`
  (peaking / low-shelf / high-shelf / high-pass / low-pass / …). Structure, not
  values.
- **Per-bank channel breakdown** — how many Left / Right / Both.
- **At-capacity flags** — each bank's filter count vs the per-bank native max.
- **Current-state validation** — run `state.validate(liveSampleRate)` and report
  pass, or which check failed (the single most useful line for "why did my import
  get rejected").
- **Native engine state** — recorder sample rate vs engine sample rate, PEQ
  handle-ready tri-state, and the enable flags for MBC / bus limiters / master
  limiter / tilt / crossover / mono-bass / all-pass sections (from
  `NativeBmwDspValues`) — a full "what is the DSP doing" snapshot.
- **Backup / restore history** — the last N results, not just the most recent
  (needs `BmwPeqState`/`PrivatePeqBackup` to keep a short ring; today it keeps
  one).
- **Persistence health** — existence + byte size + mtime (not contents) of the
  PEQ state file, the last-known-good file, and the newest private backup.
- **Device health** — `ActivityManager.isLowRamDevice` / low-memory flag, free vs
  total heap, available internal storage.
- **Permission / mode** — Shizuku vs rootless vs root, `PROJECT_MEDIA` appop
  state (explains a stalled engine / capture).
- **Timezone offset** — so a reader can interpret the timestamps above.
- **Graph display prefs** — channel display + show-individual-filters (from
  `PeqGraphPreferences`).

**Borderline — decide before implementing:** the **preamp dB value** (a single
config number, arguably fine but the privacy note currently says "no filter
values") and **device locale** (mildly identifying vs. timezone offset which
isn't). Left out of the list above pending a call.

## 9. Non-goals

- No `ParametricEqSurface` Compose rewrite (`AndroidView`).
- No `PeqDialogs` Compose rewrite (interop).
- The analyzer/graph visual polish in `ANALYZER_VISUAL_SPEC.md` is a separate
  later pass, not part of this.

## 10. After Phase 10 — Phase 11 cleanup

Delete `CrossoverDashboardBuilder` + now-dead `BmwSkinDrawables` classes (audit —
some may still back settings-screen XML), replace `DspPager` with a Compose
pager, drop `viewBinding` once no `*Binding` remains, replace `BmwPanel`'s
dp-slack title-column measurement with a `SubcomposeLayout` exact measure, and
audit remaining `AndroidView` wrappers.
