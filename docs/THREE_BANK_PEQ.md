# Three-bank parametric EQ

The BMW processing path has one authoritative parametric-EQ state containing
Full Range, Low Band, and Mid Band scopes. Every committed edit validates and
applies that complete state atomically; a rejected apply leaves the previous
engine, persisted state, editor, and graph unchanged.

## Signal flow

- **Full Range** PEQ and preamp operate before the crossover split.
- **Low Band** PEQ operates inside the post-crossover low branch.
- **Mid Band** PEQ operates inside the post-crossover mid branch.
- The permanent final left/right correction remains after the processing chain.
  UI `Left` and `Right` labels describe the corrected physical outputs, not an
  intermediate buffer order.

Each scope supports up to 16 peaking, low-shelf, or high-shelf filters. Filters
may target both channels, Left, or Right. Frequency must be at least 20 Hz and
below the current Nyquist frequency, gain is limited to -30..30 dB, and Q is
limited to 0.1..30.

## Editing and graph

Tap a numbered graph point or list row to select the same stable filter UUID.
Drag horizontally for logarithmic frequency and vertically for gain. Dragging
previews only; release creates one complete-state transaction. Cancellation,
multi-touch, teardown, validation failure, or native rejection restores the
committed point.

Graph options control individual-filter overlays and Left/Right presentation.
They are stored separately from DSP state and never reconfigure audio.

Precise frequency, gain, Q, type, and channel editing remains available in the
normal filter editor. Low and Mid exports always use a 0 dB preamp.

## Undo, redo, copy, and order

Undo and Redo retain at most 20 complete committed state snapshots. Draft
movement, selection, scope changes, cancelled edits, and graph display changes
do not enter history. Undo/redo candidates use the same validation and atomic
native apply path, and their history cursor advances only after success.

Filter tools can duplicate a filter, copy it to another scope, move it up or
down, copy Left filters to Right (or vice versa), and split Both-channel filters
into separate Left and Right copies. New filters always receive new UUIDs.
Copies append and are rejected before apply if the 16-filter scope limit would
be exceeded. Reordering preserves UUIDs and values; order can still produce
small finite-precision differences in a real processing chain.

## Presets and imports

Complete presets are versioned JSON and contain the enabled state, Full Range
preamp, all three scopes, filter types, values, channels, and UUIDs. Import
parses and validates a temporary state, shows a destructive replacement
confirmation, and commits once. Graph display settings are never part of a DSP
preset. Unsupported future versions, malformed UUIDs, duplicate UUIDs, unknown
filter/channel codes, and invalid DSP values are rejected.

Equalizer APO-style import remains scoped to the currently selected bank and
offers Replace, Append, or Cancel. It reports skipped malformed/unsupported
lines and validates the resulting complete state. Full Range replacement may
import preamp; Low and Mid never do.

## Migration and troubleshooting

Version-1 three-bank state restores directly. On upgrade from the legacy
Full-Range-only preferences, valid Full Range filters and preamp are retained
while Low and Mid start empty. Persistence occurs only after successful native
application.

If an edit is rejected, check:

- frequency is below Nyquist for the active sample rate;
- Q and gain are inside the limits above;
- the destination scope has no more than 16 filters;
- imported presets use a supported format version and valid UUIDs.

The graph predicts linear biquad response. Compressor, limiter, nonlinear
stages, channel summing, and measurement conditions can make measured output
differ from that prediction.
