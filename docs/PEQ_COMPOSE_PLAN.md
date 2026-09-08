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

## 2. What stays interop vs. gets ported

**`PeqDialogs` — interop.** It's a fragment-independent class taking `context` +
`LayoutInflater`; call it from Compose click handlers (same pattern as
`showBmwNumberInput`). Porting it to Compose `AlertDialog` is a possible later
cleanup, not Phase 10.

**`ParametricEqSurface` (the graph) — ported to Compose Canvas** (10c-i), then
given the `ANALYZER_VISUAL_SPEC.md` treatment (10c-ii). This reverses the
original "keep as `AndroidView`" call: with drag-to-adjust already removed
upstream (tap-only), there is no precision multi-touch drag state machine left to
port — the risk that got its Compose migration dropped twice is gone, and folding
the visual spec in now (rather than an unscheduled future pass) is worth the
Canvas rewrite. `PeqGraphMath` / `PeqPlotGeometry` are reused as pure math;
`PeqSurfacePaints` is replaced by Compose draw calls. See 10c-i / 10c-ii and the
**2026 update** note in §7.

- **In (to the graph composable):** `peqState`, the selected band UUID, the
  channel-display / curve-mode enums, and `nodeAlpha: Float` (§4, now a plain
  composable parameter, not a `setNodeAlpha` setter on a View).
- **Out:** `onNodeTapped(band)` → Compose shows a detail callout (§4) **and** the
  holder sets `selectedUuid`.

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

Each a reviewable PR that builds and (from 10c-i on) runs.

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

### 10c-i — graph functional port (Compose Canvas, no visual polish yet)
Replace `AndroidView(ParametricEqSurface)` with a real Compose-drawn graph — a `Canvas`
composable reproducing `ParametricEqSurface`'s current drawing 1:1 (grid, per-filter overlay
curves, summed curve via `drawSumCurve`, phase overlay, spectrum background via
`drawSpectrumDelta`, node circles via `drawBankNodes`) wired to the holder. Same node-alpha
fade timer + tap-detail callout (§4) and the graph-options menu (channel display / curve mode)
as a Compose `DropdownMenu`, as originally scoped.

**Tap-only, no dragging** — matches current behavior exactly. `ParametricEqSurface` already
removed drag-to-adjust entirely (screen is too small at 1280×480 for precise drag-selection of
a specific filter/frequency/gain point); tapping a node selects it and shows the detail
callout, but all value edits happen through the filter list's rows/dialogs (10b), never through
the graph itself. Compose's `detectTapGestures` (tap only) is sufficient here — no
`detectDragGestures`, no drag hit-testing to port, since there's nothing to port.

Goal here is *parity*, not polish — this step proves tap-to-select/detail and the options menu
work correctly as a Compose `Canvas` before any visual treatment changes. Verify on-device
against the current View-based graph side by side (or before/after screenshots): tapping a node
selects the right filter and shows the right detail callout, the sum curve matches the current
math exactly, the options menu behaves the same.

**Large.**
- *Investigate first:* exact `bindScope` side effects beyond deriving the list (`previewTitle`
  text, graph re-bind ordering) — carried over from the original 10c note, still applies here.

### 10c-ii — graph visual polish (ANALYZER_VISUAL_SPEC.md)
Once 10c-i is verified on-device, apply `docs/ANALYZER_VISUAL_SPEC.md` sections 1-4 and 7 to
this same graph, in the same PR sequence (separate commit(s), same branch is fine) rather than
a disconnected future phase:
- §1: real blur glow (`RenderEffect`/`BlurEffect`) on the summed curve, replacing
  `strokeNeon()`'s fake wide-stroke approximation. Individual per-filter overlay curves stay
  unglowed per the spec.
- §2: gradient area fill under the summed curve.
- §3: glass-treatment filter nodes (radial gradient fill, real blurred glow halo, crisp
  ring/border, highlight arc) replacing the current flat-circle `drawBankNodes` styling. Keep
  the existing right-channel dark-ring convention and node number label exactly as-is (spec is
  explicit these aren't decorative).
- §4: grid hierarchy (0dB line and octave markers brighter, everything else recedes further).
- §7: spectrum background smoothing (EMA) + peak-hold, desaturated further — replaces the raw
  redraw-every-frame `drawSpectrumDelta` behavior.
- §5 (glass bezel/vignette panel treatment) applies to this graph's container too, if not
  already covered by whatever `BmwPanel`/`BmwGlassBox` wrapping 10c-i's Canvas sits inside.

None of this changes interaction — 10c-i's tap-only selection model is untouched; this step
only changes how the graph is painted.

Verify on-device against the visual spec's descriptions (not against the old View, which this
is deliberately improving on) — the goal here is matching FabFilter Pro-Q's curve-rendering
feel per the original design conversation, not matching what `ParametricEqSurface` used to
look like.

**Medium** (building on 10c-i's already-correct interaction/data plumbing — this step only
changes how it's painted).

### 10e — assemble `ParametricEqScreen` + wire the fragment
Graph/List mode toggle (`BmwSegmentedControl` GRAPH/LIST + `AnimatedContent`).
`ParametricEqualizerFragment` → `FrameLayout` + `ComposeView`; **delete
`fragment_parametric_eq.xml` and every portrait code path** (`collapsePreview`,
the weighted-chain code, `ORIENTATION_LANDSCAPE` branching) — portrait is dead
weight on the 1280×480 head unit. Keep `activity_parametric_eq.xml`. **Medium.**

### 10f — file I/O + reset wording
`rememberLauncherForActivityResult` for: APO/REW `.txt` `OpenMultipleDocuments`
(→ `PeqApoImport.handleApoImport`), `.txt` `CreateDocument` export, private
backup `CreateDocument` / `OpenDocument` (`PrivatePeqBackup`), diagnostic-report
`CreateDocument`. `reset` confirm dialog. Reword the backup-restore confirmation
(drop "This can be undone" — there's no undo). **Medium.**
- `PeqApoImport` / `ApoImportRouter` / `PrivatePeqBackup` are already
  fragment-independent — the `Host` interface needs `context` / `activeScope` /
  `state` / `applyCandidate`, all on the holder.

### 10g — receiver + expanded diagnostics
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

**2026 update:** 10c was originally scoped as `AndroidView(ParametricEqSurface)` (wrap the
existing View, no visual change). Revised to a full Compose Canvas rewrite (10c-i) plus the
ANALYZER_VISUAL_SPEC.md treatment (10c-ii) in the same phase, rather than deferring the visual
spec to an unscheduled future pass — see chat discussion 2026-09-09. Crossovers & Tilt's and
Compressor's graphs are still `AndroidView`-wrapped as of this update; the same
functional-port-then-visual-polish split should apply to those when their turn comes, using
this phase as the template.

## 8. Expanded diagnostics (§10g)

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

- No `PeqDialogs` Compose rewrite (interop).
- No `ParametricEqSurface` *drag* interaction — it's tap-only already and the
  Canvas port keeps it tap-only (10c-i).
- Not porting Crossovers & Tilt's / Compressor's graphs off `AndroidView` here —
  that's later, using 10c-i/10c-ii as the template (§7 2026 update).

(The analyzer/graph visual polish that §9 previously deferred to "a separate
later pass" is now **in scope** as 10c-ii.)

## 10. After Phase 10 — Phase 11 cleanup

Delete `CrossoverDashboardBuilder` + now-dead `BmwSkinDrawables` classes (audit —
some may still back settings-screen XML), replace `DspPager` with a Compose
pager, drop `viewBinding` once no `*Binding` remains, replace `BmwPanel`'s
dp-slack title-column measurement with a `SubcomposeLayout` exact measure, and
audit remaining `AndroidView` wrappers.
