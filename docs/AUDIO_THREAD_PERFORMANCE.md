# Audio thread performance

How the real-time loop keeps up, and how to see where its time goes.

## Seeing it: Native DSP Truth → Processing time

While the screen is open (only then), the engine times every stage of the chain
(`NativeBmwProfiler.h`). The chain runs each stage over a whole 64-frame chunk before the next
(`NativeBmwDspProcessor::processChunk()`, bit-identical to frame by frame), so a stage costs one
clock read per chunk to time. The section shows, averaged over the last few seconds:

- the buffer's budget (its own duration) and the engine's time per buffer, mean and max;
- of that, the thread's **CPU time** and the time it spent **off the CPU** (preempted or
  waiting) -- large off-CPU time points at scheduling, not at the DSP;
- the **core** the last buffer ran on and its clock;
- every stage, most expensive first: mean and max ms per buffer and its share.

## Keeping up: fast cores and a performance hint

`AudioThreadTuning` (the worker thread, at start):

- **Fast cores.** The thread pins itself (`sched_setaffinity`, `NativeAudioThread.cpp`) to the
  CPUs with the device's highest hardware maximum clock that the app may use. With no faster
  cluster, the fast cores outside the app's cpuset, or the kernel refusing, its affinity is left
  as it was (automatic fallback). The result shows on Native DSP Truth.
- **Performance hint** (Android 12+, where the device supports it): a `PerformanceHintManager`
  session for the thread, target **50 % of the buffer's budget**, told each buffer's engine time,
  so the governor raises clocks before the deadline is close. "unsupported" on Truth means the
  device's power HAL doesn't offer it.

## What got cheaper

Per 3840-frame buffer on an x86-64 host, the car's chain (Pre EQ 14 bands, 8192-tap IR, 2-way,
tilt, virtual bass, virtual centre, mono bass, MBC, master + bus limiters, loudness): **4.53 ms
-> 2.40 ms**. On the head unit at ~600 MHz expect roughly ten times these figures.

| Stage | Before | After | How |
|---|---:|---:|---|
| Pre EQ (14 bands) | 841 µs | 444 µs | the bank over the whole chunk, sections in skewed groups of three (bit-identical); band EQ the same |
| MBC | 1000 µs | 570 µs | gain computer every 8 samples; dry all-passes off at 100 % Mix |
| Convolver | 658 µs | 104 µs | 1024-frame partitions (21 ms latency while on; none when off) |
| Master limiter | 501 µs | 182 µs | exact true-peak gate (bit-identical) |
| Virtual bass | 348 µs | 190 µs | sub band and harmonics at half rate |
| Virtual centre | 193 µs | 111 µs | detector filters as NEON pairs (bit-identical) |
| Band delay + gain | 81 µs | 20 µs | whole-sample delays skip the 16-tap kernel (bit-identical) |
| Muted bands | full chain | none | a band muted on both outputs isn't run |

The MBC's split tree, the band crossovers and tilt run the same way (`runSvfChainBlock()` /
`runBiquadPairChainBlock()`), bit-identical. On the x86 host that makes no measurable
difference -- an out-of-order core already overlaps those short (2-4 section) chains -- but it
is aimed at the head unit's in-order cores, where a section waiting on the one before it stalls
the core; Processing time on the device shows what it buys there.

Biggest left: the MBC's split tree, the Pre EQ, the crossovers and tilt.
