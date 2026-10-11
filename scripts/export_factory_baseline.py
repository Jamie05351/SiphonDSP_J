#!/usr/bin/env python3
"""Export the factory amplifier's per-driver response from REW as SiphonDSP_J's factory baseline.

Reads near-field sweeps (this app bypassed, so the factory EQ / crossover / time alignment is
what they captured) from a running REW (API on :4735) and writes `factory_baseline.json`. Copy
that to the head unit as `Android/data/<package>/files/factory_baseline.json`; the Crossovers
graph then shows the real response (app x factory) and a tap switches back to app-only.

What it does to the sweeps:
  * phase is read on REW's t=0 (the start/stop chirps of file playback). Near-field timing is
    trusted within a side only, so each side's earliest driver is 0 ms; the seat sums share one
    clock, the earlier of them at 0 ms;
  * each driver is kept only over the range its sweep covered (REW's startFreq..endFreq);
  * levels: near-field level depends on mic distance, so by default each driver is matched to
    the seat sweeps (this app bypassed) in the band where it dominates;
  * the side's real SUM is that side's seat sweep (L_N / R_N (sw) <seat>), not a sum of the
    near-field drivers: those are on-axis and cabin-free, and summed they miss the seat by a 10 dB
    false dip at the mid/tweeter crossover. Everything is normalised so the seat sums average
    0 dB over 200 Hz - 2 kHz with the app bypassed;
  * smoothing (2026-10-12): magnitude is POWER-averaged over --smooth octaves (default 1/6), like
    REW's 1/N smoothing -- a complex average let cabin reflections cancel and deepened the notches.
    Phase comes from a frequency-dependent window instead (--fdw-cycles, default 8, about the
    resolution of 1/6 oct), the way REW makes in-room phase readable: a Hann window N cycles wide
    at each frequency, so high frequencies only see the direct sound;
  * any pure delay left in the phase after the IR-peak arrival is fitted (upper half of each
    curve's band) and moved into arrivalMs. The complex response, and so every graph's maths and
    group delay, is unchanged; the stored phase just stops rotating, so it can be drawn.

Needs numpy and scipy.  Example:
    py scripts/export_factory_baseline.py -o factory_baseline.json
"""
import argparse
import base64
import datetime
import json
import struct
import sys
import urllib.request

import numpy as np
from scipy.signal.windows import tukey

REW = "http://localhost:4735"
DRIVERS = [("LEFT", "WOOFER", "w-L"), ("RIGHT", "WOOFER", "w-R"), ("LEFT", "MID", "m-L"),
           ("RIGHT", "MID", "m-R"), ("LEFT", "TWEETER", "t-L"), ("RIGHT", "TWEETER", "t-R")]
# Where each driver dominates the seat response, for the level match.
DOMINANT = {"WOOFER": (40.0, 80.0), "MID": (400.0, 800.0), "TWEETER": (5000.0, 10000.0)}
GRID = np.geomspace(20.0, 20000.0, 241)  # 1/24 octave
SMOOTH_OCT = 1.0 / 6.0  # magnitude power-smoothing width, set from --smooth
FDW_CYCLES = 8.0        # phase frequency-dependent window, set from --fdw-cycles (0 = complex average)
FDW_MIN_S = 0.0         # FDW never shorter than this each side of the peak, set from --fdw-min-ms


def get(path):
    with urllib.request.urlopen(REW + path, timeout=15) as r:
        return json.load(r)


def floats(b64):
    raw = base64.b64decode(b64)
    return np.array(struct.unpack(">%df" % (len(raw) // 4), raw))


def impulse(mid):
    d = get(f"/measurements/{mid}/impulse-response?normalised=false")
    x = floats(d["data"]) / 100.0
    fs = float(d.get("sampleRate", 48000))
    t0 = float(d.get("startTime", 0.0))
    return t0 + np.arange(len(x)) / fs, x, fs


def half_hann(n, rising):
    """n samples of a half Hann, 0 -> just under 1 (rising) or just under 1 -> 0 (falling)."""
    if n <= 0:
        return np.empty(0)
    w = 0.5 - 0.5 * np.cos(np.pi * np.arange(n) / n)
    return w if rising else w[::-1]


def fdw_phase(t, x, fs, pk, arrival, max_left, max_right):
    """Phase (rad) on GRID through a frequency-dependent window: Hann, FDW_CYCLES cycles wide,
    peaked on the IR peak, read against [arrival]. One direct DFT per grid frequency."""
    out = np.empty(len(GRID))
    for i, fc in enumerate(GRID):
        half = max(FDW_CYCLES / fc / 2.0, FDW_MIN_S)
        lft = min(int(half * fs), pk, max_left)
        rgt = min(int(half * fs), len(x) - pk - 1, max_right)
        w = np.concatenate([half_hann(lft, True), [1.0], half_hann(rgt, False)])
        seg = x[pk - lft: pk + rgt + 1] * w
        tt = t[pk - lft: pk + rgt + 1] - arrival
        out[i] = np.angle(np.sum(seg * np.exp(-2j * np.pi * fc * tt)))
    return out


def response(mid):
    """Complex response on GRID against the shared t=0, plus the IR-peak arrival (s). Magnitude is
    power-smoothed over SMOOTH_OCT; phase is through the FDW (or, with FDW_CYCLES 0, the angle of
    the complex average over the same band, the pre-2026-10-12 behaviour)."""
    t, x, fs = impulse(mid)
    pk = int(np.argmax(np.abs(x)))
    left = min(pk, int(0.125 * fs))
    right = min(len(x) - pk, int(0.5 * fs))
    seg = x[pk - left: pk + right] * np.concatenate([tukey(2 * left, 0.25)[:left], tukey(2 * right, 0.25)[right:]])
    n = 1 << int(np.ceil(np.log2(len(seg) * 2)))
    f = np.fft.rfftfreq(n, 1 / fs)[1:]
    arrival = t[pk]
    # Spectrum with the arrival already removed, so a complex average does not cancel itself.
    h = np.fft.rfft(seg, n)[1:] * np.exp(-2j * np.pi * f * (t[pk - left] - arrival))
    k = 2 ** (SMOOTH_OCT / 2)
    power = np.abs(h) ** 2
    mag = np.empty(len(GRID))
    avg = np.empty(len(GRID), complex)
    for i, fc in enumerate(GRID):
        m = (f >= fc / k) & (f <= fc * k)
        if m.any():
            mag[i] = np.sqrt(power[m].mean())
            avg[i] = h[m].mean()
        else:
            avg[i] = np.interp(fc, f, h.real) + 1j * np.interp(fc, f, h.imag)
            mag[i] = abs(avg[i])
    if FDW_CYCLES > 0:
        phase = fdw_phase(t, x, fs, pk, arrival, left, right - 1)
    else:
        phase = np.angle(avg)
    return mag * np.exp(1j * phase), arrival


def refit_arrival(d):
    """Moves the pure delay still left in d["h"] into d["arrival"]: a straight-line fit of the
    unwrapped phase against frequency over the upper half (log) of the curve's band. The product
    (phase x arrival) is unchanged, only how it is split. Returns the delay moved (s)."""
    inside = (GRID >= d["minHz"]) & (GRID <= d["maxHz"])
    f = GRID[inside]
    if len(f) < 4:
        return 0.0
    ph = np.unwrap(np.angle(d["h"][inside]))
    fit = f >= np.sqrt(f[0] * f[-1])
    if fit.sum() < 3:
        return 0.0
    tau = -np.polyfit(f[fit], ph[fit], 1)[0] / (2 * np.pi)
    d["h"] = d["h"] * np.exp(2j * np.pi * GRID * tau)
    d["arrival"] += tau
    return tau


def max_phase_step_deg(d):
    """Largest phase change between neighbouring grid points: over ~90 deg the 1/24-oct grid is
    too coarse to follow it and the app's interpolation will guess."""
    inside = (GRID >= d["minHz"]) & (GRID <= d["maxHz"])
    ph = np.unwrap(np.angle(d["h"][inside]))
    return float(np.degrees(np.max(np.abs(np.diff(ph))))) if len(ph) > 1 else 0.0


def band_db(h, lo, hi):
    m = (GRID >= lo) & (GRID <= hi)
    return 10 * np.log10(np.mean(np.abs(h[m]) ** 2))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-o", "--out", default="factory_baseline.json")
    ap.add_argument("--series", default="1", help="REW series number in the titles (default 1)")
    ap.add_argument("--no-seat-match", action="store_true", help="keep the raw near-field levels")
    ap.add_argument("--mirror", action="append", default=[], metavar="DRIVER=SIDE",
                    help="use SIDE's sweep shape for both sides of DRIVER, e.g. TWEETER=LEFT when the other "
                         "side's near-field capture is the worse one. Each side keeps its own arrival and level.")
    ap.add_argument("--seat", default="DRV+PAS",
                    help="seat(s) whose L/R sweeps become each side's real sum (title suffix; default DRV+PAS: "
                         "magnitude power-averaged over the seats, phase from the first; 'none' leaves it out)")
    ap.add_argument("--smooth", type=float, default=6.0, metavar="N",
                    help="magnitude power-smoothing, 1/N octave (default 6; 3 for a calmer seat curve)")
    ap.add_argument("--fdw-cycles", type=float, default=8.0, metavar="C",
                    help="phase frequency-dependent window in cycles (default 8; 0 = the old complex average)")
    ap.add_argument("--fdw-min-ms", type=float, default=0.0, metavar="MS",
                    help="never window phase tighter than this each side of the IR peak (default 0). Raise it "
                         "(e.g. 1.5) if a seat sum's tweeter arrives far from the IR peak and its phase looks lost")
    ap.add_argument("--no-refit", action="store_true", help="keep the IR-peak arrivals as they are")
    a = ap.parse_args()
    global SMOOTH_OCT, FDW_CYCLES, FDW_MIN_S
    SMOOTH_OCT = 1.0 / a.smooth
    FDW_CYCLES = a.fdw_cycles
    FDW_MIN_S = a.fdw_min_ms / 1000.0
    mirror = {}
    for spec in a.mirror:
        driver, _, side = spec.upper().partition("=")
        if driver not in DOMINANT or side not in ("LEFT", "RIGHT"):
            sys.exit(f"--mirror {spec}: expected WOOFER|MID|TWEETER=LEFT|RIGHT")
        mirror[driver] = side

    meas = get("/measurements")
    by_title = {}
    for k, v in meas.items():
        by_title.setdefault(v["title"].strip(), []).append((int(k), v))

    def find(title):
        hits = by_title.get(title)
        if not hits:
            sys.exit(f"REW has no measurement titled {title!r}")
        return min(hits)  # the first taken, if a title repeats

    drivers = []
    for side, driver, code in DRIVERS:
        mid, meta = find(f"{code}_{a.series} (sw) nf")
        h, arrival = response(mid)
        lo, hi = float(meta.get("startFreq", 20.0)), float(meta.get("endFreq", 20000.0))
        drivers.append(dict(side=side, driver=driver, title=meta["title"], h=h, arrival=arrival,
                            minHz=max(20.0, lo), maxHz=min(20000.0, hi)))

    # The shape (magnitude + phase, arrival already out) is borrowed; the arrival is the side's own.
    for driver, side in mirror.items():
        src = next(d for d in drivers if d["driver"] == driver and d["side"] == side)
        for d in drivers:
            if d["driver"] == driver and d is not src:
                d.update(h=src["h"].copy(), minHz=src["minHz"], maxHz=src["maxHz"],
                         title=f"{src['title']} (shape) / {d['title']} (arrival)")

    if not a.no_seat_match:
        for side, ch in (("LEFT", "L"), ("RIGHT", "R")):
            seats = [response(find(f"{ch}_{a.series} (sw) {pos}")[0])[0] for pos in ("DRV", "PAS")]
            seat = np.sqrt(np.mean([np.abs(s) ** 2 for s in seats], axis=0))
            for d in drivers:
                if d["side"] != side:
                    continue
                lo, hi = DOMINANT[d["driver"]]
                d["trimDb"] = band_db(seat, lo, hi) - band_db(d["h"], lo, hi)
                d["h"] = d["h"] * 10 ** (d["trimDb"] / 20)

    # Each side is ONE signal into the factory amplifier, so that side's sweep at a seat is its whole
    # real response there -- factory, every driver, off-axis, cabin -- and app x it is exact. The
    # near-field drivers cannot be summed into it: they are on-axis and cabin-free.
    # Several seats (DRV+PAS, a multi-seat tune): magnitude is their power average; phase cannot be
    # averaged across seats, so it is the FIRST seat's.
    sums = []
    if a.seat.lower() != "none":
        seat_names = a.seat.split("+")
        for side, ch in (("LEFT", "L"), ("RIGHT", "R")):
            taken = []
            for name in seat_names:
                mid, meta = find(f"{ch}_{a.series} (sw) {name}")
                taken.append((meta, *response(mid)))
            meta0, h0, arrival = taken[0]
            mag = np.sqrt(np.mean([np.abs(h) ** 2 for _, h, _ in taken], axis=0))
            h = mag * np.exp(1j * np.angle(h0))
            sums.append(dict(side=side, title=" + ".join(m["title"] for m, _, _ in taken), h=h, arrival=arrival,
                             minHz=max(20.0, max(float(m.get("startFreq", 20.0)) for m, _, _ in taken)),
                             maxHz=min(20000.0, min(float(m.get("endFreq", 20000.0)) for m, _, _ in taken))))

    if not a.no_refit:
        for d in drivers + sums:
            d["refitMs"] = refit_arrival(d) * 1000

    # Normalise: 0 dB = the seat sums' (else the drivers') 200 Hz - 2 kHz level with the app bypassed.
    if sums:
        norm = -np.mean([band_db(s["h"], 200, 2000) for s in sums])
    else:
        norm = -np.mean([band_db(d["h"], 400, 800) for d in drivers if d["driver"] == "MID"])

    # Arrivals: near-field timing is only trusted WITHIN a side (2026-10-11: the left near-field set
    # sat ~4-5 ms late against the right and against the seat sweeps, which match the tape-measured
    # paths). So each side's drivers are zeroed on that side's earliest driver, and the seat sums --
    # one consistent clock -- on the earlier of the two.
    first_by_side = {side: min(d["arrival"] for d in drivers if d["side"] == side) for side in ("LEFT", "RIGHT")}
    first_sum = min((s["arrival"] for s in sums), default=0.0)

    def curve(d, first):
        inside = (GRID >= d["minHz"]) & (GRID <= d["maxHz"])
        h = d["h"][inside]
        return {
            "side": d["side"], "title": d["title"],
            "arrivalMs": round((d["arrival"] - first) * 1000, 4),
            "minHz": round(d["minHz"], 2), "maxHz": round(d["maxHz"], 2),
            "freqHz": [round(f, 2) for f in GRID[inside]],
            "magDb": [round(float(20 * np.log10(abs(v) + 1e-12) + norm), 3) for v in h],
            "phaseDeg": [round(float(p), 2) for p in np.degrees(np.unwrap(np.angle(h)))],
        }

    out = []
    for d in drivers:
        out.append({**curve(d, first_by_side[d["side"]]), "driver": d["driver"], "trimDb": round(d.get("trimDb", 0.0), 2)})
        print(f"{d['side']:5s} {d['driver']:7s} {d['title']:18s} arrival +{out[-1]['arrivalMs']:6.3f} ms"
              f"  {d['minHz']:7.0f}-{d['maxHz']:5.0f} Hz  trim {out[-1]['trimDb']:+6.2f} dB"
              f"  refit {d.get('refitMs', 0.0):+.3f} ms  max step {max_phase_step_deg(d):4.0f} deg")
    seat_out = []
    for s in sums:
        seat_out.append(curve(s, first_sum))
        print(f"{s['side']:5s} SUM     {s['title']:18s} arrival +{seat_out[-1]['arrivalMs']:6.3f} ms"
              f"  refit {s.get('refitMs', 0.0):+.3f} ms  max step {max_phase_step_deg(s):4.0f} deg")

    doc = {
        "format": "siphondsp-factory-baseline", "version": 1,
        "source": f"REW near-field series {a.series}, exported {datetime.date.today().isoformat()}, "
                  f"1/{a.smooth:g} oct power, " + (f"FDW {a.fdw_cycles:g} cycles" if a.fdw_cycles > 0 else "complex avg"),
        "drivers": out,
    }
    if seat_out:
        doc["seat"] = a.seat
        doc["seatSums"] = seat_out
    with open(a.out, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    print("wrote", a.out)


if __name__ == "__main__":
    main()
