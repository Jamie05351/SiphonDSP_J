import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import struct
import re
import numpy as np
from scipy.io import wavfile
from designer import Measurement, Options, design, export, read_response


class DesignerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def fixtures(self, seat_gain=(1, 1), branch_delay=(0, 0, 0), validation=False):
        f = np.geomspace(10, 22000, 1400)
        # Smooth complex complementary frequency regions, not three independent speakers.
        low = 1 / (1 + (f / 150) ** 4)
        high = 1 / (1 + (3500 / f) ** 4)
        mid = (1 - low) * (1 - high)
        weights = np.array([low * (1 - high), mid, high])
        out = []
        for seat, gain in zip(("driver", "passenger"), seat_gain):
            for ch in ("L", "R"):
                for b, band in enumerate(("low", "mid", "high")):
                    mag = 75 + 20 * np.log10(np.maximum(weights[b] * gain, 1e-12))
                    phase = -360 * f * branch_delay[b]
                    p = self.root / f"{seat}-{ch}-{band}.txt"
                    np.savetxt(p, np.column_stack((f, mag, phase)), header="Frequency SPL Phase")
                    out.append(Measurement(str(p), seat, "centre", ch, band, "fixed-ref", validation))
        return out

    def options(self, **kw):
        return Options(taps=2048, latency_frames=1024, bass_db=0, treble_db=0, **kw)

    def test_unity_measurements_export_delayed_unity_without_crossover(self):
        r = design(self.fixtures(), self.options())
        for b in range(3):
            for c in range(2):
                # SPL interpolation of separately sampled branch curves introduces a tiny
                # residual; identity must remain broadband, not become a crossover.
                self.assertAlmostEqual(float(r.impulses[b, 1024, c]), .999, delta=.002)
                self.assertLess(np.sum(abs(np.delete(r.impulses[b, :, c], 1024))), .002)
                self.assertLess(np.max(abs(abs(r.responses[b, c]) - .999)), .002)
        output = self.root / "export"
        manifest = export(r, output)
        self.assertTrue(manifest["correction_only"])
        for b in ("low", "mid", "high"):
            sr, wav = wavfile.read(output / f"{b}.wav")
            self.assertEqual(sr, 48000)
            self.assertEqual(wav.shape, (2048, 2))
            self.assertEqual(wav.dtype, np.float32)
        with zipfile.ZipFile(output / "band-ir-package.zip") as z:
            self.assertEqual(set(z.namelist()), {"manifest.json", "report.json", "low.wav", "mid.wav", "high.wav"})
        self.assertEqual(json.loads((output / "manifest.json").read_text())["latency_frames"], 1024)

    def test_both_seats_evaluated_and_loud_response_cut(self):
        r = design(self.fixtures(seat_gain=(2, 2)), self.options())
        positions = r.report["positions"]
        self.assertEqual(len(positions), 4)
        self.assertEqual({p["seat"] for p in positions}, {"driver", "passenger"})
        for p in positions:
            self.assertGreater(p["before_rms_error_db"], 5.9)
            self.assertLess(p["predicted_rms_error_db"], 2)
        # No per-channel normalization: deliberately different filters stay different.
        peak = np.max(abs(np.fft.rfft(r.impulses, 65536, axis=1)))
        self.assertLessEqual(peak, 1.000001)

    def test_incompatible_seats_are_reported_not_hidden(self):
        r = design(self.fixtures(seat_gain=(2, .5)), self.options())
        # A shared correction cannot simultaneously flatten a +6 and -6 dB plant.
        self.assertTrue(any(p["deteriorated"] for p in r.report["positions"]))
        self.assertTrue(any("worse" in w for w in r.report["warnings"]))

    def test_missing_triplet_or_timing_reference_rejected(self):
        ms = self.fixtures()
        with self.assertRaisesRegex(ValueError, "require Low"):
            design(ms[:-1], self.options())
        ms[-1].timing_reference = "different"
        with self.assertRaisesRegex(ValueError, "unchanged acoustic"):
            design(ms, self.options())

    def test_opposite_reference_per_channel_is_valid_only_for_magnitude_design(self):
        ms = self.fixtures()
        reference = design(ms, self.options())
        for m in ms:
            m.timing_reference = "reference-right" if m.channel == "L" else "reference-left"
            data = np.loadtxt(m.path)
            data[:, 2] -= 360 * data[:, 0] * (.001 if m.channel == "L" else .002)
            np.savetxt(m.path, data)
        measured = design(ms, self.options())
        np.testing.assert_allclose(reference.impulses, measured.impulses, atol=2e-6)
        with self.assertRaisesRegex(ValueError, "one common timing reference"):
            design(ms, self.options(mixed_phase=True))

    def test_validation_positions_do_not_change_fit(self):
        ms = self.fixtures(seat_gain=(2, 2))
        baseline = design(ms, self.options())
        from dataclasses import replace
        validation = [replace(m, position="held-out", validation=True) for m in ms]
        checked = design(ms + validation, self.options())
        np.testing.assert_array_equal(baseline.impulses, checked.impulses)
        self.assertEqual(sum(p["validation_only"] for p in checked.report["positions"]), 4)

    def test_seat_weight_is_not_changed_by_more_positions(self):
        from dataclasses import replace
        ms = self.fixtures(seat_gain=(2, .5))
        one = design(ms, self.options())
        extra_driver = [replace(m, position="second") for m in ms if m.seat == "driver"]
        many = design(ms + extra_driver, self.options())
        np.testing.assert_allclose(one.impulses, many.impulses, atol=1e-6)

    def test_missing_phase_and_nonfinite_rejected(self):
        p = self.root / "bad.txt"
        p.write_text("20 75\n30 75\n")
        m = Measurement(str(p), "driver", "centre", "L", "low", "ref")
        with self.assertRaisesRegex(ValueError, "phase column"):
            read_response(m, np.geomspace(25, 16000, 20), 48000, 75)
        p.write_text("\n".join(f"{20+i*2000} nan 0" for i in range(12)))
        with self.assertRaisesRegex(ValueError, "finite"):
            read_response(m, np.geomspace(25, 16000, 20), 48000, 75)

    def test_impulse_rate_and_silence_rejected(self):
        p = self.root / "impulse.wav"
        wavfile.write(p, 44100, np.zeros(32, np.float32))
        m = Measurement(str(p), "driver", "centre", "L", "low", "ref", calibration_db=75)
        with self.assertRaisesRegex(ValueError, "sample rate"):
            read_response(m, np.array([100., 1000.]), 48000, 75)
        wavfile.write(p, 48000, np.zeros(32, np.float32))
        with self.assertRaisesRegex(ValueError, "silent"):
            read_response(m, np.array([100., 1000.]), 48000, 75)

    def test_invalid_design_options(self):
        for options in (Options(sample_rate=96000), Options(taps=2000), Options(latency_frames=0), Options(driver_weight=0), Options(max_boost_db=20)):
            with self.assertRaises(ValueError):
                options.validate()

    def test_mixed_phase_produces_finite_common_delay_and_reports_all_positions(self):
        r = design(self.fixtures(branch_delay=(.001, .002, .0005)), self.options(mixed_phase=True))
        self.assertTrue(np.all(np.isfinite(r.impulses)))
        self.assertEqual(r.impulses.shape, (3, 2048, 2))
        self.assertEqual(len(r.report["positions"]), 4)
        self.assertLessEqual(np.max(abs(np.fft.rfft(r.impulses, 65536, axis=1))), 1.000001)

    def test_mixed_phase_corrects_recoverable_common_delay(self):
        r = design(self.fixtures(branch_delay=(.001, .001, .001)), self.options(mixed_phase=True))
        for p in r.report["positions"]:
            self.assertLess(p["predicted_phase_rms_deg"], p["before_phase_rms_deg"])

    def test_native_oversampled_peak_check_stays_below_unity(self):
        r = design(self.fixtures(branch_delay=(.001, .002, .0005)), self.options(mixed_phase=True, max_boost_db=6))
        for b in range(3):
            for c in range(2):
                self.assertLessEqual(np.max(abs(np.fft.rfft(r.impulses[b, :, c], r.options.taps * 8))), 1.0)

    def test_export_matches_android_package_manifest_and_riff_contract(self):
        r = design(self.fixtures(), self.options())
        output = self.root / "android-contract"
        export(r, output)
        with zipfile.ZipFile(output / "band-ir-package.zip") as z:
            names = z.namelist()
            self.assertLessEqual(len(names), 16)
            self.assertEqual(len(names), len(set(names)))
            self.assertTrue(all(re.fullmatch(r"[A-Za-z0-9_.-]+", n) for n in names))
            self.assertLessEqual(sum(i.file_size for i in z.infolist()), 128 * 1024 * 1024)
            self.assertLessEqual(len(z.read("manifest.json")), 131072)
            m = json.loads(z.read("manifest.json"))
            self.assertEqual((m["format"], m["version"], m["correction_only"]), ("siphondsp-band-ir", 1, True))
            self.assertIn(m["sample_rate"], (44100, 48000))
            self.assertTrue(16 <= m["taps"] <= 65536 and 0 <= m["latency_frames"] < m["taps"])
            self.assertTrue(20 <= m["crossover_hz"]["low_mid"] < m["crossover_hz"]["mid_high"] < m["sample_rate"] / 2)
            files = [m["files"][b] for b in ("low", "mid", "high")]
            self.assertEqual(len(set(files)), 3)
            for name in files:
                self.assertTrue(re.fullmatch(r"[A-Za-z0-9_-]+\.wav", name))
                raw = z.read(name)
                self.assertEqual(raw[:4], b"RIFF")
                self.assertEqual(raw[8:12], b"WAVE")
                offset, frames, fmt_ok = 12, -1, False
                while offset + 8 <= len(raw):
                    chunk = raw[offset:offset+4]
                    size, = struct.unpack_from("<I", raw, offset+4)
                    start = offset + 8
                    self.assertLessEqual(size, len(raw) - start)
                    if chunk == b"fmt ":
                        code, channels, rate, byte_rate, align, bits = struct.unpack_from("<HHIIHH", raw, start)
                        self.assertEqual((code, channels, rate, align, bits), (3, 2, m["sample_rate"], 8, 32))
                        fmt_ok = True
                    if chunk == b"data":
                        self.assertEqual(size % 8, 0)
                        frames = size // 8
                    offset = start + size + size % 2
                self.assertTrue(fmt_ok)
                self.assertEqual(frames, m["taps"])

    def test_combined_phase_mismatch_is_flagged(self):
        ms = self.fixtures()
        f = np.geomspace(10, 22000, 1400)
        p = self.root / "wrong-combined.txt"
        np.savetxt(p, np.column_stack((f, np.full_like(f, 75), np.full_like(f, 180))))
        ms.append(Measurement(str(p), "driver", "centre", "L", "combined", "fixed-ref"))
        r = design(ms, self.options())
        self.assertTrue(any("disagrees" in w for w in r.report["warnings"]))

    def test_unwrapped_phase_retains_long_measured_delay(self):
        f = np.geomspace(10, 22000, 80)
        p = self.root / "long-delay.txt"
        np.savetxt(p, np.column_stack((f, np.full_like(f, 75), -360 * f * .1)))
        m = Measurement(str(p), "driver", "centre", "L", "low", "ref")
        response = read_response(m, f[10:-10], 48000, 75)
        np.testing.assert_allclose(response, np.exp(-2j * np.pi * f[10:-10] * .1), atol=1e-9)


if __name__ == "__main__":
    unittest.main()
