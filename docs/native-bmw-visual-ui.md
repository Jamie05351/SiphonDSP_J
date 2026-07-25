# Native BMW DSP visual UI

This branch is reserved for visual controls and metering layered on top of the existing native BMW DSP engine. It must not replace or duplicate the native processing path.

## Scope

- Combined crossover and tonal-response graph driven from the current BMW DSP values.
- Compressor transfer curve and live gain-reduction display.
- Low-band input/output level meters.
- Compact signal-flow overview for subsonic, crossover, delay, compressor, tilt and output routing.
- Clear read-only status for permanent L/R correction and active sample rate.

## Guardrails

- No changes to filter topology, dynamics behaviour, routing or output swap in this PR unless required for read-only metering.
- UI rendering must not run on the audio thread.
- Meter values exposed by JNI must be lock-free or copied atomically and must never block processing.
- Graph updates should be throttled for the 1280 x 480 head-unit display.
- Existing presets and full-DSP import/export remain the source of truth.
- Equalizer314 is a design reference only unless copied code is explicitly attributed and retained under GPL-3.0-compatible terms.

## Delivery order

1. Static response/crossover graph.
2. Compressor curve.
3. Native read-only metering snapshot.
4. Gain-reduction and low-band meters.
5. Optional draggable visual controls after device validation.

## Validation

- APK build passes for RootlessFull Preview.
- No audio artefacts, popping or crackling with the visual panel open or closed.
- No measurable audio-thread blocking.
- Existing BMW DSP values, presets and L/R correction remain unchanged.
- UI remains readable at 1280 x 480.
