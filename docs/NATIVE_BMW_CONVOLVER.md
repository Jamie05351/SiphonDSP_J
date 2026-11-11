# Convolver

A stereo FIR convolver, right after the band sum (per-output polarity/mute, tilt, post gain,
channel mute, measurement bus) and **ahead of the master limiter**, master volume, loudness, the
deliberate hardware L/R swap and the output guard (`NativeBmwDspProcessor::processChunk()`). It
runs on logical L/R, so the IR's left channel is the car's left. It sits ahead of the limiter
because a phase-correcting IR raises the crest factor without raising the magnitude: those peaks
must pass through the limiter. Code: `app/src/main/cpp/engine/NativeBmwConvolver.{h,cpp}`.

## Algorithm

Uniformly partitioned overlap-save (UPOLS) with a frequency-domain delay line.

- Partition = the processor's chunk, **64 frames** (`kChunkFrames`, pinned by a `static_assert`);
  FFT size 128.
- **No added latency.** Every call transforms `[previous block | block so far, zero-padded]`, so a
  sample's output is ready in the call that delivered it. The processor's full 64-frame chunks
  complete one block per call (one forward + one inverse FFT per channel, then the partition
  sums); a short trailing chunk costs one extra FFT pair, never extra delay.
- **Double precision throughout**: IR spectra, delay line, FFTs and the gain/crossfade maths.
  The FFT is [PFFFT](https://github.com/marton78/pffft) v1.1.0's double build (vendored in
  `engine/third_party/pffft`, FFTPACK/BSD-style licence; NEON on arm64, SSE2 on x86-64 hosts).
  The chain's float samples are widened on the way in and narrowed once on the way out.
- Cost grows linearly with IR length. The limit is **65,536 taps at the stream rate** (1,024
  partitions, ~1.37 s at 48 kHz), after trailing silence (below −120 dB re. peak) is trimmed.
  On an x86-64 host the maximum length costs about 15 % of one core at 48 kHz; typical
  room-correction IRs (4–16 k taps) cost a small fraction of that.

## IR loading

- WAV, **mono** (applied to both channels) or **stereo / dual mono** (L on L, R on R, no
  cross-feed). Any PCM / float format dr_wav decodes. More channels are rejected.
- An IR whose rate differs from the stream's is **resampled** (Kaiser-windowed sinc, 64 zero
  crossings, β = 10, cut at the lower Nyquist, tabulated at 1024 points per crossing) with its
  level kept. Source files up to 384 kHz; the worst case (384 kHz → 48 kHz, 65,536 taps, stereo)
  takes ~0.3 s on an x86-64 host. The decoded source is kept, so a stream-rate change rebuilds
  the kernel without reading the file again — outside the convolver's lock, with a generation
  check so a load or clear that lands meanwhile wins.
- Everything that allocates — decoding, resampling, transforming partitions, the delay line —
  runs on the calling (control) thread. The finished kernel reaches the audio thread through a
  lock-free triple buffer; the audio thread hands the retired one back through the same buffer,
  and it is freed by the next publish, on the control thread. A rejected file leaves the current
  IR running.
- Each kernel build (load or stream-rate rebuild) records the IR's **bulk latency**: the index
  of its largest-magnitude sample across both channels, in frames at the stream rate, and in ms
  (`ConvolverInfo::latencyFrames` / `latencyMs`, cleared on unload). The CONVOLVER page shows it.
  This is the IR's own delay; the convolution adds none on top.
- App side: `ConvolverIrStore` copies the picked file to `files/convolver/ir.wav` once
  `NativeDsp.checkConvolverIr()` accepts it; the service loads it at start and after every
  import or clear.

## Controls

Two config slots (schema growth 307 → 309): `v[307]` enable — off is a bypass — and `v[308]` the
IR gain trim, −30..+12 dB. Toggling, loading, swapping or unloading an IR **crossfades over
20 ms**; the gain follows a 10 ms one-pole. Fully off, or with no IR loaded, the stage is skipped
and the output is bit-identical to the chain without it (the parity check covers this).

The stage **caps its gain at the IR's peak response**: the gain applied is `min(trim, −peak dB)`,
so the IR never lifts any frequency above unity (the limiter after it catches the time-domain
peaks a phase-only change raises; the cap keeps magnitude boost from driving it). A boosting IR is brought down to 0 dB at its loudest frequency even at 0 dB trim; the trim
can only go below that. The page shows the gain actually applied and flags when the cap is in
effect. UI: Output workspace → CONVOLVER page (`ConvolverScreen.kt`).

## Response graphs

With **Show convolver in graphs** on (the PEQ graph's ⋮ menu; `PeqGraphPreferences.
showConvolverInGraphs`, off by default), the PEQ and crossover graphs multiply the running IR's
complex response into the sum, per logical channel, at the gain the stage applies
(`min(trim, −peak dB)`). The convolver off, or no IR running, contributes nothing either way.

The graphs' grids differ (point count, top frequency), so the app passes its frequency array in:
`NativeDsp.getConvolverResponse()` evaluates the stream-rate taps kept from the last kernel build
(a direct DTFT, on the caller's thread, never the audio thread) with the bulk latency taken out,
so the phase shows the correction rather than a pure delay wrapping. `getConvolverGeneration()`
moves on every load, rate rebuild and clear; the graphs poll it once a second and refetch only
when it moves.
