# Parametric EQ — Compose port sub-plan (roadmap Phase 10)

The dedicated sub-plan the roadmap's Phase 10 calls for. By now every other DSP
workspace screen is Compose (`compose/screens/{TonalityTilt, HeadroomOutput,
GainsDelay, OutputAllPass, CompressorScreens, CrossoverPagesScreens}`), the
component set is proven, and `BmwDspState` established the "screen owns hoisted
state + a single mutation funnel" shape. The PEQ screen is the last port and the
biggest single one.

## 1. What's there today

`ParametricEqualizerFragment` — ~1020 lines — plus ~14 supporting files
(`Peq*`, `BmwPeq*`, `ParametricEqSurface` + its `PeqGraphMath` / `PeqPlotGeometry`
/ `PeqSurfacePaints`), ~3000 lines total. It uses:

- **ViewBinding** (`FragmentParametricEqBinding`) + a `RecyclerView`
  (`ParametricEqBandAdapter`, `item_peq_band_list*.xml`) for the filter list.
- **`ParametricEqSurface`** — a ~1100-line custom `View`: spectrum + magnitude /
  phase / group-delay curves, per-band draggable nodes, node-tap selects the
  band. Precision multi-touch drag state machine.
- **`cards_pager`** — a landscape-only `ViewPager` swiping between a **GRAPH**
  page (`preview_card`) and a **LIST** page (`edit_card`); portrait stacks them.
- **State:** `peqState: BmwPeqState` (three band lists — `fullRangeBands` /
  `lowBandBands` / `midBandBands` — plus `preampDb`), `selectedScope`
  (`PeqScope.FULL/LOW/MID`), `selectedBandByScope: Map<PeqScope, UUID?>`,
  `history: PeqStateHistory(HISTORY_LIMIT)` (undo/redo stack, already a clean
  standalone class).
- **`applyCandidate(candidate, source, recordHistory, preserveScroll)`** — the
  single mutation funnel: `copy(enabled = true)` → `validate(sampleRate)` (toast
  + bail on failure) → if `nativeBmwPeqHandleReady() == true`
  `applyNativeBmwPeq(candidate)` else `candidate.persist(context)` → toast on a
  rejected result → push to `history` → `bindScope()`. This is pure
  validation + IO + history logic; it should move into the state holder almost
  verbatim.
- **File I/O** via `registerForActivityResult`: APO/REW `.txt` multi-import
  (`PeqApoImport` + `ApoImportRouter` route by filename — `input` / `low_left` /
  `low_right` / `mid_left` / `mid_right` → bank + channel), `.txt` export, and
  three-bank JSON preset export/import (`BmwPeqPreset`).
- **Dialogs:** `PeqDialogs(context, layoutInflater)` — a fragment-independent
  class already: value input (frequency / gain / Q), filter-type picker, channel
  picker, yes/no confirms. Plus `performReset`, `performEditAsString` (paste APO
  text), `showGraphOptionsPopup` (channel display + curve mode `PopupMenu`).
- **`BroadcastReceiver`** for `ACTION_NATIVE_BMW_DSP_UPDATED` and backup-restore
  results; a diagnostic-report path.
- **Drag-to-reorder** bands — `PeqBandEditor` produces a `"reorder"` edit result,
  so the list is reorderable.

The "unsaved edit guard" the comments mention (`canSwitchDspScreens()`) is
currently a **stub that always returns `true`** and the scope switch has no
guard — so there is nothing to port there.

## 2. Keep as `AndroidView`

`ParametricEqSurface` stays a `View`, hosted via `AndroidView` — same decision as
`CompressorSurface` / `CrossoverHandoffSurface` / `MbcBandGrMeter`. It's a large
precision drag surface with no functional benefit from a rewrite and real
regression risk (see the `project` memory on why its Compose migration was
dropped twice). Bridge:

- **In:** the current `peqState` (as whatever array/model `ParametricEqSurface`
  already consumes), the selected band UUID, and the channel-display / curve-mode
  enums.
- **Out:** a `onNodeSelected(uuid)` callback → the state holder sets
  `selectedBandByScope[scope]`; a `onNodeDragged(...)` callback → funnel a band
  edit through `applyCandidate`. `ParametricEqSurface` already exposes these as
  listeners for the fragment — reuse them.

`PeqGraphMath` / `PeqPlotGeometry` / `PeqSurfacePaints` come along unchanged as
`ParametricEqSurface`'s internals.

## 3. State holder — the crux

PEQ's state is **not** the `FloatArray` `BmwDspState` model. It's typed
(`BmwPeqState`), has its own persistence (`persist` / `applyNativeBmwPeq`) and its
own undo history. So PEQ gets its own holder:

```
rememberPeqState(): PeqStateHolder
```

- Fields: `peqState` (`mutableStateOf<BmwPeqState>`), `selectedScope`
  (`mutableStateOf`, `rememberSaveable`), `selectedBandByScope`, `history`
  (`PeqStateHistory` reused verbatim), `pendingDiagnosticReport`.
- **`applyCandidate(candidate, source, recordHistory = true)`** — moved in almost
  verbatim; the only change is `bindScope()` disappears (the visible band list is
  now a *derived* value: `bandsForScope(peqState, selectedScope)` recomputed on
  read, no explicit rebind).
- Convenience ops that today live in the fragment and just call `applyCandidate`:
  `performAdd`, `performReset`, `commitBandEdit(index, source, transform)`,
  `performUndo` / `performRedo` (`history.undo()/redo()` → `applyCandidate(...,
  recordHistory = false)`), `reorder(from, to)`.
- Derived: `visibleBands` (list for the current scope), `canUndo` / `canRedo`,
  `selectedUuid`, `scopeAccent`.
- The `RootlessAudioProcessorService` calls (`nativeBmwPeqSampleRate`,
  `nativeBmwPeqHandleReady`, `applyNativeBmwPeq`) stay exactly as they are —
  service-ready branching unchanged.

**Sizing: large.** It's the single biggest piece; land + review it on its own.

## 4. Sub-phases

Ordered so each is a reviewable PR that builds and (from 10c on) runs.

### 10a — `PeqStateHolder`
`rememberPeqState()` + `applyCandidate` + the convenience ops + derived values.
No UI yet; a throwaway `@Preview` / tiny harness exercises add / edit / undo /
scope switch. **Large.**
- *Investigate first:* exact `bindScope` side effects beyond deriving the list
  (scroll position via `preserveScroll`, `previewTitle` text, graph re-bind
  order); `PeqStateHistory`'s push/coalesce semantics (does it dedupe rapid
  same-band edits?).

### 10b — the filter list
`LazyColumn(key = { it.uuid })` — the roadmap's keyed-list requirement — with a
header row, an "add band" row, and per-band rows (frequency / gain / Q / type /
channel), each cell tap-to-edit. **Large.**
- Tap-to-edit reuses **`PeqDialogs` via interop** (call from a Compose click
  handler with `LocalContext` + a remembered `LayoutInflater`, same pattern as
  `showBmwNumberInput`). Port `PeqDialogs` to Compose `AlertDialog` later if
  wanted — not now.
- Row styling from `item_peq_band_list.xml` (+ `_header`, `_add`).
- *Investigate first / genuine risk:* **drag-to-reorder in a `LazyColumn`** has
  no first-party support. Options: `sh.calvin.reorderable` (small, well-tested
  lib — check it's on an allowed repo), or `detectDragGesturesAfterLongPress` +
  manual index math + `animateItemPlacement`. Decide when implementing; the
  reorder edit still funnels through `applyCandidate("reorder")`.
- *Also:* `selectedBandByScope` highlight + scroll-to-selected on node tap
  (`LazyListState.animateScrollToItem`), and `rememberSaveable` for scroll pos.

### 10c — graph + scope + graph-options
`AndroidView(ParametricEqSurface)` wired to the holder (§2), the **scope
segmented control** (`BmwSegmentedControl`, 3 options — already built), and the
graph-options menu (channel display / curve mode) as a Compose `DropdownMenu`
(like `BmwDropdown`). First runnable milestone: graph + list + scope switching,
edits landing. **Medium.**

### 10d — assemble `ParametricEqScreen` + wire the fragment
Graph/List mode switch (`BmwSegmentedControl` GRAPH/LIST + `AnimatedContent`, or a
Compose `HorizontalPager` of 2 for the swipe). `ParametricEqualizerFragment` →
`FrameLayout` + `ComposeView` (drop `fragment_parametric_eq.xml`); keep
`activity_parametric_eq.xml`. **Medium.**
- *Investigate first:* is the **portrait** layout used anywhere on a real device?
  The head unit is 1280×480 landscape. If portrait is effectively dead, build
  only the landscape layout + a plain vertical stack fallback and delete the
  portrait branching (`collapsePreview`, the weighted-chain code) — removes a
  whole code path. Confirm before dropping.

### 10e — file I/O + reset + edit-as-string
`rememberLauncherForActivityResult` for: APO/REW `.txt` `OpenMultipleDocuments`
(→ `PeqApoImport.handleApoImport`), `.txt` `CreateDocument` export, JSON preset
`CreateDocument` / `OpenDocument` (`BmwPeqPreset`). `performReset` (scope-aware
confirm), `performEditAsString` (paste APO text dialog). **Medium.**
- `PeqApoImport` / `ApoImportRouter` / `BmwPeqPreset` are already
  fragment-independent — the `Host` interface just needs `context` / `activeScope`
  / `state` / `applyCandidate`, all on the holder.

### 10f — receiver + diagnostics
`DisposableEffect` registering the `ACTION_NATIVE_BMW_DSP_UPDATED` +
backup-restore `BroadcastReceiver` (→ holder refresh / result toast), and the
`pendingDiagnosticReport` path. **Small.**

## 5. Non-goals for Phase 10

- No `ParametricEqSurface` Compose rewrite (stays `AndroidView`).
- No `PeqDialogs` Compose rewrite (interop).
- The analyzer/graph visual polish in `ANALYZER_VISUAL_SPEC.md` is a *separate*
  later pass on top of the ported screens — not part of this.

## 6. After 10 — Phase 11 cleanup (unchanged scope, now reachable)

Once PEQ is Compose: delete `CrossoverDashboardBuilder` + the now-dead
`BmwSkinDrawables` classes (audit — some may still back settings-screen XML),
replace `DspPager` with a Compose pager, drop `viewBinding` once no `*Binding`
remains, replace `BmwPanel`'s dp-slack title-column measurement with a
`SubcomposeLayout` exact measure, and audit remaining `AndroidView` wrappers.
