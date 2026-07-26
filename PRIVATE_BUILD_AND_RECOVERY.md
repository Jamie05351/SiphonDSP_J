# Private build and PEQ recovery

This repository is maintained for Jamie's own Snapdragon 662 head unit
(1280 x 480, landscape, Rootless JamesDSP) and Samsung test devices. Do not
infer support for other hardware.

## Known-good baseline

- Commit: `0d23c18e6de5b5b20797dc2951f4043ed81bd904`
- Intended private tag: `private-peq-known-good-20260726`
- App version at baseline: 1.6.15 (version code 52)

Create the tag only after confirming the baseline APK still installs, starts
Rootless DSP, restores all three banks, and passes the audible checks below.

## Build locally

Requirements:

- JDK 17
- Android SDK compile SDK 36
- Android NDK 28.2.13676358 with its licence accepted
- CMake 3.22.1

Private installable preview:

```bash
./gradlew :app:assembleRootlessFullPreview
```

Output:

```text
app/build/outputs/apk/rootlessFull/preview/
```

The GitHub pull-request workflow builds the same Preview variant, runs the
existing `testRootlessFullDebugUnitTest` task, runs Preview lint, verifies the
packaged ARM64 native library, and uploads:

```text
SiphonDSP-private-<short-commit>.apk
```

Run it manually from GitHub Actions with **Build APKs -> Run workflow**. Open
the run, download the `SiphonDSP-private-<commit>` artifact, and verify the
commit in **PEQ -> Diagnostics** before installation.

The build is ARM64-only. It is not claimed to be byte-for-byte reproducible.
Diagnostics records version, commit, build type/time, ABI, Android/device,
screen size, service/handle state, sample rate, and PEQ recovery status.

## Install or update

Back up first, then install over the existing private app:

```bash
adb install -r SiphonDSP-private-<short-commit>.apk
```

Do not clear app data for a normal upgrade. Clear data only when deliberately
testing a fresh install, after exporting the complete private PEQ backup and any
other needed app configuration.

## Complete PEQ backup

In the PEQ editor:

1. Tap **Backup**.
2. Save `SiphonDSP-private-peq-backup.json` somewhere outside app-private data.
3. Keep at least one known-good copy with the APK/commit that created it.

Format version 1 includes the complete authoritative three-bank state, enabled
state, Full Range preamp, filter UUID/type/channel/value fields, graph overlay
and channel-display preferences, and a reserved list for saved presets. The
current app has no internal preset library, so portable exported presets remain
separate files and the reserved list is normally empty.

Imports are capped at 1 MB, parsed as data only, previewed, fully validated, and
atomically applied. Graph display preferences change only after the DSP state
applies successfully.

To restore:

1. Tap **Restore backup**.
2. Select the JSON file.
3. Review Full/Low/Mid counts and preamp.
4. Tap **Restore**.

A malformed, future-version, oversized, or invalid backup leaves the active and
saved PEQ unchanged.

## Last-known-good and automatic recovery

Every successful committed PEQ state stores primary and last-known-good
snapshots together. Drafts and rejected operations never replace them.

Cold start tries:

1. persisted complete state;
2. last-known-good complete state;
3. safe empty PEQ (disabled, 0 dB preamp, all banks empty).

Rejected serialized state is preserved before safe fallback. Other BMW DSP
settings are not reset. Debug builds expose **Restore last known good** inside
**Diagnostics**; it uses the normal complete-state atomic apply.

## Roll back

1. Export the complete PEQ backup and portable preset files.
2. Check out the known-good commit or confirmed tag.
3. Build:

   ```bash
   git switch --detach 0d23c18e6de5b5b20797dc2951f4043ed81bd904
   ./gradlew :app:assembleRootlessFullPreview
   ```

4. Install with `adb install -r`.
5. Keep app data unless the older build fails to start.
6. If data must be cleared, reinstall, then upgrade back to the newer APK and
   restore the complete backup. Older builds may not understand Low/Mid banks;
   never flatten them into Full Range.

There is no runtime legacy DSP fallback and no second active processor.

## Diagnostics and common symptoms

Tap **Diagnostics** to review and optionally export a local sanitised report.
It contains no audio, filter values, imported contents, paths, accounts, or
music metadata.

- **Native handle unavailable:** restart Rootless DSP capture, then reopen PEQ.
- **Frequency rejected:** verify it is below Nyquist at the active sample rate.
- **Backup rejected:** keep the old file; check size/version and export a fresh
  backup from the APK that created it.
- **Safe fallback used:** export Diagnostics, try last-known-good in a debug
  build, and keep the rejected-state backup for investigation.
- **Channel sounds reversed:** stop testing and return to the known-good APK;
  do not compensate by swapping UI labels or creating another mapping.

## Snapdragon 662 head-unit checklist

- [ ] Fresh install and install-over-existing
- [ ] App launch and Rootless DSP start
- [ ] Full only: 1000 Hz, -12 dB, Q 1.0
- [ ] Low only: 80 Hz, -12 dB, Q 1.0
- [ ] Mid only: 1000 Hz, -12 dB, Q 1.0
- [ ] Low+Mid and all three together
- [ ] Empty banks; empty Full does not block Low/Mid
- [ ] Left, Right, and Both targeting in every bank
- [ ] Add/edit/delete and both Cancel paths
- [ ] Graph drag and cancellation; no parent scroll
- [ ] Undo and redo
- [ ] Preset and scoped import/export
- [ ] Complete backup export/restore and invalid-backup rejection
- [ ] Last-known-good restore and safe fallback
- [ ] Bypass controls already present in the installed build
- [ ] App/service restart and reboot
- [ ] Pause/resume, track change, seek, and sample-rate change
- [ ] Maximum filters and invalid filter/preset rejection
- [ ] Confirm/Cancel reachable at 1280 x 480
- [ ] No burst, stuck mute, channel reversal, or state loss

Record editor open time, graph/drag response, apply delay, preset/backup restore
time, CPU at maximum filters, and audio stability. Do not claim zero impact
without measurement.

## Samsung smoke test

- [ ] App launches and editor opens
- [ ] Scope switching and graph rendering
- [ ] Add/edit/delete
- [ ] Import/export and backup/restore
- [ ] No crash or obvious portrait/tablet layout corruption

## Private readiness decision

Current status: **Ready for Jamie's testing with known limitations**, only after
the draft PR's CI produces the private APK. Daily-use readiness additionally
requires the complete Snapdragon checklist.

Unresolved:

- local workstation build is blocked until NDK 28.2 licence installation;
- no device results or sample-rate measurements are recorded yet;
- native offline harness coverage remains incomplete;
- the backup reserves saved-preset storage, but current presets are portable
  files rather than an internal managed library.
