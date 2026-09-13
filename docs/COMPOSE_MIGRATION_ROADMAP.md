# Jetpack Compose Migration Roadmap

Plan for the remaining phases of moving SiphonDSP_J's DSP‑workspace UI from the
XML/`View` system to Jetpack Compose. This is a **planning document only** — no
implementation code lands with it.

---

## 1. Where we are

### Done (context — not re‑planned here)

| Phase | Outcome | Landed |
|---|---|---|
| **1 — Toolchain** | Compose plugin (`org.jetbrains.kotlin.plugin.compose` @ Kotlin `2.4.0`), `buildFeatures.compose = true`, `composeCompiler {}`, BOM `androidx.compose:compose-bom:2026.08.00`, plus `ui`, `ui-graphics`, `ui-tooling(-preview)`, `material3`, `activity-compose:1.12.0`, `lifecycle-{viewmodel,runtime}-compose:2.10.0` in `app/build.gradle.kts`. Proven with a throwaway `ComposeView` smoke test in `OutputAllPassFragment` (removed after verification). | PR #271 |
| **2 — Theme** | `app/src/main/java/app/siphondsp/compose/theme/` — `BmwTheme.kt` (`BmwDspTheme`, a fixed Material3 **dark** `ColorScheme`) and `BmwColors.kt` (`BmwColors` / `BmwTheme.colors` — the band‑specific accent palette: low / mid / headroom / tilt / default, plus `LocalBmwColors`). Every value reads directly from the existing `BmwDashboardSkin` `Int` constants, so the `View` system and Compose share one colour source of truth. | PR #272 |
| **3a — `BmwSlider`** | `app/src/main/java/app/siphondsp/compose/controls/BmwSlider.kt` — faithful Compose recreation of the hand‑painted slider chrome (capsule / groove / active fill / pill thumb with gradient body + grip ticks + focus glow), built on Material3 `Slider`'s `track` / `thumb` slots so gesture handling, a11y and RTL stay Material's. Stateless: caller hoists `value` / `onValueChange`. Verified rendering + drag on the head‑unit‑sized emulator. | PR #274 |
| **3b — `BmwSwitch`** | `compose/controls/BmwSwitch.kt` — the glass ON/OFF switch. | `eb6679f8` |
| **3a (infra) — state layer** | `compose/state/` (`PeqStateHolder` + siblings) — hoisted, observable screen state, superseding the `Fragment.rebuild()` reload‑on‑resume pattern. | `58c45ec7` |
| **3b (infra) — `BmwPanel`** | `compose/controls/BmwPanel.kt` — the glass panel scaffold, lean title, header‑toggle row (`BmwSectionHeader`, `BmwTitleRowWithSwitches`). | landed with Phase 4 |
| **4 — `TonalityTiltScreen`** | First real leaf‑screen port — `compose/screens/TonalityTiltScreen.kt`, `CrossoverTiltFragment`'s Tilt page. | `f37c06a0` |
| **5 — `HeadroomOutputScreen`** | `compose/screens/HeadroomOutputScreen.kt` — `GainLimiterFragment`'s Output page; first `AndroidView` live‑meter bridge (`MbcBandGrMeter`/`BmwGrMeter`). | `69d6baf2` |
| **6 — `GainsDelayScreen`** | `compose/screens/GainsDelayScreen.kt` — the channel‑card/diagram page, `BmwChannelCard`, linked‑delay derived state. `BmwSegmentedControl` landed alongside it (Polarity NORMAL/INVERT). | `45c1aae5` |
| **7 — `CompressorScreens`** | `compose/screens/CompressorScreens.kt` — MBC master strip, `CompressorGraph` visualiser, per‑band pages. | `c4b6bad2` |
| **9 — `OutputAllPassScreen`** | `compose/screens/OutputAllPassScreen.kt` — the repeated‑rows pattern (generated all‑pass sections). `BmwDropdown` landed alongside it. (Phase 8's components ended up folding into Phases 6/9 rather than shipping as standalone work first — order deviated from the original plan; content didn't.) | `cc083be4` |
| **10 — Parametric EQ** | `compose/screens/ParametricEqScreen.kt` + `PeqGraph.kt` + `PeqBandList.kt` — the full PEQ workspace: scope switch, graph, filter list, undo‑free edit model, presets, APO import, dialogs. Sub‑phases 10a–10f per `docs/PEQ_COMPOSE_PLAN.md`. | `1b90764e` (+ 10a `58c45ec7`) |

All of Phases 3b–10 landed **2026‑09‑09** — a single sprint this doc's "Current state" section below was never updated after. Treat every "TODO" framed in the phase‑detail sections further down (§4 onward) as **historical planning record**, not open work, except where a section is explicitly marked otherwise.

### Current Compose footprint

Every DSP workspace screen's *content* is Compose (Crossovers & Tilt, Gains & Delay + Output, Multiband Compressor, Output All‑Pass, Parametric EQ — see the table above). What's still `View`/XML, confirmed live in the codebase as of this update:

- **`DspPager`** — the swipe‑pager + page‑dots *host* (a `ViewPager2` wrapper). Still used by `CrossoverTiltFragment`, `GainLimiterFragment`, `NativeBmwCompressorFragment`, `OutputAllPassFragment` — but every page it hosts in all four is already a `ComposeView`, so **Phase 11.1 is unblocked**. `ParametricEqualizerFragment` is the one exception: it skips `DspPager` entirely and uses Compose's own `HorizontalPager` directly (landed alongside the PEQ toolbar redesign, 2026‑09‑13) — the first screen to do so, and the proof that Phase 11.1 works.
- **Activity‑level chrome** (`activity_parametric_eq.xml`) — the shared `MaterialToolbar`/`AppBarLayout`/sidebar‑rail scaffold behind all 5 workspace activities. Not in this roadmap's original scope at all; Compose content is hosted inside it via `ComposeView` (same interop pattern as everywhere else), most recently `peq_toolbar_actions` (2026‑09‑13).
- **`CrossoverDashboardBuilder.kt`** — confirmed **dead**: the file still exists but has zero real `import`s anywhere in the app; every remaining reference is a doc‑comment ("Compose port of `CrossoverDashboardBuilder.addSliderRow`") left as porting provenance. Safe to delete (Phase 11.2), pending a `BmwSkinDrawables.kt` audit for anything Settings/preference screens still use.
- **`viewBinding = true`** — ~25 files still use it (down from the ~27 baseline this doc cited). Most are legitimately out of scope per §13 (Settings, Blocklist, Onboarding, Measurement Capture, App Compatibility). A few are PEQ‑adjacent leftovers worth a look before Phase 11.3: `ParametricEqualizerActivity.kt`, `PeqDialogs.kt`, `ParametricEqBandAdapter.kt`, `ParametricEqBandList.kt`, `ParametricEqualizerPreference.kt` (the last three back the separate legacy Settings‑page inline PEQ card, not the workspace screen — confirm in scope before touching).
- **`AndroidView` interop wrappers**, still in place per the "keep through Phase 10" decision, now due for the Phase 11 revisit: `BmwGrMeter.kt` (wraps `MbcBandGrMeter`), `HeadroomOutputScreen.kt` (wraps the same for the master limiter), `CrossoverPagesScreens.kt`, `PeqGraph.kt`.

Historical framing below (the rest of §1, and §§4–10) describes the **pre‑Phase‑4 state** and the **original plan** for phases now complete — kept for context, not current status. Every workspace screen previously listed as "currently View" in the table below has been ported; see the Done table above for what actually shipped instead.

- **`CrossoverDashboardBuilder`** — the imperative glass‑panel row builder
  (`dashboardPanel`, `addSliderRow`, `addDropdownRow`, `addSegmentedSwitchRow`,
  `addChannelCard`, `addChannelDiagramSection`, `addCustomView`, `glassSwitch`,
  `headerToggleRow`, `buildGlassSegmentGroup`, …).
- **`BmwSkinDrawables.kt`** — the hand‑painted `Drawable`s behind that builder
  (`GlassSwitchTrackDrawable`, `GlassSwitchThumbDrawable`, slider capsule/thumb,
  segment group, etc.).
- **`DspPager`** — the swipe‑pager + page‑dots host. Call sites:
  `CrossoverTiltFragment`, `GainLimiterFragment`, `NativeBmwCompressorFragment`,
  `OutputAllPassFragment`.
- **`viewBinding = true`** — ~27 `*Binding` usages across the module.
- Custom `Canvas`‑drawn `View`s: `CompressorSurface`, `ParametricEqSurface`,
  `MbcBandGrMeter`, `NativeBmwDspResponseView`.

### The screen inventory (all currently `View`)

| Screen | Host | Structure |
|---|---|---|
| Crossovers & Tilt | `CrossoverTiltFragment` + `CrossoverTiltActivity` | `DspPager` of 2 pages: Crossovers, **Tilt** |
| Gains & Delay + Output | `GainLimiterFragment` + `GainLimiterActivity` | `DspPager` of 2 pages: channel‑card **diagram page**, **Output page** (Headroom, post‑gain L/R, master limiter + GR meter) |
| Multiband Compressor | `NativeBmwCompressorFragment` + `NativeBmwCompressorActivity` | `DspPager`; page 1 = `CompressorSurface` visualiser + master strip; then per‑band pages with `MbcBandGrMeter`s |
| Output All‑Pass | `OutputAllPassFragment` | `DspPager` of 4 output pages, each with repeated all‑pass sections (`INDEX_ALL_PASS` block, section width) |
| Parametric EQ | `ParametricEqualizerFragment` + `ParametricEqualizerActivity` | 16 filters × 3 banks (Full/Low/Mid scope), read‑only response graph (`ParametricEqSurface` / `NativeBmwDspResponseView`), undo, three‑bank presets, APO/REW import routed by filename |
| Settings, Blocklist, Apps list, Onboarding, … | `PreferenceFragmentCompat` / RecyclerView | **Out of scope** for this roadmap unless explicitly pulled in — see §6 |

---

## 2. Guiding principles / interop strategy

1. **One screen (or one pager page) at a time.** A fragment can mix Compose and
   `View` pages: keep `DspPager` (a `View`) and drop a `ComposeView` in for the
   one page that's been ported. This was proven by the Phase 1 smoke test and the
   Phase 3a demo. Replace `DspPager` itself with a Compose pager only once *every*
   page of a given fragment is Compose (see Phase 9 / cleanup).
2. **`AndroidView { }` for the hard `Canvas` views.** `CompressorSurface`,
   `ParametricEqSurface`, `MbcBandGrMeter`, `NativeBmwDspResponseView` are
   large, precise, touch‑driven custom draws. Host them unchanged inside a
   Compose screen via `AndroidView` interop; port them to Compose `Canvas` last,
   or not at all if interop proves acceptable. (Matches the existing decision to
   defer the `ParametricEqSurface` rewrite — see `project` memory.)
3. **`BmwDashboardSkin` stays the colour source of truth** until the final
   cleanup phase. `BmwColors` already re‑reads it; new components do the same.
4. **Stateless components, hoisted state.** `BmwSlider` takes `value` +
   `onValueChange`. Every new component follows suit. The screen owns state.
5. **No visual regressions.** Each port is a pixel/behaviour match of the screen
   it replaces, verified on the 1280×480 head‑unit form factor before merge
   (per the workspace‑chrome memory — emulator alone is not sufficient evidence
   for chrome/inset changes; it *is* sufficient for in‑panel content).

---

## 3. Cross‑cutting prerequisites (discovered from Phases 1–3)

These are not in the original phase list but every leaf‑screen port depends on
them. They should be built as part of / immediately before **Phase 4**, not
assumed away.

### 3a. Screen state layer — **investigate first, this is the crux**

Today `Fragment.rebuild()` reloads `NativeBmwDspValues` (a `FloatArray`) from
disk on every `onResume` and rebuilds the whole `View` tree; edits call
`NativeBmwDspValues.save()` + `.broadcast()`. Compose needs **hoisted,
observable** state instead.

Open questions to resolve before Phase 4:

- **Shape:** one `ViewModel` per workspace activity exposing `StateFlow<FloatArray>`
  / a typed state object, vs. a plain `remember`‑ed state holder per
  `ComposeView`. `lifecycle-viewmodel-compose` is already a dependency.
- **Write‑back:** debounce/throttle `save()` + `broadcast()` on drag (the `View`
  path does this via `onValueChangeFinished`‑style callbacks); define the
  equivalent.
- **External changes:** the `onResume` reload exists so a restored
  preset/profile/backup isn't clobbered. The state layer must observe the same
  broadcast and refresh — decide the mechanism (BroadcastReceiver →
  `mutableStateOf`, or a shared repository `Flow`).
- **Index mapping:** helpers to read/write a single `INDEX_*` slot as an
  observable `Float` with range/step, so screens read like
  `state.slider(INDEX_TILT_AMOUNT, -6f..6f, step = 0.1f)`.

**Sizing: medium–large.** No prior art in this codebase; it is the reusable
pattern every subsequent screen inherits, so it's worth doing deliberately as
its own reviewable change even though it first ships "inside" Tonality Tilt.

### 3b. `BmwPanel` / dashboard scaffold

A Compose equivalent of `CrossoverDashboardBuilder.dashboardPanel` — the glass
panel background, lean title, optional header **toggle** row, divider, content
padding. Needed by the first screen port. **Sizing: medium.** Uncertainty: the
exact chrome spec is spread across `dashboardPanel` + several drawables in
`BmwSkinDrawables.kt`; port from those, don't eyeball.

### 3c. Component set (built as each screen first needs it)

| Component | First needed by | Notes |
|---|---|---|
| `BmwSlider` | done (3a) | — |
| `BmwSwitch` | Phase 3b / 4 | see Phase 3b |
| `BmwPanel` / scaffold + header‑toggle row | Phase 4 | see 3b |
| value‑readout cell | Phase 4 | the right‑hand `150 Hz` / `0.71` chip |
| `BmwSegmentedControl` | Phase 8 / 9 | LR2/LR4, First/Second order — from `buildGlassSegmentGroup` |
| `BmwDropdown` | Phase 8 / 9 | exposed‑dropdown; from `addDropdownRow` |
| `BmwChannelCard` | Phase 6 | the delay+gain+link card from `addChannelCard` |
| Compose pager + page dots | cleanup | replaces `DspPager`; `androidx.compose.foundation.pager.HorizontalPager` (confirm stable at BOM 2026.08.00) + custom dots; page‑dots today live in an activity‑level slot (`R.id.dsp_page_toggle_slot`) — decide integration |

---

## 4. Phase 3b — `BmwSwitch`

Recreate the glass switch as a Compose composable: gradient track with sheen,
ON/OFF label, status‑colour border; glowing sphere thumb with radial gradient +
highlight arc. Mirror `BmwSkinDrawables.kt`'s `GlassSwitchTrackDrawable`
(line ~380) and `GlassSwitchThumbDrawable` (line ~497) dimension‑for‑dimension
and colour‑for‑colour, exactly as `BmwSlider` mirrored the slider drawables.

- **Depends on:** Phases 1–2 only. Independent of the state layer (stateless:
  `checked` + `onCheckedChange`).
- **Investigate first (do not assume):**
  - **Material3 `Switch` API surface at BOM 2026.08.00.** Read the actual
    signature/source. Expectation to *confirm or refute*: Material3 `Switch`
    exposes only `thumbContent: @Composable () -> Unit` (a small icon slot inside
    the thumb) and `SwitchColors` — **no custom track‑drawing slot** and no
    thumb‑shape slot, unlike `Slider`'s `track`/`thumb`. If confirmed, `Switch`
    cannot host the glass track + sheen + ON/OFF text + sphere thumb, so build on
    **`Modifier.toggleable(role = Role.Switch)`** + `Canvas` (no Material host),
    accepting that ripple/drag‑to‑toggle/a11y semantics are then hand‑wired.
  - Whether horizontal drag‑to‑toggle (not just tap) is required to match the
    current `MaterialSwitch` feel; if so, `swipeable`/`anchoredDraggable` is
    needed on top of `toggleable`.
  - The ON/OFF **label** rendering: `Canvas` `drawText` vs. an overlaid `Text` —
    the drawable does it in‑canvas; a `Box { Canvas(); Text() }` may read better.
- **Sizing: medium.** Same recreation effort as `BmwSlider` (~similar line
  count), but *higher* uncertainty because there's no Material host slot to lean
  on — the toggle gesture + a11y semantics must be assembled by hand rather than
  inherited.

---

## 5. Phase 4 — `TonalityTiltScreen` (first real leaf‑screen port)

Replace **only** `CrossoverTiltFragment`'s `tiltPage` content with a
`ComposeView`. The Crossovers and Mono Bass pages stay on the `View` system;
`DspPager` still hosts all three.

Target parity (from `CrossoverTiltFragment.tiltPage`):

- Panel: `bmw_dsp_tilt_section` title, section‑level enable toggle on
  `INDEX_TILT_ENABLED` (25), lean header, `topContentGapDp = 40`.
- `BmwSlider` "Tilt Amount" — `INDEX_TILT_AMOUNT` (26), −6..6 dB, step 0.1,
  accent `SLIDER_TILT_COLOR`.
- `BmwSlider` "Tilt Pivot" — `INDEX_TILT_FREQ` (27), 200..2000 Hz, step 1,
  accent `SLIDER_TILT_COLOR`.
- Right‑hand value‑readout chips matching the `View` layout.

- **Depends on:** Phase 3b (`BmwSwitch`), §3a (state layer — **first real user**),
  §3b (`BmwPanel`), value‑readout cell.
- **Investigate first:**
  - Land the §3a state layer here and get it reviewed as its own concern; every
    later screen reuses it, so a wrong shape here is expensive.
  - `ComposeView` composition strategy inside a `DspPager` page that is
    added/removed as the pager scrolls (`DisposeOnViewTreeLifecycleDestroyed`
    vs. default) — the smoke test used the default and worked, but a pager page
    has a different attach/detach cadence; verify no recomposition/state‑loss on
    page swipe.
  - How the section‑enable toggle greys/disables the two sliders (the `View`
    path's exact enabled/alpha behaviour).
- **Sizing: medium** — small as a *screen* (one panel, two sliders, one switch),
  but it carries the one‑time cost of §3a + §3b landing with it.

---

## 6. Phase 5 — `HeadroomOutputScreen` (GainLimiter Output page)

Port `GainLimiterFragment`'s **`outputPage`** to a `ComposeView` (the channel
diagram page stays `View` — that's Phase 6). Contents:

- `BmwSlider` "Headroom" — `INDEX_HEADROOM` (5), −12..0 dB, step 1.
- `BmwSlider` "Post gain L" / "Post gain R" — `INDEX_POST_GAIN_L/R` (10/11),
  −6..6 dB, step 0.5.
- Sub‑header "Limiter" + header toggle on `INDEX_MASTER_LIMITER_ENABLED`.
- `BmwSlider` "Threshold" — `INDEX_MASTER_LIMITER_THRESHOLD`, −12..0 dB, step 0.5.
- The live `MbcBandGrMeter` (`addCustomView`) → host via **`AndroidView`**.

- **Depends on:** Phase 4 (state layer, `BmwPanel`, `BmwSlider`, `BmwSwitch` all
  proven).
- **Investigate first:** the `AndroidView` bridge for a live meter — it's fed
  from a polling callback (`nativeBmwMasterLimiterMeter()` on a ticker); confirm
  the `View` keeps updating when wrapped and that the update loop is
  paused/resumed with the composition.
- **Sizing: small.** Same shape as Tonality Tilt (panel + sliders + a
  header‑toggle), just more rows and different index ranges. The only new thing
  is the first `AndroidView` meter bridge.

---

## 7. Phase 6 — `GainsDelayScreen` (GainLimiter channel‑diagram page)

Port `GainLimiterFragment`'s `diagramPage`: four `addChannelCard`s (Mid L, Low L,
Mid R, Low R), each with a delay slider, a gain slider, and per‑pair delay‑link
handling (`INDEX_DELAY_LINKED`), plus `addChannelDiagramSection` (the routing
diagram between the cards).

- **Depends on:** Phase 5; new **`BmwChannelCard`** component (§3c).
- **Investigate first:**
  - The **link** behaviour: when `INDEX_DELAY_LINKED` is on, editing one
    channel's delay writes the pair's; the `View` code swaps
    `delayLinkedIndex`/`delayIndex` at build time. In Compose this becomes
    derived state — design how the two cards share/mirror a value without a
    rebuild.
  - The channel **diagram** graphic (`addChannelDiagramSection`) — static vector
    vs. a small `Canvas`; is it worth porting or `AndroidView`‑wrapping?
  - Layout: four cards + centre diagram on a 1280×480 landscape panel — the
    `View` version has specific measure behaviour; match it.
- **Sizing: large.** First multi‑element screen with inter‑element (linked) state,
  a bespoke card component, and a diagram. No prior art for linked/derived state
  across Compose sub‑components in this codebase.

---

## 8. Phase 7 — `CompressorScreen`

Port `NativeBmwCompressorFragment`: master strip (MBC enable + dry/wet mix), the
`CompressorSurface` visualiser page, and the per‑band pages
(threshold/ratio/knee/attack/release/makeup per band, `INDEX_LOW_COMPRESSOR_*`
28–34, `INDEX_MID_COMPRESSOR_*` 35–41) with their `MbcBandGrMeter`s.

- **Depends on:** Phase 6; `AndroidView` meter bridge (Phase 5).
- **Investigate first:**
  - **`CompressorSurface`** — a canvas‑heavy custom `View` (comparable risk
    profile to `ParametricEqSurface`). Decision required: keep via `AndroidView`
    (recommended for this phase) vs. port to Compose `Canvas` (defer). Do **not**
    port it as part of this phase unless the `AndroidView` route fails review.
  - The number of per‑band pages and whether they're generated from
    `MBC_BAND_COUNT` — if so this also exercises repeated content (overlaps
    Phase 9's concern; consider ordering Phase 9 before this).
  - Multiple simultaneous live meters (`bandGrMeters[]`, `lowBusGrMeter`,
    `midBusGrMeter`) — perf of several `AndroidView`s each on their own ticker.
- **Sizing: large.** Many controls, several live meters, a heavy visualiser (even
  if wrapped), and possibly generated band pages.

---

## 9. Phase 8 — `BmwSegmentedControl` + `BmwDropdown`

Standalone component work, no screen attached. Recreate:

- **`BmwSegmentedControl`** from `CrossoverDashboardBuilder.buildGlassSegmentGroup`
  / `addSegmentedSwitchRow` — the glass pill segment group (e.g. LR2/LR4,
  First/Second order).
- **`BmwDropdown`** from `addDropdownRow` — the glass exposed‑dropdown.

- **Depends on:** Phases 1–2. Independent of the state layer (stateless).
- **Investigate first:**
  - Whether to build `BmwDropdown` on Material3 `ExposedDropdownMenuBox` (does it
    allow enough surface/field restyling at this BOM?) or from scratch on a
    `Popup`.
  - The segment group's selection animation / pressed state in the current
    drawable — match or intentionally simplify.
- **Sizing: medium** (two components, each roughly `BmwSwitch`‑scale, moderate
  uncertainty on the Material host question).

---

## 10. Phase 9 — `OutputAllPassFragment` real port

Port for real (the demo/smoke‑test wiring is already removed). Structure: a
4‑output pager, each page rendering repeated all‑pass sections from the
`INDEX_ALL_PASS` (54) block — per section: a "Type" dropdown (First/Second order)
with a section enable toggle, a "Frequency" slider (20..1000 Hz), a "Q" slider
(0.1..30).

- **Depends on:** Phase 8 (`BmwDropdown`, needed for "Type"); the state layer;
  all base components.
- **Investigate first — this is the point of doing this screen:**
  - **Generated/repeated Compose rows.** No prior art in this codebase. Decide
    `Column { repeat(n) { … } }` vs. `LazyColumn`; how each generated row's
    state hoists back to distinct `INDEX_ALL_PASS + offset` slots without a full
    rebuild; keys/stability so recomposition is scoped per row.
  - Whether the 4 output pages are still `DspPager` pages each hosting a
    `ComposeView`, or whether this fragment is the first to move fully to a
    Compose `HorizontalPager` (all its pages would be Compose at that point).
  - The known `NestedScrollView` `isFillViewport` measure hazard the `View`
    version documents — the Compose equivalent (`verticalScroll` + weighted
    children) has its own pitfalls; verify rows after a switch/toggle aren't
    dropped.
- **Sizing: large.** The repeated‑rows pattern is genuinely new here and is the
  prerequisite learning for the PEQ screen.

---

## 11. Phase 10 — Parametric Equalizer screen (highest risk — its own sub‑plan)

> Sub‑plan written: see `docs/PEQ_COMPOSE_PLAN.md` (sub‑phases 10a–10f).

`ParametricEqualizerFragment` — explicitly **last**, with a dedicated sub‑plan
written once Phases 3b–9 are done and the component set is proven. Scope that
makes it the hardest:

- 16 filters × 3 banks (Full / Low / Mid scope), scope switch with an
  unsaved‑edit guard.
- Filter **list** page + read‑only **response graph** (`ParametricEqSurface` /
  `NativeBmwDspResponseView`) — `AndroidView`‑wrap the graph initially.
- **Undo** ("this can be undone" throughout), per‑bank clear/reset.
- **Three‑bank preset** export/import (`BmwPeqPreset.encode/decode`).
- **APO/REW `.txt` import** routed by filename (`input` / `low_left` / `low_right`
  / `mid_left` / `mid_right` → bank+channel) plus the fallback
  import‑into‑current‑bank dialog.
- Several dialogs (`PeqDialogs`, `PeqBandEditor`).

- **Depends on:** everything above — especially Phase 9's repeated‑rows pattern,
  Phase 8's segmented control (scope switch), and a Compose dialog pattern
  established somewhere earlier.
- **Investigate first (in the sub‑plan):** undo/redo state model in Compose
  (snapshot stack vs. command list); porting `PeqDialogs` to Compose vs. keeping
  `DialogFragment`s; whether `ParametricEqSurface` finally gets a Compose port or
  stays `AndroidView` permanently; list virtualisation for 16 rows × edit state.
- **Sizing: large++ / own epic.** Do not attempt before the rest is stable.

---

## Visual design direction: analyzer/graph polish

> Full implementation-ready detail: see `docs/ANALYZER_VISUAL_SPEC.md`.

Applies to the PEQ graph (`ParametricEqSurface`), the Crossovers & Tilt response graph
(`NativeBmwDspResponseView`), and the Compressor multiband graph. Direction: move from the
current flat/neon-approximated look toward something closer to FabFilter Pro-Q's curve
rendering -- real depth and glow rather than flat fills and wide-stroke fake blur.

Current state (for reference):
- Curve glow is faked via `strokeNeon()`: a second copy of the same path, drawn ~3.4x wider
  at low alpha, underneath the crisp line. Reads as a hard-edged translucent band, not a
  soft glow.
- Filter nodes (`drawBankNodes`) are flat-filled circles with a flat low-alpha halo ring --
  no gradient, no real blur.
- The Compressor graph's per-band zones are flat, hard-edged solid color blocks.
- Grid lines are uniform weight/opacity throughout.

Target treatment, for all three graphs:
- **Real blur glow** on the combined/summed response curve, using Compose's `RenderEffect`/
  `BlurEffect` (same mechanism already used for `BmwSlider`'s focus ring in Phase 3a) instead
  of the fake wide-stroke approximation. Per-filter individual curves stay thin/subtle/
  low-opacity (as now) so they don't compete with the summed curve.
- **Gradient area fill** under the combined curve, fading from the curve's color to
  transparent -- this is a large part of what makes Pro-Q's curve read as "substantial"
  rather than "a line on a chart."
- **Filter/band nodes**: soft radial-gradient fill (glassy, not flat) plus a real blurred glow
  halo, colored to match their curve -- same glass-sphere spirit as `GlassSwitchThumbDrawable`'s
  thumb treatment, applied to graph nodes instead of switches.
- **Grid hierarchy**: not all gridlines equal weight. The 0dB reference line (and octave
  markers, where present) slightly brighter/more opaque; everything else recedes further than
  it currently does.
- **Panel depth**: a subtle vignette/darkening toward the panel edges, plus the same glass-bezel
  treatment (inset shadow, subtle rim light) already used elsewhere in the app (see
  `BmwSkinDrawables`), so the graph area feels like part of the same physical dashboard instead
  of floating on flat black.
- **Spectrum overlay** (PEQ background spectrum): desaturate/lower its opacity further so it
  reads as ambient context rather than competing with the curve, and add smoothing/peak-hold
  decay rather than redrawing the raw instantaneous spectrum every frame (real analyzers show a
  fast trace plus a slower-decaying peak line).
- **Compressor graph specifically**: soften the flat color-block zones into gradient-tinted
  regions using the same grid/depth treatment as above; if the compressor engine exposes live
  gain-reduction data, consider drawing an actual GR trace on top of the static band zones
  rather than leaving them purely static.

This is a rendering/visual-polish pass, not a data/behavior change -- the underlying curve
math, node interaction, and drag-to-adjust behavior stay exactly as they are now.

## Gains & Delay: car cutout diagram

Direction (decided): **Option 2 -- distance/delay readout overlay.** Keep the existing car
interior photo and layout as the base. Add, per speaker position marked on the photo:
- A thin connecting line from that speaker's position to a marked listening-position point
  (the app's existing ~60/40 driver/passenger-weighted reference point -- see
  [[e60-dsp]]/[[rew-measurement]] project notes on multi-seat tuning philosophy).
- A label along or at the end of that line showing the current delay value (already available
  per-channel from `NativeBmwDspValues`) and the equivalent physical distance the delay
  represents (distance = delay_ms * speed_of_sound_mm_per_ms; confirm/settle on a reference
  speed-of-sound constant, e.g. ~343mm/ms at ~20C, when implementing).
- These lines/labels update live as the corresponding Delay slider is dragged, giving the
  diagram real informational value instead of being purely decorative.

Explicitly NOT in scope for this direction: animated wavefront arcs (a fancier alternative
that was considered and set aside), and no change to the photo/background art itself beyond
the added overlay lines/labels and the general glass-panel treatment applied to panels
elsewhere in this document.

---

## 12. Phase 11 — Final `View`‑system cleanup — **NEXT UP**

> **Status (2026‑09‑13): unblocked and next.** Phases 3b–10 all landed 2026‑09‑09
> (see the Done table in §1) — every workspace screen's content is Compose, so
> the "only once every workspace screen is Compose" precondition below is
> satisfied. This is the next real work on this roadmap.

Only once **every** workspace screen is Compose:

1. Replace `DspPager` with the Compose pager everywhere; delete `DspPager` +
   `R.id.dsp_page_toggle_slot` wiring.
2. Delete `CrossoverDashboardBuilder` and the now‑unused parts of
   `BmwSkinDrawables.kt`. **Audit, don't assume** — some drawables may still back
   XML in settings/preference screens, notifications, or `ThemingDelegate`.
3. Remove `viewBinding = true` **only after** all ~27 `*Binding` usages are gone
   (many are in out‑of‑scope settings screens — see below).
4. Grep for any remaining `ComposeView` / `AndroidView` interop wrappers; either
   port the wrapped `View` (`CompressorSurface`, `ParametricEqSurface`,
   `MbcBandGrMeter`, `NativeBmwDspResponseView`) to Compose `Canvas` or make an
   explicit, documented decision to keep `AndroidView` for them.
5. Drop `ui-viewbinding`‑adjacent deps if any; keep `activity-compose`.

- **Sizing: medium**, mostly mechanical + audit, but gated entirely on Phases
  4–10.

---

## 13. Open scoping questions (decide before/with Phase 4)

- **Settings / preference screens** (`DspFragment`, `settings/`,
  `PreferenceGroupFragment`, `BlocklistFragment`, `AppsListFragment`,
  `OnboardingFragment`) are `PreferenceFragmentCompat` / RecyclerView, not the
  glass‑panel workspace style. Are they in scope for the Compose migration at
  all, or explicitly staying on AndroidX Preference? This decides whether
  `viewBinding` can ever be removed (Phase 11.3).
- **Live `Canvas` views** (`CompressorSurface`, `ParametricEqSurface`,
  `MbcBandGrMeter`, `NativeBmwDspResponseView`): `AndroidView` forever, or port
  as a final phase? Recommendation: `AndroidView` through Phase 10, revisit in
  Phase 11.
- **State layer granularity:** one `ViewModel` per workspace activity, or one
  shared DSP‑state repository for the whole app? (§3a)
- **`DspPager` replacement timing:** per‑fragment as each goes fully Compose, or
  one sweep in Phase 11?

---

## 14. Sizing summary

| Phase | Item | Size | Why |
|---|---|---|---|
| 3b | `BmwSwitch` | M | Same recreation effort as `BmwSlider`, higher uncertainty — no Material host slot, toggle gesture + a11y hand‑wired |
| 3a (infra) | Screen state layer | M–L | No prior art; reusable pattern for all later screens |
| 3b (infra) | `BmwPanel` / scaffold | M | Chrome spec spread across builder + drawables |
| 4 | `TonalityTiltScreen` | M | Trivial as a screen; carries §3a + §3b landing |
| 5 | Headroom / Output page | S | Same shape as Tonality Tilt; +1 `AndroidView` meter |
| 6 | Gains & Delay diagram page | L | Linked/derived cross‑component state, bespoke card, diagram |
| 7 | Compressor | L | Many controls, multiple live meters, heavy visualiser (wrapped) |
| 8 | `BmwSegmentedControl` + `BmwDropdown` | M | Two components; Material‑host‑vs‑scratch question |
| 9 | `OutputAllPassFragment` real port | L | Generated/repeated rows — new pattern, prerequisite for PEQ |
| 10 | Parametric EQ | L++ | Own epic: 16×3 filters, undo, presets, APO import, graph |
| 11 | Final `View` cleanup | M | Mechanical + audit, gated on all of the above |
