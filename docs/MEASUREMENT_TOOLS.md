# Measurement tools: crossover bypass, measurement neutral, engine status, self-test

Measurement setups you can trust: every state the UI shows comes from the native engine, and
"neutral" is proven by measuring an offline copy of the running chain.

## Crossover bypass (slot 309)

`INDEX_CROSSOVER_BYPASS` / `kCrossoverBypass`. On: the band split is removed entirely. The input
stages (DC blocker, Mono Bass, Virtual Bass, Pre EQ, headroom, MBC, virtual centre) feed the
summed-bus stages (tilt, post gain, channel mute, convolver, master limiter, volume, loudness, the
hardware swap) as one full-range stereo path. Nothing of the bands runs: no routing matrix,
crossover or subsonic filter, band EQ, all-pass, band delay, band gain, per-output polarity or
mute, bus limiter or measurement mute, and **no added latency** (the band chain's alignment kernel
and bus-limiter look-ahead are part of the bands). Switching it crossfades over 20 ms; the bands
restart from a clean state when they come back, and the BW2/BW4 pairing is left alone, so the
split returns exactly as set. Persisted with the rest of the config; the Crossovers page's
"Crossover" switch writes it, and the status strip shows "Xover off".

Replaces the retired per-band passthroughs (slots 1, 2, 291), which are held at 0 on every load.

## Measurement neutral (slots 310..311)

`INDEX_MEASUREMENT_NEUTRAL` turns on, and `INDEX_NEUTRAL_ALLOW` holds the stages kept running
under it (a `kStage*` bitmask). Native applies it to a **copy** of the config inside
`configure()` (`NativeBmwDspProcessor::applyMeasurementNeutral()`), so every neutralised stage goes
through its normal parse, diff, ramp and rebuild, and the saved settings are never touched:
switching neutral off is the same config without the flag, restored exactly (also after a
restart). The neutral set is `kStage*` bits 0..21:

Pre EQ, crossover (forces the bypass), band EQ, band gains, band delays, polarity, all-pass,
subsonic, routing, bus limiters, Mono Bass, virtual centre, Virtual Bass, MBC, tilt, convolver IR,
master limiter, loudness, headroom (manual or automatic), post gain, the 3 Hz DC blocker and
measurement mute.

Not in it: the **master volume** (the car's only adjustable volume while playing), channel mute
and the built-in signal generator (so one side can still be measured with the internal sweep).
Pre EQ, band EQ and the DC blocker aren't config slots: they crossfade (20 ms) under
`Params::preEqOn / bandEqOn / dcBlockerOn`. Any stage can be run again while neutral is on: its
bit in the allow mask (Native DSP Truth, "run in neutral"). Switching neutral on or off clears it.

The Kotlin graph model mirrors the masking (`MeasurementNeutral.effective()`), checked against the
engine by the `measurement_neutral_*` golden scenarios.

## Engine status

`NativeBmwDspProcessor::readEngineStatus()` (lock-free; JNI `getEngineStatus`; also the truth
snapshot's last block): the stages actually running (`kStage*` bits, from the installed state:
the convolver only with an IR running, PEQ only with an active band, band stages only while the
split runs), neutral and its allow mask, the crossover (0 bypassed / 2-way / 3-way), tilt, limiter
threshold, MBC bands, all-pass outputs, master volume, headroom, sample rate and an install
revision. Published by the owner after every install. The status strip, the front-page chips and
Native DSP Truth's "Active stages" read only this; with no engine they show "—".

## Self-test

`NativeBmwSelfTest.h`. A fresh processor is built from what the live one last accepted (config as
sent, PEQ, sample rate, the running IR), off the audio thread. An impulse goes into Left, then
Right; Left in is compared with the car's Left speaker (the hardware output's right channel). Over
20 Hz .. 20 kHz it reports the worst magnitude and phase deviation from a wire (latency taken out
first and reported separately), and the crosstalk. Verified: within ±0.01 dB and ±0.1°, latency 0,
crosstalk below -80 dB. Otherwise each active stage is tried alone under neutral (band stages with
the crossover, against the crossover alone) and those still altering the signal are listed. The
master volume is divided back out and reported. Compressors, limiters and the virtual stages are
listed as level-dependent when active: an impulse doesn't prove them. Runs automatically when
neutral is switched on (or a stage is let back on), and on demand from Native DSP Truth.
