# Native BMW four-output architecture

The native path now treats Front Left and Front Right as virtual inputs feeding four stable logical outputs: Low Left, Low Right, Mid Left and Mid Right. The default 2x4 matrix is unity on the same side and zero cross-channel, so it preserves the previous stereo topology. Final internal stereo is reconstructed only as `Low Left + Mid Left` and `Low Right + Mid Right`; the existing physical-output swap remains after the limiter because it matches the vehicle wiring.

The linear processing order is: DC blocker, input trim and the legacy Full Range bank (shown as **Input Correction**), routing, output crossover, output PEQ, up to two output all-pass sections, polarity and the existing 0-2.8 ms delay, band dynamics/gain, stereo reconstruction, tilt/post gain, limiter, then the existing physical-output mapping. Mono bass remains confined to its existing low-frequency branch.

State indices 0-45 are unchanged. Indices 46-53 contain the routing matrix and 54-85 contain two all-pass sections per output. The index-keyed atomic store pads older states with stereo routing and disabled all-pass defaults; it never rewrites the prior primary state unless a complete validated state is saved. No delay range or delay-line capacity changed.

The response model reports complete-path magnitude and wrapped phase. All-pass coefficients are rebuilt only on configuration or sample-rate changes; the audio callback performs no allocation, locking, coefficient generation, or file access.
