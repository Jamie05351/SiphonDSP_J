# Stereo and per-band convolution

The convolver page selects one of two architectures. The legacy stereo IR stays in
its original storage location. Low, Mid and High have separate stored correction IRs.
Mode selection and gain trim are retained separately. All three band slots must load
before per-band mode can be selected. The convolver's main enable switch bypasses
whichever architecture is selected.

- **Stereo:** existing bands and bus limiters → band sum → tilt/post gain → stereo FIR → master limiter.
- **Per-band:** existing crossovers/EQ/all-pass/delay/gain → each band's stereo FIR → bus limiter → band sum → tilt/post gain → master limiter.

The IRs in the second architecture are correction-only. Existing crossover filters
remain enabled; the package does not change frequencies, slopes, delays, EQ or
polarity. A package crossover-frequency mismatch requires explicit acknowledgement.
Its baseline may also contain EQ/delay/gain differences that cannot be detected from
crossover metadata alone: retain the complete measured baseline.

Switching architectures fades to silence, clears histories, selects the new path,
then fades back in. The native engine never applies the legacy IR after the three
band IRs. Every band uses the same fixed 1024-frame partition latency. Different
intentional delays inside the IRs are retained; do not independently realign files.
The designer exports a common IR delay. Its default 8192-frame delay plus the
1024-frame buffer is approximately **192 ms at 48 kHz**. This is latency, not CPU
processing time. Check video/phone/navigation usability on the actual unit.

Crossovers bypassed means their software branches, including band FIRs, are bypassed.
The page rejects selecting band mode while crossovers are bypassed and warns if that
setting changes later. Use stereo mode if full-range convolution with no band split
is required.

## Measurements and Windows program

See [the Windows designer instructions](../tools/band-ir-designer/README.md) for the
complete measurement and import workflow. Its model matches the current two-output
path: software bands are summed to stereo before the BMW system splits them to
physical speakers. No separate six-channel speaker output is implied.

Use stationary, timing-referenced software-band-solo sweeps with all physical
speakers connected, convolution bypassed, crossovers/EQ/delays unchanged, and gain
fixed. Collect Low/Mid/High for left and right at each position in both seats.
Collect left/right combined sweeps to check the measured complex branch sum. The
reference chirp must remain available regardless of band muting. In default magnitude
mode the opposite side can remain available as reference: mute unwanted bands only
on the measured side. Use a consistent reference ID per measured channel. Mixed-phase
mode needs one common L/R reference through an independent reference path or electrical
loopback. IDs are user assertions; software cannot verify the physical reference.
The designer assumes diagonal stereo routing: mono bass and cross-channel mixing
must be off during measurement and playback of its generated filters. Arbitrary
cross-channel matrix optimisation is not implemented.

The designer accepts REW frequency/SPL/phase text exports or calibrated mono impulse
WAVs with a preserved common time origin. Save `.mdat` originals, but export their
measurements: direct `.mdat` decoding is not supported. It produces `low.wav`,
`mid.wav`, `high.wav` and a versioned ZIP package with configuration/report metadata.
Load the ZIP on the convolver page, inspect the three slots, then select Per-band.
Individual mono/stereo WAV loading remains available. Set band-mode gain to 0 dB
to reproduce the designer prediction. Band-mode trim is attenuation-only, so a
positive shared trim cannot independently hit different IR peak caps and change
the relative band gains.

The designer fits both seats with explicit weights, bounded correction and
regularization. It reports predicted improvement and deterioration per position,
including held-out measurements, using the actual finite exported taps. Magnitude
correction is the default; mixed-phase fitting is opt-in and depends on valid common
phase/timing information. These are predictions; verify them with fresh acoustic
measurements. No real-car correction filters ship without measurements.

## Verification

- Host native tests exercise unchanged legacy output, unity band reconstruction,
  common algorithmic latency, mode/file isolation, invalid/incomplete sets and rate changes.
- Kotlin tests cover branch response modelling, slot persistence and ZIP validation.
- Designer tests cover multi-seat fitting, phase, held-out data, common normalization
  and compatibility with Android's ZIP/WAV contract.
- `.github/workflows/band-ir-designer.yml` tests and packages the desktop application
  on Windows; the existing Android workflow compiles/tests the app.

Phone/head-unit performance and acoustic results require target-device verification.
