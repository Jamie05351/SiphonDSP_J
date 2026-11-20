# J_DSP Multi-seat Band IR Designer

A Windows desktop application for designing **correction-only** Low/Mid/High stereo
FIRs from measurements at both seats. Existing J_DSP crossovers, EQ and delays remain
in the measured baseline. It does not synthesize another crossover or assume six
independent physical speaker outputs.

## Start on Windows

Install Python 3.11 or newer from python.org, including the Python launcher and
Tcl/Tk. Double-click **Run-Designer.bat**. The first run creates a local virtual
environment and downloads the dependencies; subsequent runs use that environment.
The program works offline after dependencies are installed. To build a standalone
folder containing `BandIRDesigner.exe`, run **Build-Windows.bat** on Windows. Copy
the entire resulting `dist/BandIRDesigner` folder, not just the executable.

The repository's `.github/workflows/band-ir-designer.yml` runs the tests and builds the
Windows executable using pinned GitHub Actions. It produces a downloadable
`BandIRDesigner-Windows` artifact when the workflow succeeds. A reference template is
also kept in `packaging/windows-build.yml`. A configured workflow is not evidence of a
successful Windows build; check its actual result.

## Required measurement topology

The current J_DSP pipeline sums its software bands back into stereo before the BMW
factory crossover/amplifier/speaker system. Therefore the designer needs acoustic
responses of **each software branch**, measured through the complete physical system.
Do not substitute isolated physical-woofer or midrange sweeps for these branch sweeps.

1. Save the current configuration. Bypass all correction IRs, retain the chosen
   crossovers, EQ and delay settings, and use an unchanged playback/measurement gain.
   Do not change polarity, the factory volume, preamp or headroom between sweeps.
   Bypass dynamics that would make the sweep level-dependent; measure at a level that
   does not invoke limiting. **Mono bass and cross-channel routing must be off**, and
   remain off when using this designer's filters. The model assumes diagonal stereo
   routing: a left sweep does not drive right-side software outputs, or vice versa.
   Record these baseline settings with the project.
2. Leave **all physical speakers connected**. Use J_DSP band solo plus channel solo:
   Low L, Mid L, High L, then Low R, Mid R, High R. Preserve the routing and the final
   hardware swap exactly as they will be used for playback.
3. For default magnitude design, use one unchanged timing-reference speaker per
   measured channel. With normal diagonal stereo routing, use Right as the reference
   for Left sweeps, and Left for Right sweeps. Mute unwanted bands **only on the side
   being measured**; leave the reference side's bands unmuted. Do not use a global
   band-solo control that removes the reference chirp. Confirm that the reference
   side is silent during the actual sweep (no mono bass/crossfeed). Do not independently
   align or move individual impulse zero times.
   **Mixed-phase fitting requires one common reference across both channels**: use
   an independent acoustic reference path or a suitable electrical loopback. An
   opposite-speaker reference for each channel does not establish a shared L/R time origin.
4. Repeat at stationary mic positions near the driver's and passenger's head positions.
   Start with the centre and two nearby positions per seat: 6 positions × 6 sweeps =
   **36 band-solo measurements**. An additional held-out position per seat is useful.
5. At each position also measure all software bands together per channel. Import these
   as `combined`: they check agreement with the complex sum of the branch measurements.
   Combined sweeps are optional validation; they are not substituted for branch triplets.
6. Save the original REW `.mdat` for your records. Export each measurement as REW
   frequency/SPL/phase text (`.txt`, `.csv`, `.frd`), including the full correction range.
   **Native `.mdat` parsing is not implemented.** Alternatively import mono impulse WAVs
   at the project's sample rate, with a common preserved time origin and known SPL
   calibration offset. WAV amplitude alone is not calibrated SPL.

The application requires a matching low/mid/high triplet for each seat/position/channel,
and both seats and both channels in the design set. Use the same timing-reference ID
for all files of a measured channel. Mixed-phase mode additionally requires the same
reference ID across both channels. These fields record your assertions; the application
cannot independently verify the actual reference or routing. Phase is required even in
magnitude mode because the acoustic band sum depends on relative phase.

## Workflow and interpretation

- Assign seat, position, channel and **software band**, then add each file. Enter a
  calibration offset of zero for normal REW calibrated SPL exports. For a normalized
  impulse WAV, supply the offset that maps the impulse's transfer amplitude to measured
  SPL under the unchanged sweep level. Unknown calibration makes absolute target level
  and predicted gain unreliable; use calibrated REW exports if uncertain.
- Mark complete held-out triplets as **Validation-only** to exclude them from the fit
  while still reporting their predicted result. Each seat's positions share that seat's
  weight; collecting more driver positions does not silently give the driver more weight.
- Enter existing crossover frequencies for the exported manifest. These fields are
  metadata, not newly generated filters: the actual measured branch responses are the
  model used for fitting. A mismatch between these fields and the baseline is not
  automatically detectable.
- Set target SPL and broad bass/treble target offsets. Default boost limit is 0 dB.
  Regularization favours smaller changes; higher values are more conservative.
- Calculate, then select **every seat/position/channel** in Preview. The upper plot
  shows measured branch sum, actual finite-FIR prediction and target. An imported
  combined sweep is displayed as a consistency check. The lower plot shows exported
  impulses and their common delay, including pre/post ringing.
- The report flags worsening RMS error and reports the fraction of bins that worsen
  by more than 0.5 dB. A shared tune is a compromise: a lower overall error does not imply
  that every frequency or seat improves. These are linear predictions, not verified
  acoustic results, distortion measurements or amplifier/speaker protection ratings.

## Filter model

For each channel, position and frequency, let `H[b]` be the measured acoustic transfer
of software branch `b`. The predicted response is `sum(H[b] * K[b])`. The program fits
the three correction responses `K` jointly against both seats, with equal seat weight
by default and regularization toward `K=1`.

Default mode constrains each correction to a positive real gain and preserves the
baseline's phase in its fitting target. The exported symmetric FIR adds one common
linear-phase delay. It does not attempt full acoustic phase inversion. Experimental
mixed-phase mode fits complex corrections toward one specified arrival time relative
to the common timing reference, with bounded per-frequency magnitude; causality,
finite length and differing seat paths can limit or defeat that fit. Its prediction
must be reviewed carefully. It is not Dirac Live or an implementation of its algorithms.

Corrections are smoothed over log frequency and faded toward unity at the correction
range boundaries. They do not reject out-of-band content: existing crossovers do that.
All six channels use the same tap count and bulk delay. Their actual finite exported
coefficients, including truncation/windowing, are used for predictions. One common
normalization factor keeps the six sampled frequency-response peaks below unity;
individual IRs are **not** normalized independently. The report and plotted target
include that common attenuation. Restore playback loudness only after checking the
complete signal chain.

## Export and load

Export creates:

- `low.wav`, `mid.wav`, `high.wav`: stereo float32 correction IRs.
- `manifest.json`: format `siphondsp-band-ir`, version 1, `correction_only: true`,
  sample rate, taps, common delay, existing crossover metadata, fixed WAV filenames,
  per-band trim defaults and common attenuation.
- `report.json`: per-position before/predicted errors, deterioration flags, source
  filenames and limitations.
- `band-ir-package.zip`: all five files at the ZIP root.

Load the three WAVs into their matching J_DSP band slots (or import the package where
supported), select per-band convolution mode, and retain the exact measured baseline.
Do not independently peak-normalize the WAVs: that changes the relative band gains and
invalidates the prediction. Do not add another copy of the crossover inside the IRs.
Re-measure both seats with the correction enabled to validate the actual outcome.

## Tests

`python -m unittest discover -s tests -v` exercises synthetic shared-plant correction,
unity response, equal-seat weighting, held-out positions, incompatible seat reports,
common normalization, WAV/package export, mixed-phase finite output, and bad inputs.
These tests establish software behaviour, not performance in an actual car. Windows
packaging and target-device listening must be verified separately.
