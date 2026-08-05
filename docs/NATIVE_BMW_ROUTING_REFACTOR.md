# Native BMW routing refactor

This branch introduces the non-audible foundation for replacing the fixed low/mid native DSP topology with generic output-channel routing.

## Target signal flow

```text
Stereo capture
  -> dedicated input correction L/R
  -> routing matrix
  -> Low L / Low R / Mid L / Mid R output channels
  -> crossover, output PEQ, all-pass, polarity, delay, dynamics and gain
  -> sum Low L + Mid L to final Left
  -> sum Low R + Mid R to final Right
  -> final post gain, limiter and transport ramp
```

The final sum remains stereo. Left and right are never collapsed together unless an explicit mono-bass route is selected.

## Added foundation

`NativeBmwRouting.h` provides:

- Generic four-output descriptors rather than hard-coded routing assumptions.
- An explicit 2x4 routing matrix whose defaults preserve the current audible topology.
- Dedicated input-correction state, separate from speaker/output correction.
- First- and second-order all-pass coefficient generation.
- A fixed, explicit stereo reconstruction helper.

## Integration sequence

1. Include `NativeBmwRouting.h` in `NativeBmwDspProcessor`.
2. Route corrected stereo input through `RoutingMatrix::process()`.
3. Move low/mid channel gain, polarity, delay and future crossover state into `OutputChannelDescriptor`-backed runtime objects.
4. Run each logical output through its existing low or mid processing path.
5. Replace the hand-written final branch addition with `sumToStereo()`.
6. Add a dedicated input-correction PEQ bank before routing.
7. Add all-pass runtime biquads and configuration/UI state.
8. Extend the response calculator to show magnitude, phase and group delay for the combined per-output chain.

## Compatibility rule

Until the integration steps are complete, this header is intentionally not included by the active processor. This keeps the current DSP binary and tuning behaviour unchanged while the new architecture is reviewed.
