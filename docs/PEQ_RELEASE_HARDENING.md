# Three-bank PEQ release hardening

## Release-readiness decision

**Not ready for stable release.** The architecture and recovery safeguards are
in place, but release-candidate builds, upgrade/downgrade coverage, native
offline harness coverage, performance measurements, and target-head-unit audio
validation remain release blockers. CI success alone does not change this
decision.

## Staged release strategy

1. Build an internal `rootlessFull` release candidate.
2. Complete the manual device/audio checklist on the Snapdragon 662 head unit.
3. Publish a clearly labelled preview APK as a workflow artifact.
4. Offer it to a small opt-in beta group with the feedback template below.
5. Expand the beta only after recovery and upgrade evidence is clean.
6. Promote the exact tested commit to stable.

The project does not currently provide store-managed percentage rollout.
Tagged preview builds and separately named workflow artifacts are the manual
equivalent. The canary cannot be assumed to install beside stable because it
uses the same application ID; treat it as an upgrade after backing up app data.

Do not create the release tag until the exact release commit passes all gates.
The current project version convention is `1.6.15`/version code `52`; the first
candidate tag should therefore be `v1.6.15-peq-rc1`, subject to the release
maintainer confirming that 1.6.15 is still the intended release version.

## Rollback and data preservation

The known-good pre-hardening baseline is master merge commit `0d23c18`. Build it
with:

```bash
git switch --detach 0d23c18
./gradlew :app:assembleRootlessFullRelease
```

Before installing an older APK, export the complete three-bank JSON preset and
retain an app-data backup. Older builds may ignore Low and Mid state and must
never flatten those branches into Full Range. Reinstalling a newer build can
restore the exported complete preset.

Runtime fallback to the old Full-Range-only processor is intentionally absent:
the legacy processor cannot safely coexist with the authoritative three-bank
path. Build-level rollback avoids two active processors and duplicated state.
Emergency recovery uses a safe empty three-bank state through the same atomic
native path.

Do not delete PR #29 or #30 branches until stable release; keep them closed for
forensic comparison and never reuse them.

## Backup, migration, and recovery

- The first legacy migration stores the original serialized Full Range bands,
  enabled state, preamp, migration version, and timestamp once.
- A successful committed state stores the primary and last-known-good snapshots
  in one preference commit.
- Drafts, rejected imports, temporary graph motion, and failed native applies
  never replace last-known-good.
- Cold start tries persisted state once, then last-known-good, then a safe empty
  state. Recovery records a concise result and does not loop or inject test
  filters.
- Debug builds expose **Restore last known good** inside the diagnostic dialog.
  The restore still validates and uses the normal complete-state atomic apply.

Downgrades cannot understand Low and Mid banks. Export first; the one-time
legacy backup and complete JSON preset are the preservation mechanisms.

## Diagnostics, privacy, and support bundle

The user-invoked diagnostic screen shows the report before export. It includes
app/Android versions, device model, architecture, screen dimensions, sample
rate availability, service status, PEQ format/enabled state, bank counts, last
restore result, fallback usage, and last-known-good timestamp.

It excludes audio, filter values, imported file contents, filenames/paths,
usernames, accounts, and unrelated device identifiers. Export creates a local
text file; nothing is uploaded automatically. Logs remain concise and must not
record graph motion or audio-loop activity.

Preset and APO imports are treated as data, never executable content, and are
limited to 1 MB before parsing. Android's document provider controls the output
destination, preventing application-built traversal paths.

## Native error reporting

The current JNI API returns a Boolean. Kotlin validation distinguishes sample
rate, Nyquist/frequency, gain, Q, section count, and duplicate identity errors
before JNI. A structured native error enum remains desirable, but changing the
JNI contract immediately before release would expand risk and requires its own
native-test-backed PR. Native `false` is reported as configuration rejection;
raw pointers and internal class names are not shown to users.

## Compatibility matrix

| Configuration | Status | Evidence / required work |
|---|---|---|
| Android API 29-36 rootless | Expected | Build configuration; device matrix not completed |
| Android API 26-36 rooted variants | Expected | Build configuration; rooted PEQ validation pending |
| ARM64 | Expected | Target device validation pending |
| ARMv7 | Not yet tested | Confirm packaged ABI and native smoke test |
| x86/x86_64 emulator | Not yet tested | Confirm packaging and unit/instrumentation run |
| 44.1 kHz | Not yet tested | Golden math covers Nyquist; audio run required |
| 48 kHz | Not yet tested for RC | Main development assumption; head-unit run required |
| 96 kHz | Not yet tested | Device/native harness required |
| Snapdragon 662, 1280 x 480 | Release blocker | Complete canary checklist |
| Phone portrait / tablet | Expected | XML parses; visual/instrumentation validation pending |
| Samsung devices | Not yet tested | Opt-in beta coverage required |

No untested row should be advertised as supported based only on this table.

## Upgrade and downgrade matrix

Test fresh/no-state, current stable master, Full-Range-only legacy state,
malformed legacy state, maximum valid banks, APO imports, and unusual
channel-specific filters. Verify no crash, mute, duplication, scope crossover,
repeat migration, or unrelated-setting reset.

For downgrade, verify the old APK starts while new-format preferences exist,
legacy Full Range data remains recoverable, complete presets survive outside
app data, and upgrading again can restore all three banks. Do not claim
downgrade safety until these cases pass.

## Automated and native testing

Current JVM coverage includes state validation, duplicate UUID rejection,
preset version/identity round trips, bounded history, graph mapping, and
biquad-response golden checks for peaking, shelves, cascades, channels, empty
banks, and sample-rate Nyquist.

Still required before stable:

- native offline impulse/sine/stereo harness;
- invalid-state rollback and empty-bank passthrough;
- channel targeting and final L/R correction;
- sample-rate rebuild and NaN/Infinity checks;
- release minification/JNI packaging;
- migration fixtures and process-death instrumentation;
- 1,000-operation stress loops and leak/memory observation.

## Canary checklist

- [ ] Fresh and upgrade installation
- [ ] Full, Low, Mid, combined, and empty scopes
- [ ] Left, Right, and Both targeting
- [ ] Add, edit, delete, and cancel
- [ ] Graph drag commit and cancellation
- [ ] Undo and redo
- [ ] Preset export/import and scoped APO import
- [ ] Reset, rejection, and last-known-good recovery
- [ ] App/service restart and device reboot
- [ ] Pause/resume, track change, seek, sleep/wake, and route changes
- [ ] 44.1/48/96 kHz where supported
- [ ] Maximum filters and rapid operations
- [ ] Diagnostic report review/export
- [ ] 1280 x 480 control reachability
- [ ] No burst, mute, stale filter, data loss, or channel reversal

## Performance budget

No measured RC numbers are available yet. Record on the target head unit:

| Metric | Result |
|---|---|
| Native complete-state apply latency | Not measured |
| Graph redraw time at maximum filters | Not measured |
| Editor startup time | Not measured |
| Preset parse/apply time | Not measured |
| Migration time | Not measured |
| Memory for 20-state history | Not measured |
| CPU impact at maximum filters | Not measured |
| Release APK size delta | Not measured |

Do not replace these entries with “no impact” without measurements.

## Beta feedback template

- Build/commit:
- Device and Android version:
- Sample rate and audio route:
- Selected PEQ scope:
- Action performed:
- Expected result:
- Actual result:
- Reproducibility:
- Did restart help?
- Was audio muted or distorted?
- Diagnostic report attached? (Do not attach music or personal files.)
- Screenshot, if useful:

## User-facing release notes draft

This preview adds separate Full Range, Low, and Mid parametric EQ. Full Range
works before the crossover split; Low and Mid tune their post-crossover
branches. Filters can be edited directly on the graph, targeted to Left, Right,
or both channels, and moved or copied between scopes. Complete presets,
scope-based Equalizer APO import/export, and bounded Undo/Redo are included.

Legacy Full Range settings are backed up during migration. Successful settings
also maintain a last-known-good copy for recovery. This is a canary build:
export a complete preset before downgrading, and report problems with the
sanitised diagnostic report.

Known limits include 16 filters per scope, peaking/low-shelf/high-shelf types,
no safe runtime legacy-processor toggle, and possible differences between the
linear graph and measured output while compressor, limiter, or other nonlinear
stages are active.
