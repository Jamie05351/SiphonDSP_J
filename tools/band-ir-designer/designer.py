"""Correction-only FIR design for software bands feeding a shared stereo output.

The measured branch responses include the existing electrical and acoustic plant.
No crossover is synthesized here. All spectra retain the measurement time origin.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from pathlib import Path
import json
import re
import zipfile
import numpy as np
from scipy.io import wavfile
from scipy.ndimage import gaussian_filter1d
from scipy.optimize import lsq_linear

BANDS = ("low", "mid", "high")
CHANNELS = ("L", "R")


@dataclass
class Measurement:
    path: str
    seat: str
    position: str
    channel: str
    band: str
    timing_reference: str
    validation: bool = False
    calibration_db: float = 0.0


@dataclass
class Options:
    sample_rate: int = 48000
    taps: int = 16384
    latency_frames: int = 8192
    low_mid_hz: float = 150.0
    mid_high_hz: float = 3500.0
    min_hz: float = 25.0
    max_hz: float = 16000.0
    target_db: float = 75.0
    bass_db: float = 3.0
    treble_db: float = -6.0
    max_boost_db: float = 0.0
    max_cut_db: float = 12.0
    regularization: float = 0.03
    mixed_phase: bool = False
    target_arrival_ms: float = 0.0
    driver_weight: float = 1.0
    passenger_weight: float = 1.0

    def validate(self):
        if self.sample_rate not in (44100, 48000):
            raise ValueError("Sample rate must be 44100 or 48000 Hz")
        if self.taps < 1024 or self.taps > 65536 or self.taps & (self.taps - 1):
            raise ValueError("Taps must be a power of two from 1024 to 65536")
        if self.sample_rate == 44100 and self.taps > 32768:
            raise ValueError("44100 Hz exports are limited to 32768 taps so they also load at the head unit's 48000 Hz rate")
        if not self.taps // 4 <= self.latency_frames <= 3 * self.taps // 4:
            raise ValueError("Latency must be between one quarter and three quarters of the tap count")
        vals = list(asdict(self).values())
        if not all(np.isfinite(x) for x in vals):
            raise ValueError("All settings must be finite")
        if not 10 <= self.min_hz < self.low_mid_hz < self.mid_high_hz < self.max_hz < self.sample_rate / 2:
            raise ValueError("Require 10 <= minimum < low/mid < mid/high < maximum < Nyquist")
        if self.low_mid_hz < 20:
            raise ValueError("Low/Mid crossover metadata must be at least 20 Hz for J_DSP packages")
        if not 0 <= self.max_boost_db <= 6 or not 0 < self.max_cut_db <= 24:
            raise ValueError("Boost must be 0..6 dB and cut greater than 0 up to 24 dB")
        if not 0.0001 <= self.regularization <= 10:
            raise ValueError("Regularization must be 0.0001..10")
        if not -30 <= self.bass_db <= 30 or not -30 <= self.treble_db <= 30:
            raise ValueError("Target bass/treble offsets must be within +/-30 dB")
        if not -120 <= self.target_db <= 160 or abs(self.target_arrival_ms) > 1000:
            raise ValueError("Target level must be -120..160 dB and arrival within +/-1000 ms")
        if not -200 <= self.target_db <= 200:
            raise ValueError("Target level must be within -200..200 dB")
        if self.driver_weight <= 0 or self.passenger_weight <= 0:
            raise ValueError("Both seat weights must be positive")


def read_response(m: Measurement, frequencies: np.ndarray, sample_rate: int, reference_db: float):
    """Read REW SPL+phase text or a calibrated impulse WAV, retaining absolute phase."""
    if m.channel not in CHANNELS or m.band not in (*BANDS, "combined"):
        raise ValueError("Choose channel L/R and band low/mid/high/combined")
    if m.seat not in ("driver", "passenger") or not m.position.strip() or not m.timing_reference.strip():
        raise ValueError("Every measurement needs driver/passenger, position and timing-reference ID")
    if not np.isfinite(m.calibration_db) or abs(m.calibration_db) > 300:
        raise ValueError("Calibration offset must be finite and within +/-300 dB")
    p = Path(m.path)
    if p.stat().st_size > 128 * 1024 * 1024:
        raise ValueError(f"{p.name}: measurement file exceeds 128 MiB")
    if p.suffix.lower() == ".wav":
        rate, samples = wavfile.read(p)
        if rate != sample_rate:
            raise ValueError(f"{p.name}: WAV sample rate {rate} differs from project {sample_rate}")
        if samples.ndim != 1 or samples.size < 16 or samples.size > 2**22:
            raise ValueError(f"{p.name}: use a mono impulse WAV of 16..4194304 samples")
        if np.issubdtype(samples.dtype, np.integer):
            if samples.dtype == np.uint8:
                samples = (samples.astype(float) - 128) / 128
            else:
                samples = samples.astype(float) / (np.iinfo(samples.dtype).max + 1)
        else:
            samples = samples.astype(float)
        if not np.all(np.isfinite(samples)) or np.max(np.abs(samples)) < 1e-12:
            raise ValueError(f"{p.name}: impulse is silent or non-finite")
        n = max(2**18, 1 << (len(samples) - 1).bit_length())
        h = np.fft.rfft(samples, n)
        f = np.fft.rfftfreq(n, 1 / rate)
        # Preserve complex response rather than interpolating through wrapped phase.
        result = np.interp(frequencies, f, h.real) + 1j * np.interp(frequencies, f, h.imag)
        return result * 10 ** ((m.calibration_db - reference_db) / 20)
    if p.suffix.lower() not in (".txt", ".csv", ".frd"):
        raise ValueError(f"{p.name}: export REW frequency/SPL/phase text; .mdat is not supported")
    rows = []
    for line in p.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith(("*", "#", "//", ";")):
            continue
        parts = re.split(r"[\s,;]+", line)
        try:
            row = [float(x) for x in parts[:3]]
        except ValueError:
            continue
        if len(row) < 3:
            raise ValueError(f"{p.name}: phase column is required to sum the measured bands")
        rows.append(row)
    a = np.asarray(rows, dtype=float)
    if a.ndim != 2 or len(a) < 8 or not np.all(np.isfinite(a)):
        raise ValueError(f"{p.name}: need at least eight finite frequency/SPL/phase rows")
    if np.any(a[:, 0] <= 0) or np.any(np.diff(a[:, 0]) <= 0):
        raise ValueError(f"{p.name}: frequencies must be positive and strictly increasing")
    if a[0, 0] > frequencies[0] or a[-1, 0] < frequencies[-1]:
        raise ValueError(f"{p.name}: response must cover {frequencies[0]:.1f}..{frequencies[-1]:.1f} Hz")
    if np.max(a[:, 1]) < -180:
        raise ValueError(f"{p.name}: response appears silent")
    if np.any(abs(a[:, 1]) > 500):
        raise ValueError(f"{p.name}: magnitude values exceed the supported +/-500 dB range")
    mag = np.interp(np.log(frequencies), np.log(a[:, 0]), a[:, 1])
    # REW may export wrapped OR already-unwrapped phase. Do not fold the latter's
    # real delay back into a short one when adjacent high-frequency steps exceed pi.
    phase_values = np.deg2rad(a[:, 2])
    if np.all(abs(a[:, 2]) <= 180.000001):
        phase_values = np.unwrap(phase_values)
    phase = np.interp(np.log(frequencies), np.log(a[:, 0]), phase_values)
    return 10 ** ((mag + m.calibration_db - reference_db) / 20) * np.exp(1j * phase)


def target_db(f, o):
    # Broad target anchors, independent of the existing crossover slopes.
    return np.interp(np.log2(f), np.log2([20, 100, 1000, 20000]), [o.bass_db, 0, 0, o.treble_db])


def group_measurements(measurements, f, o):
    if not measurements:
        raise ValueError("Import measurement files first")
    references = {m.timing_reference.strip() for m in measurements}
    for channel in CHANNELS:
        refs = {m.timing_reference.strip() for m in measurements if m.channel == channel}
        if refs and (len(refs) != 1 or not next(iter(refs))):
            raise ValueError("Use one unchanged acoustic timing-reference ID for every measurement of each channel")
    if o.mixed_phase and len(references) != 1:
        raise ValueError("Mixed-phase fitting requires one common timing reference across L and R")
    groups = {}
    for m in measurements:
        key = (m.seat, m.position, m.channel)
        group = groups.setdefault(key, {"validation": m.validation, "responses": {}, "paths": {}})
        if group["validation"] != m.validation:
            raise ValueError(f"{key}: all bands at a position must have the same validation flag")
        if m.band in group["responses"]:
            raise ValueError(f"{key}: duplicate {m.band} measurement")
        group["responses"][m.band] = read_response(m, f, o.sample_rate, o.target_db)
        group["paths"][m.band] = str(Path(m.path).resolve())
    for key, g in groups.items():
        if any(b not in g["responses"] for b in BANDS):
            raise ValueError(f"{key}: require Low, Mid and High SOFTWARE-band-solo sweeps with all physical speakers connected")
    for ch in CHANNELS:
        for seat in ("driver", "passenger"):
            if not any(k[0] == seat and k[2] == ch and not g["validation"] for k, g in groups.items()):
                raise ValueError(f"Missing design measurement triplet: {seat} {ch}")
    return groups


@dataclass
class Design:
    options: Options
    frequencies: np.ndarray
    impulses: np.ndarray  # band, sample, channel
    responses: np.ndarray  # band, channel, frequency; ACTUAL exported FIR
    groups: dict
    target: np.ndarray
    common_gain_db: float
    report: dict


def _db(x):
    return 20 * np.log10(np.maximum(np.abs(x), 1e-12))


def design(measurements: list[Measurement], o: Options) -> Design:
    o.validate()
    f = np.geomspace(o.min_hz, o.max_hz, 768)
    groups = group_measurements(measurements, f, o)
    target = 10 ** (target_db(f, o) / 20)
    correction = np.ones((3, 2, len(f)), complex)
    lower, upper = 10 ** (-o.max_cut_db / 20), 10 ** (o.max_boost_db / 20)
    for ci, ch in enumerate(CHANNELS):
        entries = [(k, g) for k, g in groups.items() if k[2] == ch and not g["validation"]]
        counts = {s: sum(k[0] == s for k, _ in entries) for s in ("driver", "passenger")}
        weight = np.array([(o.driver_weight if k[0] == "driver" else o.passenger_weight) / counts[k[0]] for k, _ in entries])
        weight /= weight.sum()
        h = np.array([[g["responses"][b] for b in BANDS] for _, g in entries])
        for j, hz in enumerate(f):
            a = h[:, :, j]
            baseline = a.sum(axis=1)
            desired_phase = np.exp(-2j * np.pi * hz * o.target_arrival_ms / 1000) if o.mixed_phase else np.exp(1j * np.angle(baseline))
            wanted = target[j] * desired_phase
            aw = a * np.sqrt(weight[:, None])
            rhs = (wanted - baseline) * np.sqrt(weight)
            strength = max(float(np.sum(abs(aw) ** 2)), 1e-12) * o.regularization
            if o.mixed_phase:
                delta = np.linalg.solve(aw.conj().T @ aw + np.eye(3) * strength, aw.conj().T @ rhs)
                k = 1 + delta
                k = np.exp(1j * np.angle(k)) * np.clip(abs(k), lower, upper)
            else:
                real_a = np.vstack((aw.real, aw.imag, np.eye(3) * np.sqrt(strength)))
                real_rhs = np.concatenate((rhs.real, rhs.imag, np.zeros(3)))
                k = 1 + lsq_linear(real_a, real_rhs, bounds=(lower - 1, upper - 1), tol=1e-7).x
            correction[:, ci, j] = k
    # Smooth over roughly 1/6 octave to avoid fitting narrow spatial cancellations.
    sigma = max(0.5, (len(f) - 1) / np.log2(f[-1] / f[0]) / 12)
    if o.mixed_phase:
        correction = gaussian_filter1d(correction.real, sigma, axis=2) + 1j * gaussian_filter1d(correction.imag, sigma, axis=2)
    else:
        correction = 10 ** (gaussian_filter1d(_db(correction), sigma, axis=2) / 20)
    correction = correction.astype(complex)
    correction = np.exp(1j * np.angle(correction)) * np.clip(abs(correction), lower, upper)
    # Fade corrections to unity at boundaries. The FIR itself passes out-of-band content;
    # the existing crossover is solely responsible for rejecting it.
    edge = np.minimum(np.clip(np.log2(f / f[0]) * 2, 0, 1), np.clip(np.log2(f[-1] / f) * 2, 0, 1))
    correction = 1 + edge * (correction - 1)
    # Match/exceed the native loader's 8x oversampled peak check so a narrow
    # inter-bin peak does not trigger different normalization in individual slots.
    fft_n = max(65536, o.taps * 8)
    fft_f = np.fft.rfftfreq(fft_n, 1 / o.sample_rate)
    impulses = np.zeros((3, o.taps, 2), dtype=np.float64)
    for b in range(3):
        for c in range(2):
            k = correction[b, c]
            spectrum = np.interp(fft_f, f, k.real, left=1, right=1) + 1j * np.interp(fft_f, f, k.imag, left=0, right=0)
            circular = np.fft.irfft(spectrum, fft_n)
            impulse = np.roll(circular, o.latency_frames)[:o.taps]
            # A wide flat centre leaves the common delay impulse untouched.
            from scipy.signal.windows import tukey
            impulses[b, :, c] = impulse * tukey(o.taps, 0.2)
    # One normalization shared by all six channels, never per-file/per-channel normalization.
    spectra = np.fft.rfft(impulses, fft_n, axis=1)
    peak = float(np.max(np.abs(spectra)))
    common_gain = min(1.0, 0.999 / max(peak, 1e-15))
    impulses *= common_gain
    impulses = impulses.astype(np.float32)
    actual_fft = np.fft.rfft(impulses.astype(float), fft_n, axis=1)
    # Remove known bulk delay before interpolation: interpolating fast-rotating complex
    # bins directly would invent amplitude loss between bins even for a pure delay.
    actual_fft *= np.exp(2j * np.pi * fft_f * o.latency_frames / o.sample_rate)[None, :, None]
    responses = np.empty((3, 2, len(f)), complex)
    for b in range(3):
        for c in range(2):
            responses[b, c] = np.interp(f, fft_f, actual_fft[b, :, c].real) + 1j * np.interp(f, fft_f, actual_fft[b, :, c].imag)
            responses[b, c] *= np.exp(-2j * np.pi * f * o.latency_frames / o.sample_rate)
    common_gain_db = float(_db(common_gain))
    # The target includes the common export attenuation. Predictions use actual finite WAV taps.
    exported_target = target * common_gain
    positions = []
    for (seat, pos, ch), g in groups.items():
        h = np.array([g["responses"][b] for b in BANDS])
        before = h.sum(axis=0)
        after = (h * responses[:, CHANNELS.index(ch)]).sum(axis=0)
        before_error = _db(before) - _db(target)
        after_error = _db(after) - _db(exported_target)
        r = {"seat": seat, "position": pos, "channel": ch, "validation_only": g["validation"],
             "before_rms_error_db": float(np.sqrt(np.mean(before_error ** 2))),
             "predicted_rms_error_db": float(np.sqrt(np.mean(after_error ** 2))),
             "worsened_bins_percent": float(np.mean(abs(after_error) > abs(before_error) + 0.5) * 100),
             "paths": g["paths"]}
        if o.mixed_phase:
            target_phase = np.exp(-2j * np.pi * f * o.target_arrival_ms / 1000)
            before_phase_error = np.angle(before * target_phase.conj())
            # Remove only the shared, deliberately added FIR delay. Preserve the acoustic
            # propagation measured relative to the common timing reference.
            undelayed_after = after * np.exp(2j * np.pi * f * o.latency_frames / o.sample_rate)
            after_phase_error = np.angle(undelayed_after * target_phase.conj())
            r["before_phase_rms_deg"] = float(np.rad2deg(np.sqrt(np.mean(before_phase_error ** 2))))
            r["predicted_phase_rms_deg"] = float(np.rad2deg(np.sqrt(np.mean(after_phase_error ** 2))))
        if "combined" in g["responses"]:
            combined = g["responses"]["combined"]
            r["combined_vs_branch_sum_rms_db"] = float(np.sqrt(np.mean((_db(combined) - _db(before)) ** 2)))
            r["combined_vs_branch_sum_relative_complex_error"] = float(np.sqrt(np.mean(abs(combined - before) ** 2) / max(np.mean(abs(combined) ** 2), 1e-24)))
        r["deteriorated"] = r["predicted_rms_error_db"] > r["before_rms_error_db"] + 0.1
        positions.append(r)
    report = {"positions": positions, "common_gain_db": common_gain_db,
              "fir_sampled_peak_gain_db": {band: [float(_db(np.max(abs(actual_fft[bi, :, c])))) for c in range(2)] for bi, band in enumerate(BANDS)},
              "prediction": "Linear acoustic prediction from actual exported FIRs, not a post-install measurement",
              "warnings": ["Existing crossover/EQ/delay and source gain must match the measured baseline.",
                           "No independently wired speaker outputs are assumed.",
                           "Band-solo sweeps must share an unchanged timing reference per measured channel.",
                           "The designer requires diagonal stereo routing: mono bass and cross-channel mixing off during measurement and playback.",
                           "Re-measure both seats after installation; finite FIR and spatial compromises remain."]}
    if any(p["deteriorated"] for p in positions):
        report["warnings"].append("One or more positions have worse predicted RMS target error; review before installation.")
    if o.mixed_phase and any(p["predicted_phase_rms_deg"] > p["before_phase_rms_deg"] + 1 for p in positions):
        report["warnings"].append("One or more positions have worse predicted phase error; mixed-phase design is a compromise.")
    if any(p.get("combined_vs_branch_sum_rms_db", 0) > 1 or p.get("combined_vs_branch_sum_relative_complex_error", 0) > .25 for p in positions):
        report["warnings"].append("Measured combined response disagrees with the branch sum. Check timing, gain, routing and dynamics before trusting these predictions.")
    return Design(o, f, impulses, responses, groups, exported_target, common_gain_db, report)


def export(result: Design, directory: str | Path):
    if not np.all(np.isfinite(result.impulses)) or not np.all(np.isfinite(result.responses)):
        raise ValueError("Design contains non-finite coefficients; no files exported")
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    for bi, band in enumerate(BANDS):
        wavfile.write(directory / f"{band}.wav", result.options.sample_rate, result.impulses[bi])
    manifest = {"format": "siphondsp-band-ir", "version": 1, "correction_only": True,
                "sample_rate": result.options.sample_rate, "taps": result.options.taps,
                "latency_frames": result.options.latency_frames,
                "crossover_hz": {"low_mid": result.options.low_mid_hz, "mid_high": result.options.mid_high_hz},
                "files": {b: f"{b}.wav" for b in BANDS}, "gain_db": {b: 0.0 for b in BANDS},
                "common_gain_db": result.common_gain_db, "design": asdict(result.options),
                "measurement_topology": "software-band-solo; all physical speakers connected; shared stereo plant",
                "routing_assumption": "diagonal stereo; mono bass and cross-channel mixing disabled"}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2, allow_nan=False), encoding="utf-8")
    (directory / "report.json").write_text(json.dumps(result.report, indent=2, allow_nan=False), encoding="utf-8")
    with zipfile.ZipFile(directory / "band-ir-package.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for name in ("manifest.json", "report.json", "low.wav", "mid.wav", "high.wav"):
            z.write(directory / name, name)
    return manifest
