"""Windows desktop entry point. Run with Python or the packaged BandIRDesigner.exe."""
from __future__ import annotations
from dataclasses import asdict
import json
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import numpy as np
from matplotlib.figure import Figure
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
from designer import Measurement, Options, BANDS, CHANNELS, design, export


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("J_DSP - Multi-seat Band IR Designer")
        self.geometry("1250x850")
        self.measurements = []
        self.result = None
        self.events = queue.Queue()
        self.fields = {}
        self.status = tk.StringVar(value="Import matched software-band-solo sweeps for both seats and channels.")
        notebook = ttk.Notebook(self)
        notebook.pack(fill="both", expand=True)
        inputs = ttk.Frame(notebook, padding=12)
        settings = ttk.Frame(notebook, padding=12)
        preview = ttk.Frame(notebook, padding=12)
        notebook.add(inputs, text="1 - Measurements")
        notebook.add(settings, text="2 - Design settings")
        notebook.add(preview, text="3 - Preview and export")
        ttk.Label(inputs, text="Measure each software band SOLO with all physical speakers connected. Keep existing crossover/EQ/delay fixed.", wraplength=1100).pack(anchor="w")
        ttk.Label(inputs, text="Bypass correction IRs while measuring. Keep a consistent timing reference per channel and unchanged source gain. Import REW SPL+phase text or calibrated mono impulse WAV.", wraplength=1100).pack(anchor="w", pady=(0, 12))
        ttk.Label(inputs, text="Designer requirement: mono bass and cross-channel routing OFF during measurement and playback. Mixed-phase fitting needs one common reference across L and R.", wraplength=1100).pack(anchor="w", pady=(0, 8))
        row = ttk.Frame(inputs)
        row.pack(fill="x")
        self.seat = tk.StringVar(value="driver")
        self.position = tk.StringVar(value="centre")
        self.channel = tk.StringVar(value="L")
        self.band = tk.StringVar(value="low")
        self.reference = tk.StringVar(value="")
        self.calibration = tk.StringVar(value="0")
        self.validation = tk.BooleanVar()
        for label, variable, values, width in [
            ("Seat", self.seat, ("driver", "passenger"), 12),
            ("Position", self.position, None, 12),
            ("Channel", self.channel, CHANNELS, 5),
            ("Software band", self.band, (*BANDS, "combined"), 12),
            ("Timing-reference ID", self.reference, None, 20),
            ("Calibration offset dB", self.calibration, None, 12),
        ]:
            frame = ttk.Frame(row)
            frame.pack(side="left", padx=(0, 8))
            ttk.Label(frame, text=label).pack(anchor="w")
            if values:
                ttk.Combobox(frame, textvariable=variable, values=values, state="readonly", width=width).pack()
            else:
                ttk.Entry(frame, textvariable=variable, width=width).pack()
        ttk.Checkbutton(inputs, text="Validation-only position (exclude this whole position/channel triplet from the fit)", variable=self.validation).pack(anchor="w", pady=8)
        actions = ttk.Frame(inputs)
        actions.pack(fill="x", pady=6)
        for label, callback in [("Add file...", self.add), ("Remove selected", self.remove), ("Save project...", self.save_project), ("Open project...", self.open_project)]:
            ttk.Button(actions, text=label, command=callback).pack(side="left", padx=(0, 8))
        columns = ("seat", "position", "channel", "band", "validation", "reference", "file")
        self.table = ttk.Treeview(inputs, columns=columns, show="headings", selectmode="extended")
        for col in columns:
            self.table.heading(col, text=col.title())
            self.table.column(col, width=90 if col != "file" else 350, stretch=col == "file")
        self.table.pack(fill="both", expand=True)
        ttk.Label(inputs, text="For one position in each seat: 12 band-solo sweeps minimum. Three positions per seat: 36 band-solo sweeps; add combined sweeps to check the measured sum.", wraplength=1100).pack(anchor="w", pady=8)
        left = ttk.Frame(settings)
        left.pack(anchor="nw")
        labels = [
            ("sample_rate", "Sample rate (44100 or 48000 Hz)"), ("taps", "FIR taps (power of two)"),
            ("latency_frames", "Common FIR delay (frames, all bands/channels)"),
            ("low_mid_hz", "Existing Low/Mid crossover (Hz, documentation only)"),
            ("mid_high_hz", "Existing Mid/High crossover (Hz, documentation only)"),
            ("min_hz", "Correction minimum (Hz)"), ("max_hz", "Correction maximum (Hz)"),
            ("target_db", "Target SPL at 100 Hz–1 kHz (dB)"), ("bass_db", "Target 20 Hz offset (dB)"),
            ("treble_db", "Target 20 kHz offset (dB)"), ("max_boost_db", "Maximum design boost (0–6 dB)"),
            ("max_cut_db", "Maximum design cut (0–24 dB)"), ("regularization", "Regularization (larger = more conservative)"),
            ("target_arrival_ms", "Mixed-phase target arrival relative to timing reference (ms)"),
            ("driver_weight", "Driver seat weight"), ("passenger_weight", "Passenger seat weight"),
        ]
        defaults = Options()
        for r, (name, label) in enumerate(labels):
            ttk.Label(left, text=label).grid(row=r, column=0, sticky="w", pady=3)
            variable = tk.StringVar(value=str(getattr(defaults, name)))
            self.fields[name] = variable
            ttk.Entry(left, textvariable=variable, width=16).grid(row=r, column=1, padx=15)
        self.mixed = tk.BooleanVar(value=False)
        ttk.Checkbutton(settings, text="Experimental mixed-phase correction (requires trustworthy common timing; inspect impulse and both-seat predictions)", variable=self.mixed).pack(anchor="w", pady=15)
        ttk.Label(settings, text="Default mode fits positive real frequency-dependent gains: magnitude correction with one common linear-phase FIR delay. Existing relative acoustic phase is used in the band-sum model. Mixed-phase mode fits complex filters toward the specified shared arrival time; it can introduce pre-ringing and a seat compromise. Neither mode changes existing crossover filters.", wraplength=1050).pack(anchor="w")
        self.design_button = ttk.Button(settings, text="Calculate and preview", command=self.calculate)
        self.design_button.pack(anchor="w", pady=15)
        selectors = ttk.Frame(preview)
        selectors.pack(fill="x")
        self.preview_key = tk.StringVar()
        self.preview_combo = ttk.Combobox(selectors, textvariable=self.preview_key, state="readonly", width=50)
        self.preview_combo.pack(side="left")
        self.preview_combo.bind("<<ComboboxSelected>>", lambda _: self.plot())
        self.export_button = ttk.Button(selectors, text="Export WAVs + report + ZIP...", command=self.export_result, state="disabled")
        self.export_button.pack(side="right")
        self.figure = Figure(figsize=(10, 5), dpi=100)
        self.axes = self.figure.subplots(2, 1)
        self.canvas = FigureCanvasTkAgg(self.figure, preview)
        self.canvas.get_tk_widget().pack(fill="both", expand=True)
        self.summary = tk.Text(preview, height=8, wrap="word", state="disabled")
        self.summary.pack(fill="x")
        ttk.Label(self, textvariable=self.status, wraplength=1200).pack(fill="x", padx=12, pady=8)
        self.notebook = notebook
        self.after(100, self.poll)

    def options(self):
        d = {k: float(v.get()) for k, v in self.fields.items()}
        for k in ("sample_rate", "taps", "latency_frames"):
            if not np.isfinite(d[k]) or d[k] != int(d[k]):
                raise ValueError(f"{k} must be an integer")
            d[k] = int(d[k])
        d["mixed_phase"] = self.mixed.get()
        o = Options(**d)
        o.validate()
        return o

    def refresh(self):
        self.table.delete(*self.table.get_children())
        for i, m in enumerate(self.measurements):
            self.table.insert("", "end", iid=str(i), values=(m.seat, m.position, m.channel, m.band, m.validation, m.timing_reference, m.path))
        self.result = None
        self.export_button.configure(state="disabled")

    def add(self):
        path = filedialog.askopenfilename(filetypes=[("REW export or mono impulse", "*.txt *.csv *.frd *.wav"), ("All files", "*.*")])
        if not path:
            return
        try:
            calibration = float(self.calibration.get())
            if not self.reference.get().strip():
                raise ValueError("Enter the unchanged timing-reference speaker/configuration ID for this measured channel first")
            self.measurements.append(Measurement(path, self.seat.get(), self.position.get().strip(), self.channel.get(), self.band.get(), self.reference.get().strip(), self.validation.get(), calibration))
            self.refresh()
        except ValueError as e:
            messagebox.showerror("Measurement", str(e))

    def remove(self):
        for i in sorted(map(int, self.table.selection()), reverse=True):
            del self.measurements[i]
        self.refresh()

    def save_project(self):
        try:
            o = self.options()
            path = filedialog.asksaveasfilename(defaultextension=".json", filetypes=[("Designer project", "*.json")])
            if path:
                Path(path).write_text(json.dumps({"version": 1, "options": asdict(o), "measurements": [asdict(m) for m in self.measurements]}, indent=2), encoding="utf-8")
        except (ValueError, OSError) as e:
            messagebox.showerror("Save project", str(e))

    def open_project(self):
        path = filedialog.askopenfilename(filetypes=[("Designer project", "*.json")])
        if not path:
            return
        try:
            p = json.loads(Path(path).read_text(encoding="utf-8"))
            if p.get("version") != 1:
                raise ValueError("Unsupported project version")
            options = Options(**p["options"])
            options.validate()
            measurements = [Measurement(**m) for m in p["measurements"]]
            for k, v in asdict(options).items():
                if k in self.fields:
                    self.fields[k].set(str(v))
            self.mixed.set(options.mixed_phase)
            self.measurements = measurements
            self.refresh()
        except (ValueError, TypeError, KeyError, OSError) as e:
            messagebox.showerror("Open project", str(e))

    def input_signature(self):
        return json.dumps({"options": asdict(self.options()),
                           "measurements": [asdict(m) for m in self.measurements]}, sort_keys=True)

    def calculate(self):
        try:
            options = self.options()
            measurements = list(self.measurements)
            signature = self.input_signature()
        except ValueError as e:
            messagebox.showerror("Settings", str(e))
            return
        self.design_button.configure(state="disabled")
        self.export_button.configure(state="disabled")
        self.status.set("Calculating both-seat filters...")
        def worker():
            try:
                self.events.put((signature, True, design(measurements, options)))
            except Exception as e:
                self.events.put((signature, False, str(e)))
        threading.Thread(target=worker, daemon=True).start()

    def poll(self):
        try:
            signature, ok, data = self.events.get_nowait()
        except queue.Empty:
            self.after(100, self.poll)
            return
        self.design_button.configure(state="normal")
        try:
            current = self.input_signature()
        except ValueError:
            current = None
        if signature != current:
            self.result = None
            self.export_button.configure(state="disabled")
            self.status.set("Inputs changed during calculation. Calculate again before exporting.")
            self.after(100, self.poll)
            return
        if not ok:
            self.status.set("Design failed; no filters exported.")
            messagebox.showerror("Design", data)
        else:
            self.result = data
            self.result_signature = signature
            self.preview_keys = list(data.groups)
            names = [" / ".join(k) + (" [validation]" if data.groups[k]["validation"] else "") for k in self.preview_keys]
            self.preview_combo.configure(values=names)
            self.preview_combo.current(0)
            self.export_button.configure(state="normal")
            lines = [f"Shared export attenuation: {data.common_gain_db:.2f} dB. Predictions include actual finite exported FIRs."]
            for p in data.report["positions"]:
                lines.append(f"{p['seat']} / {p['position']} / {p['channel']}: RMS target error {p['before_rms_error_db']:.2f} -> {p['predicted_rms_error_db']:.2f} dB; {p['worsened_bins_percent']:.1f}% bins worse" + (" - DETERIORATED" if p["deteriorated"] else ""))
                if "predicted_phase_rms_deg" in p:
                    lines.append(f"  Phase RMS error {p['before_phase_rms_deg']:.1f}° -> {p['predicted_phase_rms_deg']:.1f}° relative to requested arrival, excluding common FIR delay")
            lines.extend(data.report["warnings"])
            self.summary.configure(state="normal")
            self.summary.delete("1.0", "end")
            self.summary.insert("end", "\n".join(lines))
            self.summary.configure(state="disabled")
            self.notebook.select(2)
            self.plot()
            self.status.set("Review every seat/position. Predictions require verification by fresh measurements.")
        self.after(100, self.poll)

    def plot(self):
        if self.result is None:
            return
        r = self.result
        k = self.preview_keys[self.preview_combo.current()]
        g = r.groups[k]
        h = np.array([g["responses"][b] for b in BANDS])
        before = h.sum(axis=0)
        after = (h * r.responses[:, CHANNELS.index(k[2])]).sum(axis=0)
        db = lambda x: 20 * np.log10(np.maximum(abs(x), 1e-12)) + r.options.target_db
        ax, ir_ax = self.axes
        ax.clear()
        ax.semilogx(r.frequencies, db(before), label="Before (measured branch sum)")
        ax.semilogx(r.frequencies, db(after), label="Predicted from exported FIRs")
        ax.semilogx(r.frequencies, db(r.target), "--", label="Target incl. common attenuation")
        if "combined" in g["responses"]:
            ax.semilogx(r.frequencies, db(g["responses"]["combined"]), ":", label="Measured combined check")
        ax.set_ylabel("SPL (dB)")
        ax.set_xlabel("Frequency (Hz)")
        ax.grid(True, alpha=.2)
        ax.legend(fontsize=8)
        ir_ax.clear()
        t = np.arange(r.options.taps) / r.options.sample_rate * 1000
        for b, band in enumerate(BANDS):
            ir_ax.plot(t, r.impulses[b, :, CHANNELS.index(k[2])], label=band)
        ir_ax.axvline(r.options.latency_frames / r.options.sample_rate * 1000, color="grey", linestyle="--")
        ir_ax.set_xlabel("FIR time (ms); inspect pre/post ringing")
        ir_ax.set_ylabel("Amplitude")
        ir_ax.legend(fontsize=8)
        self.figure.tight_layout()
        self.canvas.draw_idle()

    def export_result(self):
        if self.result is None:
            return
        try:
            if self.input_signature() != self.result_signature:
                raise ValueError("Measurements or settings changed. Calculate again before exporting.")
        except ValueError as e:
            messagebox.showerror("Recalculate", str(e))
            return
        directory = filedialog.askdirectory(title="Choose output folder (existing band files will be replaced)")
        if directory:
            try:
                export(self.result, directory)
                self.status.set(f"Exported low.wav, mid.wav, high.wav, manifest/report and ZIP to {directory}")
            except (OSError, ValueError) as e:
                messagebox.showerror("Export", str(e))


if __name__ == "__main__":
    App().mainloop()
