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
    0 dB over 200 Hz - 2 kHz with the app bypassed.

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


def response(mid):
    """Complex response on GRID against the shared t=0, 1/6-oct smoothed, plus the IR-peak arrival (s)."""
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
    out = np.empty(len(GRID), complex)
    for i, fc in enumerate(GRID):
        m = (f >= fc * 2 ** (-1 / 12)) & (f <= fc * 2 ** (1 / 12))
        out[i] = h[m].mean() if m.any() else np.interp(fc, f, h.real) + 1j * np.interp(fc, f, h.imag)
    return out, arrival


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
    ap.add_argument("--seat", default="DRV",
                    help="seat whose L/R sweeps become each side's real sum (title suffix, default DRV; "
                         "'none' leaves the sum out)")
    a = ap.parse_args()
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
    sums = []
    if a.seat.lower() != "none":
        for side, ch in (("LEFT", "L"), ("RIGHT", "R")):
            mid, meta = find(f"{ch}_{a.series} (sw) {a.seat}")
            h, arrival = response(mid)
            sums.append(dict(side=side, title=meta["title"], h=h, arrival=arrival,
                             minHz=max(20.0, float(meta.get("startFreq", 20.0))),
                             maxHz=min(20000.0, float(meta.get("endFreq", 20000.0)))))

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
              f"  {d['minHz']:7.0f}-{d['maxHz']:5.0f} Hz  trim {out[-1]['trimDb']:+6.2f} dB")
    seat_out = []
    for s in sums:
        seat_out.append(curve(s, first_sum))
        print(f"{s['side']:5s} SUM     {s['title']:18s} arrival +{seat_out[-1]['arrivalMs']:6.3f} ms")

    doc = {
        "format": "siphondsp-factory-baseline", "version": 1,
        "source": f"REW near-field series {a.series}, exported {datetime.date.today().isoformat()}",
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
