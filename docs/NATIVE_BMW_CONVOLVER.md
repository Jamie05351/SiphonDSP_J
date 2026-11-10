# Convolver

A stereo FIR convolver, the **last** stage of the native chain: after the master limiter, the
master volume, loudness and the deliberate hardware L/R swap, just ahead of the output guard
(`NativeBmwDspProcessor::processChunk()`). Code: `app/src/main/cpp/engine/NativeBmwConvolver.{h,cpp}`.

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
  crossings, β = 10, cut at the lower Nyquist) with its level kept. The decoded source is kept,
  so a stream-rate change rebuilds the kernel without reading the file again.
- Everything that allocates — decoding, resampling, transforming partitions, the delay line —
  runs on the calling (control) thread. The finished kernel reaches the audio thread through a
  lock-free triple buffer; the audio thread hands the retired one back through the same buffer,
  and it is freed by the next publish, on the control thread. A rejected file leaves the current
  IR running.
- App side: `ConvolverIrStore` copies the picked file to `files/convolver/ir.wav` once
  `NativeDsp.checkConvolverIr()` accepts it; the service loads it at start and after every
  import or clear.

## Controls

Two config slots (schema growth 307 → 309): `v[307]` enable — off is a bypass — and `v[308]` the
IR gain trim, −30..+12 dB. Toggling, loading, swapping or unloading an IR **crossfades over
20 ms**; the gain follows a 10 ms one-pole. Fully off, or with no IR loaded, the stage is skipped
and the output is bit-identical to the chain without it (the parity check covers this).

Because the convolver runs after the limiter, an IR with gain above 0 dB at some frequency can
push peaks past it; the page shows the IR's peak response and **Match gain** sets the trim to
cancel it. UI: Output workspace → CONVOLVER page (`ConvolverScreen.kt`).
