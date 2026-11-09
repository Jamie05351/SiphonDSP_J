package app.siphondsp.model

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber

object NativeBmwDspValues {
    // No longer written to -- kept so load() can migrate anyone still on the old
    // SharedPreferences blob (pre-NativeBmwDspStore) onto the new atomic file store.
    const val PREFS = "native_bmw_dsp"
    const val KEY = "values"

    // The slot layout -- every index, its default, and whether native or only Kotlin reads it --
    // is generated from app/src/main/schema/native_bmw_dsp.schema into NativeBmwDspSchema (and the
    // C++ header, native-tests/default_config.txt and docs/NATIVE_BMW_DSP_SCHEMA.md). The INDEX_*
    // constants here re-export it so call sites keep their names; change the layout in the schema.
    // Slots are append-only: saves are stored by index, and the migrate*IfNeeded() functions below
    // seed each newer block for an older save.
    const val SIZE = NativeBmwDspSchema.SIZE

    const val INDEX_ENABLED = NativeBmwDspSchema.INDEX_ENABLED
    const val INDEX_LPF_PASS = NativeBmwDspSchema.INDEX_LPF_PASS
    const val INDEX_HPF_PASS = NativeBmwDspSchema.INDEX_HPF_PASS
    const val INDEX_CHANNEL_MUTE = NativeBmwDspSchema.INDEX_CHANNEL_MUTE
    const val INDEX_MEASUREMENT_MUTE = NativeBmwDspSchema.INDEX_MEASUREMENT_MUTE
    const val INDEX_HEADROOM = NativeBmwDspSchema.INDEX_HEADROOM
    const val INDEX_LOW_GAIN_L = NativeBmwDspSchema.INDEX_LOW_GAIN_L
    const val INDEX_LOW_GAIN_R = NativeBmwDspSchema.INDEX_LOW_GAIN_R
    const val INDEX_MID_GAIN_L = NativeBmwDspSchema.INDEX_MID_GAIN_L
    const val INDEX_MID_GAIN_R = NativeBmwDspSchema.INDEX_MID_GAIN_R
    const val INDEX_POST_GAIN_L = NativeBmwDspSchema.INDEX_POST_GAIN_L
    const val INDEX_POST_GAIN_R = NativeBmwDspSchema.INDEX_POST_GAIN_R

    // Legacy shared-band settings retained for migration/backward compatibility. The native
    // engine now consumes the independent per-output block at INDEX_OUTPUT_CONFIG.
    const val INDEX_SUBSONIC_ENABLED = NativeBmwDspSchema.INDEX_SUBSONIC_ENABLED
    const val INDEX_SUBSONIC_FREQ = NativeBmwDspSchema.INDEX_SUBSONIC_FREQ
    const val INDEX_LOW_MUTE = NativeBmwDspSchema.INDEX_LOW_MUTE
    const val INDEX_LOW_CROSSOVER_FREQ = NativeBmwDspSchema.INDEX_LOW_CROSSOVER_FREQ
    const val INDEX_LOW_LR4 = NativeBmwDspSchema.INDEX_LOW_LR4
    const val INDEX_MID_MUTE = NativeBmwDspSchema.INDEX_MID_MUTE
    const val INDEX_MID_CROSSOVER_FREQ = NativeBmwDspSchema.INDEX_MID_CROSSOVER_FREQ
    const val INDEX_LOW_INVERT = NativeBmwDspSchema.INDEX_LOW_INVERT
    const val INDEX_MID_INVERT = NativeBmwDspSchema.INDEX_MID_INVERT

    const val INDEX_MID_DELAY_L = NativeBmwDspSchema.INDEX_MID_DELAY_L
    const val INDEX_MID_DELAY_R = NativeBmwDspSchema.INDEX_MID_DELAY_R
    const val INDEX_LOW_DELAY_L = NativeBmwDspSchema.INDEX_LOW_DELAY_L
    const val INDEX_LOW_DELAY_R = NativeBmwDspSchema.INDEX_LOW_DELAY_R
    const val INDEX_TILT_ENABLED = NativeBmwDspSchema.INDEX_TILT_ENABLED
    const val INDEX_TILT_AMOUNT = NativeBmwDspSchema.INDEX_TILT_AMOUNT
    // The tilt runs two low shelves and two high shelves at the pivot, each at 0.75 x the stored
    // amount (Tilt::rebuild), so each end of the response moves 1.5 x the stored amount: a saved
    // 3 dB is +4.5 dB of bass and -4.5 dB of treble. The stored value keeps that meaning (every
    // saved tune sounds the same); the UI shows and edits the real end gain.
    const val TILT_END_GAIN_PER_AMOUNT = 1.5f
    const val INDEX_TILT_FREQ = NativeBmwDspSchema.INDEX_TILT_FREQ

    // Legacy band-linked compressor settings, used as migration seeds.
    const val INDEX_LOW_COMPRESSOR_ENABLED = NativeBmwDspSchema.INDEX_LOW_COMPRESSOR_ENABLED
    const val INDEX_LOW_COMPRESSOR_THRESHOLD = NativeBmwDspSchema.INDEX_LOW_COMPRESSOR_THRESHOLD
    const val INDEX_LOW_COMPRESSOR_RATIO = NativeBmwDspSchema.INDEX_LOW_COMPRESSOR_RATIO
    const val INDEX_LOW_COMPRESSOR_KNEE = NativeBmwDspSchema.INDEX_LOW_COMPRESSOR_KNEE
    const val INDEX_LOW_COMPRESSOR_ATTACK = NativeBmwDspSchema.INDEX_LOW_COMPRESSOR_ATTACK
    const val INDEX_LOW_COMPRESSOR_RELEASE = NativeBmwDspSchema.INDEX_LOW_COMPRESSOR_RELEASE
    const val INDEX_LOW_COMPRESSOR_MAKEUP = NativeBmwDspSchema.INDEX_LOW_COMPRESSOR_MAKEUP

    const val INDEX_MID_COMPRESSOR_ENABLED = NativeBmwDspSchema.INDEX_MID_COMPRESSOR_ENABLED
    const val INDEX_MID_COMPRESSOR_THRESHOLD = NativeBmwDspSchema.INDEX_MID_COMPRESSOR_THRESHOLD
    const val INDEX_MID_COMPRESSOR_RATIO = NativeBmwDspSchema.INDEX_MID_COMPRESSOR_RATIO
    const val INDEX_MID_COMPRESSOR_KNEE = NativeBmwDspSchema.INDEX_MID_COMPRESSOR_KNEE
    const val INDEX_MID_COMPRESSOR_ATTACK = NativeBmwDspSchema.INDEX_MID_COMPRESSOR_ATTACK
    const val INDEX_MID_COMPRESSOR_RELEASE = NativeBmwDspSchema.INDEX_MID_COMPRESSOR_RELEASE
    const val INDEX_MID_COMPRESSOR_MAKEUP = NativeBmwDspSchema.INDEX_MID_COMPRESSOR_MAKEUP

    // 42..45 were the Mono Bass enable/freq/blend/makeup (a frequency-dependent mono/stereo
    // blend for the low paths). The feature was removed (disabled by default, never proven
    // useful) and the slots were reclaimed in place rather than by shrinking the array -- same
    // rationale as 139..142 below. 43..45 are still not read natively; a leftover value from an
    // older save there is simply ignored. 42 is now INDEX_CROSSOVER_TYPE_MIGRATED, a Kotlin-only
    // migration marker (see migrateCrossoverTypeIfNeeded) -- same pattern as 140/181/188/191.
    const val INDEX_CROSSOVER_TYPE_MIGRATED = NativeBmwDspSchema.INDEX_CROSSOVER_TYPE_MIGRATED
    // Distinct from the legacy Mono Bass enable boolean (0/1) that used to live in slot 42 -- see
    // migrateCrossoverTypeIfNeeded.
    const val CROSSOVER_TYPE_MIGRATED_MARKER = 2f

    // Advanced stereo routing matrix: four logical outputs x Front L/Front R source.
    const val INDEX_ROUTING = NativeBmwDspSchema.INDEX_ROUTING
    const val ROUTING_VALUE_COUNT = 8
    const val INDEX_ROUTE_LOW_LEFT_FRONT_LEFT = NativeBmwDspSchema.INDEX_ROUTE_LOW_LEFT_FRONT_LEFT
    const val INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT = NativeBmwDspSchema.INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT
    const val INDEX_ROUTE_LOW_RIGHT_FRONT_LEFT = NativeBmwDspSchema.INDEX_ROUTE_LOW_RIGHT_FRONT_LEFT
    const val INDEX_ROUTE_LOW_RIGHT_FRONT_RIGHT = NativeBmwDspSchema.INDEX_ROUTE_LOW_RIGHT_FRONT_RIGHT
    const val INDEX_ROUTE_MID_LEFT_FRONT_LEFT = NativeBmwDspSchema.INDEX_ROUTE_MID_LEFT_FRONT_LEFT
    const val INDEX_ROUTE_MID_LEFT_FRONT_RIGHT = NativeBmwDspSchema.INDEX_ROUTE_MID_LEFT_FRONT_RIGHT
    const val INDEX_ROUTE_MID_RIGHT_FRONT_LEFT = NativeBmwDspSchema.INDEX_ROUTE_MID_RIGHT_FRONT_LEFT
    const val INDEX_ROUTE_MID_RIGHT_FRONT_RIGHT = NativeBmwDspSchema.INDEX_ROUTE_MID_RIGHT_FRONT_RIGHT

    // Two all-pass sections per exact output: [enabled, order(1|2), frequencyHz, Q].
    const val INDEX_ALL_PASS = NativeBmwDspSchema.INDEX_ALL_PASS
    const val ALL_PASS_SECTIONS_PER_OUTPUT = 2
    const val ALL_PASS_SECTION_WIDTH = 4
    const val ALL_PASS_VALUE_COUNT = 32

    // Versioned independent-output block. Output order matches NativeBmwRouting::OutputId.
    const val INDEX_OUTPUT_SCHEMA_VERSION = NativeBmwDspSchema.INDEX_OUTPUT_SCHEMA_VERSION
    const val OUTPUT_SCHEMA_VERSION = 1f
    const val INDEX_OUTPUT_CONFIG = NativeBmwDspSchema.INDEX_OUTPUT_CONFIG
    const val OUTPUT_CONFIG_WIDTH = 13
    const val OUTPUT_COUNT = 4

    const val OUTPUT_LOW_LEFT = 0
    const val OUTPUT_LOW_RIGHT = 1
    const val OUTPUT_MID_LEFT = 2
    const val OUTPUT_MID_RIGHT = 3

    const val FIELD_CROSSOVER_FREQ = 0
    const val FIELD_CROSSOVER_TYPE = 1
    const val CROSSOVER_TYPE_BW2 = 0f
    const val CROSSOVER_TYPE_BW3 = 1f
    const val CROSSOVER_TYPE_LR4 = 2f
    // Append to preserve existing persisted topology IDs.
    const val CROSSOVER_TYPE_BW1 = 3f
    const val CROSSOVER_TYPE_BW4 = 4f
    const val FIELD_SUBSONIC_ENABLED = 2
    const val FIELD_SUBSONIC_FREQ = 3
    const val FIELD_MUTE = 4
    const val FIELD_INVERT = 5
    const val FIELD_COMPRESSOR_ENABLED = 6
    const val FIELD_COMPRESSOR_THRESHOLD = 7
    const val FIELD_COMPRESSOR_RATIO = 8
    const val FIELD_COMPRESSOR_KNEE = 9
    const val FIELD_COMPRESSOR_ATTACK = 10
    const val FIELD_COMPRESSOR_RELEASE = 11
    const val FIELD_COMPRESSOR_MAKEUP = 12

    fun outputIndex(output: Int, field: Int): Int {
        require(output in 0 until OUTPUT_COUNT) { "Invalid BMW output $output" }
        require(field in 0 until OUTPUT_CONFIG_WIDTH) { "Invalid BMW output field $field" }
        return INDEX_OUTPUT_CONFIG + output * OUTPUT_CONFIG_WIDTH + field
    }

    // 139..142: formerly the Pultec-style bass boost/cut stage's enabled/freq/boost/cut. Removed
    // (unused feature, no native processing left). 139/140 hold the meas-mute stopband offset +
    // its migration marker; 141/142 (briefly a removed Mid-band LPF) are now the stage-centering
    // L/R alignment delay -- see below.

    // Measurement-mute bus brick-wall: octaves to walk the LR8 corner off the opposite band's
    // crossover and into the stopband, so the band still playing keeps its own transition region
    // for measurement. 0 == original on-crossover behaviour. Consumed natively (v[139]).
    const val INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES = NativeBmwDspSchema.INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES
    const val DEFAULT_MEAS_MUTE_STOPBAND_OCTAVES = 1f

    // One-time migration counter for the reclaimed 139..142 block. Kotlin-only -- native never
    // reads index 140. 0 = unseeded; >=1 = slot 139 seeded to the meas-mute default;
    // >=2 = slots 141/142 zeroed for the stage-delay reclaim (a leftover Mid-LPF corner value
    // there would otherwise clamp to a several-ms stage delay). See load()'s migrate* calls.
    const val INDEX_MEAS_MUTE_STOPBAND_MIGRATED = NativeBmwDspSchema.INDEX_MEAS_MUTE_STOPBAND_MIGRATED

    // 141, 142: stage-centering L/R alignment delay in ms -- an independent post-sum correction
    // layer for L/R stage imaging, downstream of the per-output crossover/driver-alignment
    // delays (INDEX_LOW/MID_DELAY_*). Applied natively to the summed stereo bus after the master
    // limiter, the last stage before the hardware L/R swap. Clamped to [0, STAGE_DELAY_MAX_MS].
    // Reclaimed from a removed Mid-band LPF; migrateStageDelayReclaimIfNeeded() zeroes any
    // leftover value once.
    const val INDEX_STAGE_DELAY_L = NativeBmwDspSchema.INDEX_STAGE_DELAY_L
    const val INDEX_STAGE_DELAY_R = NativeBmwDspSchema.INDEX_STAGE_DELAY_R
    const val STAGE_DELAY_MAX_MS = 10f

    // UI-only: links Low Left/Right and Mid Left/Right delay editing together on the Gains &
    // Delay page. Native never reads this index -- see NativeBmwDspProcessor::configure(), which
    // consumes 0..139 and 144..191.
    const val INDEX_DELAY_LINKED = NativeBmwDspSchema.INDEX_DELAY_LINKED

    // ---------------------------------------------------------------------------------------
    // Pre-crossover multiband compressor (MBC) -- indices 144..181. New in the 144 -> 192
    // schema growth. Operates on the full-range stereo bus AFTER headroom and BEFORE the
    // routing matrix / band crossovers (see NativeBmwDspProcessor::processFrame). Four bands
    // split by three Linkwitz-Riley 24 dB/oct crossovers and reconstructed flat via all-pass
    // compensation on the already-split lower bands -- same principle as the mono-bass
    // all-pass fix. Ships DISABLED; the metering JNI and the UI land in later PRs.
    const val INDEX_MBC_ENABLED = NativeBmwDspSchema.INDEX_MBC_ENABLED
    const val INDEX_MBC_MIX = NativeBmwDspSchema.INDEX_MBC_MIX // dry/wet blend, percent 0..100 (100 = fully processed)
    const val INDEX_MBC_XO_0 = NativeBmwDspSchema.INDEX_MBC_XO_0 // band 0 | band 1 split, Hz
    const val INDEX_MBC_XO_1 = NativeBmwDspSchema.INDEX_MBC_XO_1 // band 1 | band 2 split, Hz
    const val INDEX_MBC_XO_2 = NativeBmwDspSchema.INDEX_MBC_XO_2 // band 2 | band 3 split, Hz

    const val INDEX_MBC_BANDS = NativeBmwDspSchema.INDEX_MBC_BANDS
    const val MBC_BAND_COUNT = 4
    const val MBC_BAND_WIDTH = 8
    const val MBC_FIELD_ENABLED = 0
    const val MBC_FIELD_THRESHOLD = 1
    const val MBC_FIELD_RATIO = 2
    const val MBC_FIELD_KNEE = 3
    const val MBC_FIELD_ATTACK = 4
    const val MBC_FIELD_RELEASE = 5
    const val MBC_FIELD_MAKEUP = 6
    const val MBC_FIELD_STEREO_LINK = 7 // 1 = detector on max(|L|,|R|), one gain cell drives both

    fun mbcBandIndex(band: Int, field: Int): Int {
        require(band in 0 until MBC_BAND_COUNT) { "Invalid MBC band $band" }
        require(field in 0 until MBC_BAND_WIDTH) { "Invalid MBC band field $field" }
        return INDEX_MBC_BANDS + band * MBC_BAND_WIDTH + field
    }

    // One-time marker: 1 once an existing saved config has had the MBC + limiter block
    // (144..191) seeded to its shipped-disabled defaults. Lets the block ship without a schema
    // *version* bump and guarantees the feature stays off for existing users even if a later
    // build flips a default on. Kotlin-only -- native never reads index 181.
    const val INDEX_MBC_MIGRATED = NativeBmwDspSchema.INDEX_MBC_MIGRATED

    // Per-bus output limiter (Low bus, Mid bus) -- indices 182..187. Ships DISABLED. Threshold in
    // dBFS, release in ms; native uses a short lookahead and an infinite (brick-wall) ratio.
    const val INDEX_BUS_LIMITER_LOW_ENABLED = NativeBmwDspSchema.INDEX_BUS_LIMITER_LOW_ENABLED
    const val INDEX_BUS_LIMITER_LOW_THRESHOLD = NativeBmwDspSchema.INDEX_BUS_LIMITER_LOW_THRESHOLD
    const val INDEX_BUS_LIMITER_LOW_RELEASE = NativeBmwDspSchema.INDEX_BUS_LIMITER_LOW_RELEASE
    const val INDEX_BUS_LIMITER_MID_ENABLED = NativeBmwDspSchema.INDEX_BUS_LIMITER_MID_ENABLED
    const val INDEX_BUS_LIMITER_MID_THRESHOLD = NativeBmwDspSchema.INDEX_BUS_LIMITER_MID_THRESHOLD
    const val INDEX_BUS_LIMITER_MID_RELEASE = NativeBmwDspSchema.INDEX_BUS_LIMITER_MID_RELEASE

    // One-time marker: 1 once the legacy per-output compressor (INDEX_LOW/MID_COMPRESSOR_* and
    // the per-output FIELD_COMPRESSOR_* slots) has been force-disabled. The multiband compressor +
    // per-bus limiters replaced it and its editing UI was retired. Native no longer has the stage
    // at all and ignores those slots; they stay in the schema so no later index moves. Kotlin-only
    // -- this marker just gates the one-time flip. See migrateDisableLegacyCompressorIfNeeded.
    const val INDEX_LEGACY_COMP_DISABLED_MIGRATED = NativeBmwDspSchema.INDEX_LEGACY_COMP_DISABLED_MIGRATED

    // Master brick-wall limiter on the summed output (indices 189..191). Was a fixed -1 dBFS
    // ceiling with no controls; now enable + threshold. enable == 0 is a TRUE bypass -- nothing
    // constrains the output. Native reads 189/190; 191 is the one-time seed marker.
    const val INDEX_MASTER_LIMITER_ENABLED = NativeBmwDspSchema.INDEX_MASTER_LIMITER_ENABLED
    const val INDEX_MASTER_LIMITER_THRESHOLD = NativeBmwDspSchema.INDEX_MASTER_LIMITER_THRESHOLD
    const val INDEX_MASTER_LIMITER_MIGRATED = NativeBmwDspSchema.INDEX_MASTER_LIMITER_MIGRATED
    const val DEFAULT_MASTER_LIMITER_THRESHOLD_DB = -1f

    // Measurement signal generator (192..199). type: 0 off, 1 log sweep, 2 pink periodic noise.
    // Nonzero replaces the real DSP input entirely, pre-crossover -- see
    // NativeBmwDspProcessor::processFrame(). Native reads all eight; no migration marker.
    const val INDEX_MEAS_GEN_TYPE = NativeBmwDspSchema.INDEX_MEAS_GEN_TYPE
    const val INDEX_MEAS_GEN_SWEEP_START_HZ = NativeBmwDspSchema.INDEX_MEAS_GEN_SWEEP_START_HZ
    const val INDEX_MEAS_GEN_SWEEP_END_HZ = NativeBmwDspSchema.INDEX_MEAS_GEN_SWEEP_END_HZ
    const val INDEX_MEAS_GEN_SWEEP_DURATION_S = NativeBmwDspSchema.INDEX_MEAS_GEN_SWEEP_DURATION_S
    const val INDEX_MEAS_GEN_SWEEP_LEVEL_DB = NativeBmwDspSchema.INDEX_MEAS_GEN_SWEEP_LEVEL_DB
    const val INDEX_MEAS_GEN_PINK_PERIOD_S = NativeBmwDspSchema.INDEX_MEAS_GEN_PINK_PERIOD_S
    const val INDEX_MEAS_GEN_PINK_LEVEL_DB = NativeBmwDspSchema.INDEX_MEAS_GEN_PINK_LEVEL_DB
    // Only meaningful while INDEX_MEAS_GEN_TYPE == 1 (sweep): wraps it in REW's Acoustic Timing
    // Reference cycle (Mid sweep, Low sweep, Mid sweep, bracketed by timing chirps) instead of
    // the bare continuously-looping single sweep -- see
    // NativeBmwMeasurementGenerator::configureTimingRef()/nextTimingRefSample().
    const val INDEX_MEAS_GEN_TIMING_REF_ENABLED = NativeBmwDspSchema.INDEX_MEAS_GEN_TIMING_REF_ENABLED
    // 0 = combined (chirp+sweep share both channels), 1 = split (chirp-only left / sweep-only
    // right). Only meaningful while INDEX_MEAS_GEN_TIMING_REF_ENABLED is set.
    const val INDEX_MEAS_GEN_TIMING_REF_SPLIT_CHANNELS = NativeBmwDspSchema.INDEX_MEAS_GEN_TIMING_REF_SPLIT_CHANNELS
    const val INDEX_MEAS_GEN_TIMING_REF_MID_START_HZ = NativeBmwDspSchema.INDEX_MEAS_GEN_TIMING_REF_MID_START_HZ
    const val INDEX_MEAS_GEN_TIMING_REF_MID_END_HZ = NativeBmwDspSchema.INDEX_MEAS_GEN_TIMING_REF_MID_END_HZ
    const val INDEX_MEAS_GEN_TIMING_REF_LOW_START_HZ = NativeBmwDspSchema.INDEX_MEAS_GEN_TIMING_REF_LOW_START_HZ
    const val INDEX_MEAS_GEN_TIMING_REF_LOW_END_HZ = NativeBmwDspSchema.INDEX_MEAS_GEN_TIMING_REF_LOW_END_HZ

    // Mid's optional upper (Mid/High) bandpass corner (205..209) -- turns Mid from HPF-only into
    // a true bandpass. Only Mid has this; Low/High don't. A 2-slot (Left/Right) tail block, not
    // folded into the per-output INDEX_OUTPUT_CONFIG block above -- growing that block's width in
    // place would shift every index from 139 onward for every output, not just Mid. Use
    // midUpperXoIndex(OUTPUT_MID_LEFT/RIGHT, field) rather than these base constants directly.
    const val INDEX_MID_UPPER_XO = NativeBmwDspSchema.INDEX_MID_UPPER_XO
    const val MID_UPPER_XO_WIDTH = 2
    const val MID_UPPER_XO_FIELD_FREQ = 0
    const val MID_UPPER_XO_FIELD_ENABLED = 1
    // One-time marker: 1 once an existing saved config has had the Mid upper-corner block seeded
    // disabled. Kotlin-only -- native never reads this index. See migrateMidUpperCrossoverIfNeeded.
    const val INDEX_MID_UPPER_XO_MIGRATED = NativeBmwDspSchema.INDEX_MID_UPPER_XO_MIGRATED
    const val DEFAULT_MID_UPPER_XO_FREQ = 3000f

    // Mid's upper-corner lowpass slope (288..290), independent of Mid's own FIELD_CROSSOVER_TYPE,
    // which now shapes only Mid's highpass. Same 0..4 encoding. Use midUpperXoTypeIndex().
    const val INDEX_MID_UPPER_XO_TYPE = NativeBmwDspSchema.INDEX_MID_UPPER_XO_TYPE
    // One-time marker: 1 once an existing saved config has had both upper slopes seeded from Mid's
    // own type. Kotlin-only -- native never reads this index. See migrateMidUpperXoTypeIfNeeded.
    const val INDEX_MID_UPPER_XO_TYPE_MIGRATED = NativeBmwDspSchema.INDEX_MID_UPPER_XO_TYPE_MIGRATED

    fun midUpperXoTypeIndex(output: Int): Int {
        require(output == OUTPUT_MID_LEFT || output == OUTPUT_MID_RIGHT) {
            "Mid upper crossover slope only exists for Mid outputs, got $output"
        }
        return INDEX_MID_UPPER_XO_TYPE + if (output == OUTPUT_MID_LEFT) 0 else 1
    }

    fun midUpperXoIndex(output: Int, field: Int): Int {
        require(output == OUTPUT_MID_LEFT || output == OUTPUT_MID_RIGHT) {
            "Mid upper crossover corner only exists for Mid outputs, got $output"
        }
        require(field in 0 until MID_UPPER_XO_WIDTH) { "Invalid Mid upper XO field $field" }
        val slot = if (output == OUTPUT_MID_LEFT) 0 else 1
        return INDEX_MID_UPPER_XO + slot * MID_UPPER_XO_WIDTH + field
    }

    // High band (210..261) -- a genuinely new third output, added in the 210 -> 262 growth
    // (docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md). Kept entirely in the schema's tail (its own
    // routing/all-pass/output-config sub-blocks) rather than growing OUTPUT_COUNT/outputIndex()
    // to 6: those stay pinned at 4 forever so the legacy INDEX_ROUTING/INDEX_ALL_PASS/
    // INDEX_OUTPUT_CONFIG block offsets -- which every existing saved config already relies on --
    // can never shift. Use highOutputIndex()/highAllPassIndex() rather than outputIndex() for
    // High. Ships silent via INDEX_HIGH_XO_PASS (both default true) -- unlike
    // INDEX_LPF_PASS/INDEX_HPF_PASS (which only bypass the crossover filter, an accepted
    // pre-existing risk for Low/Mid), true here fully silences High: a tweeter with no HPF ahead
    // of it is a speaker-damage risk from raw bass, and this flag is also the 3-way master-off
    // switch's single write for High, so it must not depend on the per-output mute field also
    // being set correctly. Same conservative opt-in launch as MBC and the bus limiters.
    const val OUTPUT_HIGH_LEFT = 4
    const val OUTPUT_HIGH_RIGHT = 5

    const val INDEX_HIGH_XO_PASS = NativeBmwDspSchema.INDEX_HIGH_XO_PASS
    // High's own crossover passthrough (291): the INDEX_LPF_PASS/INDEX_HPF_PASS contract for High
    // -- its chain skipped, the raw routed signal through. INDEX_HIGH_XO_PASS still wins.
    const val INDEX_HIGH_PASS = NativeBmwDspSchema.INDEX_HIGH_PASS

    // Master volume: the DSP as the car's volume, applied last (after the limiter). -80..0 dB.
    const val INDEX_MASTER_VOLUME_DB = NativeBmwDspSchema.INDEX_MASTER_VOLUME_DB
    const val INDEX_MASTER_MUTE = NativeBmwDspSchema.INDEX_MASTER_MUTE
    const val MASTER_VOLUME_MIN_DB = -80f
    const val MASTER_VOLUME_MAX_DB = 0f

    // Loudness compensation: bass/treble lift that follows the master volume (none at full volume).
    const val INDEX_LOUDNESS_ENABLED = NativeBmwDspSchema.INDEX_LOUDNESS_ENABLED
    const val INDEX_LOUDNESS_STRENGTH = NativeBmwDspSchema.INDEX_LOUDNESS_STRENGTH // percent, 0..100
    // Automatic headroom on (1) or manual (0). Kotlin-only -- native never reads this index; the
    // manual value stays in INDEX_HEADROOM either way, so switching back to manual restores it.
    const val INDEX_HEADROOM_AUTO = NativeBmwDspSchema.INDEX_HEADROOM_AUTO
    // Headroom's range, here and in native configure().
    const val HEADROOM_MIN_DB = -30f
    const val HEADROOM_MAX_DB = 0f
    // Mono Bass: both channels carry (L+R)/2 below the split, stereo above it (complementary
    // LR4, right after the input DC blocker). Range mirrors native MonoBass.
    const val INDEX_MONO_BASS_ENABLED = NativeBmwDspSchema.INDEX_MONO_BASS_ENABLED
    const val INDEX_MONO_BASS_FREQ = NativeBmwDspSchema.INDEX_MONO_BASS_FREQ // Hz
    const val MONO_BASS_MIN_HZ = 60f
    const val MONO_BASS_MAX_HZ = 120f
    const val MONO_BASS_DEFAULT_HZ = 90f
    const val MONO_BASS_STEP_HZ = 5f

    /** Mono Bass's split frequency in Hz as native runs it, or 0 while it's off. */
    fun monoBassSplitHz(values: FloatArray): Double {
        if (values.getOrElse(INDEX_MONO_BASS_ENABLED) { 0f } < .5f) return 0.0
        val hz = values.getOrElse(INDEX_MONO_BASS_FREQ) { MONO_BASS_DEFAULT_HZ }
        return (if (hz.isFinite()) hz else MONO_BASS_DEFAULT_HZ).coerceIn(MONO_BASS_MIN_HZ, MONO_BASS_MAX_HZ).toDouble()
    }
    // Virtual Bass: the sub below the cut rebuilt as harmonics the woofers can play (native
    // VirtualBass, right after Mono Bass); optionally the original sub high-passed away (LR4 at the
    // cut). Ranges mirror native VirtualBass.
    const val INDEX_VIRTUAL_BASS_ENABLED = NativeBmwDspSchema.INDEX_VIRTUAL_BASS_ENABLED
    const val INDEX_VIRTUAL_BASS_FREQ = NativeBmwDspSchema.INDEX_VIRTUAL_BASS_FREQ // cut, Hz
    const val INDEX_VIRTUAL_BASS_AMOUNT = NativeBmwDspSchema.INDEX_VIRTUAL_BASS_AMOUNT // harmonics level, percent
    const val INDEX_VIRTUAL_BASS_REMOVE_SUB = NativeBmwDspSchema.INDEX_VIRTUAL_BASS_REMOVE_SUB
    const val INDEX_VIRTUAL_BASS_CHARACTER = NativeBmwDspSchema.INDEX_VIRTUAL_BASS_CHARACTER // Warm 0 .. Punchy 100, percent
    // The subsonic high-pass's slope, both sides: LR4 (24 dB/oct) or BW2 (12 dB/oct, half the
    // phase turn through the bass).
    const val INDEX_SUBSONIC_SLOPE = NativeBmwDspSchema.INDEX_SUBSONIC_SLOPE
    const val SUBSONIC_SLOPE_LR4 = 0f
    const val SUBSONIC_SLOPE_BW2 = 1f
    // Virtual Bass Range: the harmonics are kept from the reach up to this x the reach (native
    // VirtualBass::kMinRange..kMaxRange). Response: the detector's release, ms (native
    // kMinResponseMs..kMaxResponseMs) -- faster is punchier, slower smoother.
    const val INDEX_VIRTUAL_BASS_RANGE = NativeBmwDspSchema.INDEX_VIRTUAL_BASS_RANGE
    const val INDEX_VIRTUAL_BASS_RESPONSE = NativeBmwDspSchema.INDEX_VIRTUAL_BASS_RESPONSE
    const val VIRTUAL_BASS_MIN_RANGE = 3f
    const val VIRTUAL_BASS_MAX_RANGE = 8f
    const val VIRTUAL_BASS_DEFAULT_RANGE = 5f
    const val VIRTUAL_BASS_STEP_RANGE = 0.5f
    const val VIRTUAL_BASS_MIN_RESPONSE_MS = 100f
    const val VIRTUAL_BASS_MAX_RESPONSE_MS = 500f
    const val VIRTUAL_BASS_DEFAULT_RESPONSE_MS = 200f
    const val VIRTUAL_BASS_STEP_RESPONSE_MS = 25f
    // The cut is the woofers' reach: the lowest note they really play (e.g. where the subsonic HPF
    // starts). Notes below it are rebuilt as harmonics; notes above it are left alone. Capped at
    // 60 Hz so it can't be set into the band the woofers carry.
    const val VIRTUAL_BASS_MIN_HZ = 25f
    const val VIRTUAL_BASS_MAX_HZ = 60f
    const val VIRTUAL_BASS_DEFAULT_HZ = 40f
    // Remove sub is a gentle HP2 this far below the reach (native VirtualBass::kRemovalRatio).
    const val VIRTUAL_BASS_REMOVAL_RATIO = 0.8
    const val VIRTUAL_BASS_STEP_HZ = 5f
    const val VIRTUAL_BASS_DEFAULT_AMOUNT = 50f
    const val VIRTUAL_BASS_DEFAULT_CHARACTER = 50f
    /** How far up Virtual Bass keeps its harmonics, x its cut, as native clamps it. */
    fun virtualBassRange(values: FloatArray): Double {
        val r = values.getOrElse(INDEX_VIRTUAL_BASS_RANGE) { VIRTUAL_BASS_DEFAULT_RANGE }
        return (if (r.isFinite()) r else VIRTUAL_BASS_DEFAULT_RANGE).coerceIn(VIRTUAL_BASS_MIN_RANGE, VIRTUAL_BASS_MAX_RANGE).toDouble()
    }

    /** The top of Virtual Bass's harmonics band in Hz (cut x range), or 0 while it's off. */
    fun virtualBassTopHz(values: FloatArray): Double = virtualBassCutHz(values) * virtualBassRange(values)

    // Virtual Bass's input lowpass (native VirtualBass::kInputRatio x the cut, an LR8) is -6 dB at
    // its corner and falls 48 dB/oct above it, and the highest harmonic it makes is a note's 4th.
    const val VIRTUAL_BASS_INPUT_RATIO = 0.85
    const val VIRTUAL_BASS_HIGHEST_HARMONIC = 4

    /**
     * Where Virtual Bass's harmonics mostly land, in Hz: up to the 4th harmonic of a note at its
     * input lowpass corner (where that note is already 6 dB down; notes above it, and so higher
     * harmonics, fall off steeply), or the top of its band ([virtualBassTopHz]) if that is lower.
     * Not a hard ceiling: a note at the reach still gives a 4th harmonic about 13 dB down, which
     * Range's band filter shapes. 0 while off.
     */
    fun virtualBassHarmonicsTopHz(values: FloatArray): Double {
        val made = virtualBassCutHz(values) * VIRTUAL_BASS_INPUT_RATIO * VIRTUAL_BASS_HIGHEST_HARMONIC
        return minOf(made, virtualBassTopHz(values))
    }

    /** Virtual Bass's cut in Hz as native runs it, or 0 while it's off. */
    fun virtualBassCutHz(values: FloatArray): Double {
        if (values.getOrElse(INDEX_VIRTUAL_BASS_ENABLED) { 0f } < .5f) return 0.0
        val hz = values.getOrElse(INDEX_VIRTUAL_BASS_FREQ) { VIRTUAL_BASS_DEFAULT_HZ }
        return (if (hz.isFinite()) hz else VIRTUAL_BASS_DEFAULT_HZ).coerceIn(VIRTUAL_BASS_MIN_HZ, VIRTUAL_BASS_MAX_HZ).toDouble()
    }

    /**
     * The most Virtual Bass can raise the peak level below [virtualBassTopHz], in dB,
     * or 0 while it's off: 20 log10(1 + rise x Amount) (native VirtualBass::kPeakRiseKept /
     * kPeakRiseRemoved). A lone note's harmonics stay inside it on their own -- they never peak in
     * step with the sub, and with Remove sub on what's taken away almost cancels what's added --
     * and where a note's own harmonics line up with the added ones, the native stage turns its
     * harmonics down for that peak, so the allowance holds for any signal (a native test pins it).
     * A bigger allowance would only turn the whole system down.
     */
    fun virtualBassWorstCaseBoostDb(values: FloatArray): Double {
        if (virtualBassCutHz(values) <= 0.0) return 0.0
        val pct = values.getOrElse(INDEX_VIRTUAL_BASS_AMOUNT) { VIRTUAL_BASS_DEFAULT_AMOUNT }
        val amount = (if (pct.isFinite()) pct else VIRTUAL_BASS_DEFAULT_AMOUNT).coerceIn(0f, 100f) / 100.0
        val removing = values.getOrElse(INDEX_VIRTUAL_BASS_REMOVE_SUB) { 0f } >= .5f
        val rise = if (removing) VIRTUAL_BASS_PEAK_RISE_REMOVED else VIRTUAL_BASS_PEAK_RISE_KEPT
        return 20.0 * kotlin.math.log10(1.0 + rise * amount)
    }
    // Mirrors native VirtualBass::kPeakRiseKept / kPeakRiseRemoved.
    const val VIRTUAL_BASS_PEAK_RISE_KEPT = 0.55
    const val VIRTUAL_BASS_PEAK_RISE_REMOVED = 0.12

    /**
     * The corner of Virtual Bass's Remove sub high-pass (an HP2 at [VIRTUAL_BASS_REMOVAL_RATIO] x
     * the reach), in Hz as native runs it, or 0 when it doesn't run (Virtual Bass off, or Remove
     * sub off). The only part of Virtual Bass a linear response can show -- the harmonics it adds
     * are not a filter.
     */
    fun virtualBassRemovalHz(values: FloatArray): Double {
        if (values.getOrElse(INDEX_VIRTUAL_BASS_REMOVE_SUB) { 0f } < .5f) return 0.0
        return virtualBassCutHz(values) * VIRTUAL_BASS_REMOVAL_RATIO
    }
    // Mirrors native Loudness (NativeBmwCrossovers.h) -- the response graph draws the same lift.
    const val LOUDNESS_BASS_HZ = 100.0
    const val LOUDNESS_TREBLE_HZ = 8000.0
    const val LOUDNESS_BASS_PER_DB = 0.3
    const val LOUDNESS_TREBLE_PER_DB = 0.1
    const val LOUDNESS_BASS_MAX_DB = 12.0
    const val LOUDNESS_TREBLE_MAX_DB = 4.0
    const val INDEX_HIGH_GAIN_L = NativeBmwDspSchema.INDEX_HIGH_GAIN_L
    const val INDEX_HIGH_GAIN_R = NativeBmwDspSchema.INDEX_HIGH_GAIN_R
    const val INDEX_HIGH_DELAY_L = NativeBmwDspSchema.INDEX_HIGH_DELAY_L
    const val INDEX_HIGH_DELAY_R = NativeBmwDspSchema.INDEX_HIGH_DELAY_R

    const val INDEX_ROUTE_HIGH_LEFT_FRONT_LEFT = NativeBmwDspSchema.INDEX_ROUTE_HIGH_LEFT_FRONT_LEFT
    const val INDEX_ROUTE_HIGH_LEFT_FRONT_RIGHT = NativeBmwDspSchema.INDEX_ROUTE_HIGH_LEFT_FRONT_RIGHT
    const val INDEX_ROUTE_HIGH_RIGHT_FRONT_LEFT = NativeBmwDspSchema.INDEX_ROUTE_HIGH_RIGHT_FRONT_LEFT
    const val INDEX_ROUTE_HIGH_RIGHT_FRONT_RIGHT = NativeBmwDspSchema.INDEX_ROUTE_HIGH_RIGHT_FRONT_RIGHT

    const val INDEX_HIGH_ALL_PASS = NativeBmwDspSchema.INDEX_HIGH_ALL_PASS
    const val INDEX_HIGH_OUTPUT_CONFIG = NativeBmwDspSchema.INDEX_HIGH_OUTPUT_CONFIG
    // One-time marker: 1 once an existing saved config has had the whole High block (210..260)
    // seeded muted/disabled. Kotlin-only -- native never reads this index. See
    // migrateHighBandIfNeeded.
    const val INDEX_HIGH_BAND_MIGRATED = NativeBmwDspSchema.INDEX_HIGH_BAND_MIGRATED

    // High-bus brick-wall limiter (262..264) -- same contract as INDEX_BUS_LIMITER_LOW_*/MID_*:
    // threshold in dBFS, release in ms, fixed-fast attack, stereo-linked. Ships DISABLED.
    const val INDEX_BUS_LIMITER_HIGH_ENABLED = NativeBmwDspSchema.INDEX_BUS_LIMITER_HIGH_ENABLED
    const val INDEX_BUS_LIMITER_HIGH_THRESHOLD = NativeBmwDspSchema.INDEX_BUS_LIMITER_HIGH_THRESHOLD
    const val INDEX_BUS_LIMITER_HIGH_RELEASE = NativeBmwDspSchema.INDEX_BUS_LIMITER_HIGH_RELEASE
    // One-time marker: 1 once an existing saved config has had the High-bus limiter seeded
    // disabled. Kotlin-only -- native never reads this index. See
    // migrateHighBusLimiterIfNeeded.
    const val INDEX_BUS_LIMITER_HIGH_MIGRATED = NativeBmwDspSchema.INDEX_BUS_LIMITER_HIGH_MIGRATED

    // Virtual-source stage (266..286): the virtual centre, extracted from L/R and fed back into
    // each internal side with its own level/delay/polarity/all-pass, ahead of routing. Ships
    // DISABLED. See docs/NATIVE_BMW_VIRTUAL_CHANNELS.md.
    const val INDEX_VIRTUAL_ENABLED = NativeBmwDspSchema.INDEX_VIRTUAL_ENABLED
    const val INDEX_VIRTUAL_DETECT_HPF = NativeBmwDspSchema.INDEX_VIRTUAL_DETECT_HPF
    const val INDEX_VIRTUAL_DETECT_LPF = NativeBmwDspSchema.INDEX_VIRTUAL_DETECT_LPF
    const val INDEX_VIRTUAL_ATTACK = NativeBmwDspSchema.INDEX_VIRTUAL_ATTACK
    const val INDEX_VIRTUAL_RELEASE = NativeBmwDspSchema.INDEX_VIRTUAL_RELEASE
    const val INDEX_VIRTUAL_CENTRE_LEVEL = NativeBmwDspSchema.INDEX_VIRTUAL_CENTRE_LEVEL
    const val INDEX_VIRTUAL_SIDE_LEVEL = NativeBmwDspSchema.INDEX_VIRTUAL_SIDE_LEVEL
    // Centre feed into each internal side, Left (273..279) then Right (280..286).
    const val INDEX_VIRTUAL_FEED = NativeBmwDspSchema.INDEX_VIRTUAL_FEED
    const val VIRTUAL_FEED_WIDTH = 7
    const val VIRTUAL_FEED_GAIN = 0
    const val VIRTUAL_FEED_DELAY = 1
    const val VIRTUAL_FEED_POLARITY = 2
    const val VIRTUAL_FEED_AP_ENABLED = 3
    const val VIRTUAL_FEED_AP_FREQ = 4
    const val VIRTUAL_FEED_AP_Q = 5
    const val VIRTUAL_FEED_AP_ORDER = 6
    const val VIRTUAL_SIDE_LEFT = 0
    const val VIRTUAL_SIDE_RIGHT = 1
    // One-time marker: 1 once an existing saved config has had the virtual stage seeded off.
    // Kotlin-only -- native never reads this index. See migrateVirtualIfNeeded.
    const val INDEX_VIRTUAL_MIGRATED = NativeBmwDspSchema.INDEX_VIRTUAL_MIGRATED

    /** The flat index of [field] (a VIRTUAL_FEED_* constant) of the centre feed into [side]. */
    fun virtualFeedIndex(side: Int, field: Int): Int {
        require(side == VIRTUAL_SIDE_LEFT || side == VIRTUAL_SIDE_RIGHT) { "Invalid virtual feed side $side" }
        require(field in 0 until VIRTUAL_FEED_WIDTH) { "Invalid virtual feed field $field" }
        return INDEX_VIRTUAL_FEED + side * VIRTUAL_FEED_WIDTH + field
    }

    fun highOutputIndex(output: Int, field: Int): Int {
        require(output == OUTPUT_HIGH_LEFT || output == OUTPUT_HIGH_RIGHT) {
            "Invalid BMW High output $output"
        }
        require(field in 0 until OUTPUT_CONFIG_WIDTH) { "Invalid BMW High output field $field" }
        val slot = if (output == OUTPUT_HIGH_LEFT) 0 else 1
        return INDEX_HIGH_OUTPUT_CONFIG + slot * OUTPUT_CONFIG_WIDTH + field
    }

    fun highAllPassIndex(output: Int, section: Int, field: Int): Int {
        require(output == OUTPUT_HIGH_LEFT || output == OUTPUT_HIGH_RIGHT) {
            "Invalid BMW High output $output"
        }
        require(section in 0 until ALL_PASS_SECTIONS_PER_OUTPUT) { "Invalid BMW High all-pass section $section" }
        require(field in 0 until ALL_PASS_SECTION_WIDTH) { "Invalid BMW High all-pass field $field" }
        val slot = if (output == OUTPUT_HIGH_LEFT) 0 else 1
        return INDEX_HIGH_ALL_PASS + (slot * ALL_PASS_SECTIONS_PER_OUTPUT + section) * ALL_PASS_SECTION_WIDTH + field
    }

    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_ENABLED = INDEX_LOW_COMPRESSOR_ENABLED
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_THRESHOLD = INDEX_LOW_COMPRESSOR_THRESHOLD
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_RATIO = INDEX_LOW_COMPRESSOR_RATIO
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_KNEE = INDEX_LOW_COMPRESSOR_KNEE
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_ATTACK = INDEX_LOW_COMPRESSOR_ATTACK
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_RELEASE = INDEX_LOW_COMPRESSOR_RELEASE
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_MAKEUP = INDEX_LOW_COMPRESSOR_MAKEUP

    val DEFAULTS = NativeBmwDspSchema.DEFAULTS

    init {
        check(DEFAULTS.size == SIZE) { "BMW DSP defaults size=${DEFAULTS.size}, expected=$SIZE" }
    }

    fun load(context: Context): FloatArray = synchronized(storeLock) {
        val store = store(context)
        val stored = store.load()
        val values = stored ?: migrateLegacy(context, store) ?: DEFAULTS.copyOf()
        // App-level power owns the master enable. Force the native path enabled so a stale
        // historical value cannot strand the user in silence with no corresponding control.
        values[INDEX_ENABLED] = 1f
        val before = values.copyOf()
        // Every migration is marker-gated, so on an already-migrated config this pass changes
        // nothing and writes nothing. It still runs on every load rather than being cached per
        // process: the file can be replaced underneath (a whole-app backup restore, a raw save of
        // DEFAULTS), and that copy must be migrated before anything uses it.
        runMigrations(values)
        retireStageDelay(values)
        // One checked save for the whole pass, rather than one per migration. Under storeLock with
        // the read above, so a save() from another thread can't land in between and be overwritten
        // by this (older) migrated snapshot.
        if (!values.contentEquals(before)) {
            val saved = store.save(values)
            Timber.i("BMW DSP migrated stored config success=$saved")
        }
        values
    }

    // Serialises load()'s read -> migrate -> save against save(). Always taken before the store's
    // own write lock.
    private val storeLock = Any()

    /** Every one-time migration, in order. Each is gated by its own marker slot. */
    private fun runMigrations(values: FloatArray) {
        migrateIndependentOutputsIfNeeded(values)
        migrateMeasMuteStopbandIfNeeded(values)
        migrateStageDelayReclaimIfNeeded(values)
        migrateMbcIfNeeded(values)
        migrateDisableLegacyCompressorIfNeeded(values)
        migrateMasterLimiterIfNeeded(values)
        migrateCrossoverTypeIfNeeded(values)
        migrateMidUpperCrossoverIfNeeded(values)
        migrateHighBandIfNeeded(values)
        migrateHighBusLimiterIfNeeded(values)
        migrateVirtualIfNeeded(values)
        migrateMidUpperXoTypeIfNeeded(values)
    }

    /**
     * Stage alignment (141/142) no longer has a control -- the virtual centre does that job -- so
     * it is held at 0 on every load, save and restore: a leftover value would otherwise keep
     * shifting the image with nothing on screen to show or undo it. True if it changed anything.
     */
    private fun retireStageDelay(values: FloatArray): Boolean {
        if (values[INDEX_STAGE_DELAY_L] == 0f && values[INDEX_STAGE_DELAY_R] == 0f) return false
        values[INDEX_STAGE_DELAY_L] = 0f
        values[INDEX_STAGE_DELAY_R] = 0f
        return true
    }

    /**
     * Seed Mid's upper (Mid/High) lowpass slope (indices 288..290, added in the 288 -> 291
     * growth) from each Mid output's own FIELD_CROSSOVER_TYPE on configs saved before it existed.
     * Until this growth native shaped the upper lowpass with Mid's own type, so copying it keeps
     * every existing save's Mid band bit-identical until the user picks a separate slope. Must run
     * after [migrateCrossoverTypeIfNeeded], which can rewrite that type. Runs once; the marker
     * then stops it so a deliberate later choice is respected.
     */
    private fun migrateMidUpperXoTypeIfNeeded(values: FloatArray) {
        if (values[INDEX_MID_UPPER_XO_TYPE_MIGRATED] == 1f) return
        for (output in intArrayOf(OUTPUT_MID_LEFT, OUTPUT_MID_RIGHT)) {
            values[midUpperXoTypeIndex(output)] = values[outputIndex(output, FIELD_CROSSOVER_TYPE)]
        }
        values[INDEX_MID_UPPER_XO_TYPE_MIGRATED] = 1f
        Timber.i("BMW DSP seeded Mid upper slope from Mid type")
    }

    /**
     * Seed the virtual-source stage off (indices 266..287, added in the 266 -> 288 growth) on
     * configs saved before it existed, so nobody's sound changes until they turn the virtual
     * centre on. The index-keyed [NativeBmwDspStore] already backfills a missing index from
     * [DEFAULTS] -- which ships it disabled -- so this mostly claims the marker at index 287,
     * mirroring [migrateMbcIfNeeded]. Runs once; the marker then stops it so a deliberate later
     * enable is respected.
     */
    private fun migrateVirtualIfNeeded(values: FloatArray) {
        if (values[INDEX_VIRTUAL_MIGRATED] == 1f) return
        values[INDEX_VIRTUAL_ENABLED] = 0f
        values[INDEX_VIRTUAL_MIGRATED] = 1f
        Timber.i("BMW DSP seeded virtual stage off")
    }

    /**
     * Seed the High-bus limiter disabled (indices 262..265, added in the 262 -> 266 growth) on
     * configs saved before it existed. The index-keyed [NativeBmwDspStore] already backfills a
     * missing index from [DEFAULTS] on load -- DEFAULTS already ships it disabled -- so this
     * mostly just claims the marker at index 265, mirroring [migrateMbcIfNeeded]: it stays OFF
     * for existing users even if a later build ever ships a different default. Runs once; the
     * marker then stops it so a deliberate later enable is respected.
     */
    private fun migrateHighBusLimiterIfNeeded(values: FloatArray) {
        if (values[INDEX_BUS_LIMITER_HIGH_MIGRATED] == 1f) return
        values[INDEX_BUS_LIMITER_HIGH_ENABLED] = 0f
        values[INDEX_BUS_LIMITER_HIGH_MIGRATED] = 1f
        Timber.i("BMW DSP seeded High-bus limiter disabled")
    }

    /**
     * Seed the whole High band (indices 210..261, added in the 210 -> 262 growth) silent on
     * configs saved before it existed. Sets both `highXoPass` (true fully silences High --
     * unlike `INDEX_LPF_PASS`/`INDEX_HPF_PASS`, which only bypass the crossover filter) and the
     * per-output mute field, so the band stays silent even if some future caller sets only one
     * of the two. Same conservative opt-in launch as [migrateMbcIfNeeded]. The index-keyed
     * [NativeBmwDspStore] already backfills a missing index from [DEFAULTS] on load -- DEFAULTS
     * already ships this muted -- so this mostly just claims the marker at index 261,
     * guaranteeing the band stays off for existing users even if a later build ever ships a
     * different default. Runs once; the marker then stops it so a deliberate later enable is
     * respected.
     */
    private fun migrateHighBandIfNeeded(values: FloatArray) {
        if (values[INDEX_HIGH_BAND_MIGRATED] == 1f) return
        values[INDEX_HIGH_XO_PASS] = 1f
        values[highOutputIndex(OUTPUT_HIGH_LEFT, FIELD_MUTE)] = 1f
        values[highOutputIndex(OUTPUT_HIGH_RIGHT, FIELD_MUTE)] = 1f
        values[INDEX_HIGH_BAND_MIGRATED] = 1f
        Timber.i("BMW DSP seeded High band muted")
    }

    /**
     * Seed Mid's upper (Mid/High) bandpass corner disabled (indices 205..209, added in the
     * 205 -> 210 growth) on configs saved before it existed. The index-keyed [NativeBmwDspStore]
     * already backfills a missing index from [DEFAULTS] on load -- DEFAULTS already ships this
     * disabled -- so this mostly just claims the marker at index 209, mirroring
     * [migrateMbcIfNeeded]: it stays OFF for existing users even if a later build ever ships a
     * different default. Runs once; the marker then stops it so a deliberate later enable is
     * respected.
     */
    private fun migrateMidUpperCrossoverIfNeeded(values: FloatArray) {
        if (values[INDEX_MID_UPPER_XO_MIGRATED] == 1f) return
        values[midUpperXoIndex(OUTPUT_MID_LEFT, MID_UPPER_XO_FIELD_ENABLED)] = 0f
        values[midUpperXoIndex(OUTPUT_MID_RIGHT, MID_UPPER_XO_FIELD_ENABLED)] = 0f
        values[INDEX_MID_UPPER_XO_MIGRATED] = 1f
        Timber.i("BMW DSP seeded Mid upper crossover corner disabled")
    }

    /**
     * Seed all four outputs' FIELD_CROSSOVER_TYPE slot to LR4 on configs saved before slope
     * selection existed. That slot (per-output index 1) was always present but never read
     * natively -- configure() forced LR4 regardless of its stored value -- so an existing save's
     * actual bytes there are meaningless leftovers (mixed 0/1 across outputs, see DEFAULTS'
     * pre-migration history), not a real prior choice. Reading them for real without this
     * migration would silently change some users' crossover topology to BW2/BW3 on update. Runs
     * once; the reclaimed-from-Mono-Bass 42 marker then stops it so a deliberate later choice of
     * BW2/BW3 is respected. The marker uses 2f rather than 1f: slot 42 was the legacy Mono Bass
     * enable boolean, so an existing user who had that on already has a stored 1f there, and
     * comparing against 1f would misread that as "already migrated" and skip the reseed.
     */
    private fun migrateCrossoverTypeIfNeeded(values: FloatArray) {
        if (values[INDEX_CROSSOVER_TYPE_MIGRATED] == CROSSOVER_TYPE_MIGRATED_MARKER) return
        listOf(OUTPUT_LOW_LEFT, OUTPUT_LOW_RIGHT, OUTPUT_MID_LEFT, OUTPUT_MID_RIGHT).forEach { output ->
            values[outputIndex(output, FIELD_CROSSOVER_TYPE)] = CROSSOVER_TYPE_LR4
        }
        values[INDEX_CROSSOVER_TYPE_MIGRATED] = CROSSOVER_TYPE_MIGRATED_MARKER
        Timber.i("BMW DSP seeded crossover type (LR4) for pre-selectable-slope installs")
    }

    /**
     * Seed the master-limiter enable + threshold (indices 189/190, reclaimed from the reserved
     * run) on configs saved before the control existed. Their slot 189 holds a leftover 0, which
     * reads as "limiter bypassed" -- silently removing the -1 dBFS safety ceiling every one of
     * those users has always had. Runs once; the 191 marker then stops it so a deliberate off is
     * respected.
     */
    private fun migrateMasterLimiterIfNeeded(values: FloatArray) {
        if (values[INDEX_MASTER_LIMITER_MIGRATED] == 1f) return
        values[INDEX_MASTER_LIMITER_ENABLED] = 1f
        values[INDEX_MASTER_LIMITER_THRESHOLD] = DEFAULT_MASTER_LIMITER_THRESHOLD_DB
        values[INDEX_MASTER_LIMITER_MIGRATED] = 1f
        Timber.i("BMW DSP seeded master limiter enable/threshold default")
    }

    /** Persists [values], zeroing the retired stage delay in place first (see [retireStageDelay]). */
    fun save(context: Context, values: FloatArray): Boolean {
        require(values.size == SIZE) { "Expected $SIZE BMW DSP values, got ${values.size}" }
        retireStageDelay(values)
        return synchronized(storeLock) { store(context).save(values) }
    }

    /** Oldest [nativeDspValues] array length a [PrivatePeqBackup] restore will still accept. */
    const val MIN_RESTORABLE_SIZE = 139

    /**
     * Pad a position-encoded config array from an older/shorter schema up to [SIZE], filling the
     * new trailing indices from [DEFAULTS]. A no-op when [values] is already [SIZE] long. Used by
     * the private-backup restore path; the array is dense and position-encoded, so a straight
     * tail copy from [DEFAULTS] is the correct fill.
     */
    fun padToCurrentSize(values: FloatArray): FloatArray {
        if (values.size >= SIZE) return values
        return DEFAULTS.copyOf().also { padded -> values.copyInto(padded, endIndex = values.size) }
    }

    /**
     * [padToCurrentSize] plus every load()-time reclaimed-slot migration, run in the same order
     * [load] itself uses. Restoring an old backup does not go through [load] -- the array comes
     * from the backup file, not disk -- so without this, a backup whose bytes predate a reclaim
     * (e.g. the short-lived Mid-band LPF that once lived at 141/142, now the stage-centering
     * delay) gets its leftover value silently reinterpreted as whatever that slot means today and
     * applied straight to the running DSP. Use this instead of calling [padToCurrentSize] alone
     * on any array that did not come from [load] (backup restore, imported config, etc.); still
     * call [save] afterward to persist the final result, same as any other array this returns.
     */
    @Suppress("UNUSED_PARAMETER") // Keep the existing import/restore call contract.
    fun migrateRestoredValues(context: Context, values: FloatArray): FloatArray {
        val padded = padToCurrentSize(values).copyOf()
        // Preparing a backup must not persist intermediate migrations (none of them saves). The
        // restore caller performs one checked save after preparation succeeds.
        padded[INDEX_ENABLED] = 1f
        runMigrations(padded)
        retireStageDelay(padded)
        return padded
    }

    private fun store(context: Context) = NativeBmwDspStore(context.noBackupFilesDir)

    private fun migrateIndependentOutputsIfNeeded(values: FloatArray) {
        if (values[INDEX_OUTPUT_SCHEMA_VERSION] >= OUTPUT_SCHEMA_VERSION) return

        fun copyCompressor(output: Int, legacyBase: Int) {
            values[outputIndex(output, FIELD_COMPRESSOR_ENABLED)] = values[legacyBase]
            values[outputIndex(output, FIELD_COMPRESSOR_THRESHOLD)] = values[legacyBase + 1]
            values[outputIndex(output, FIELD_COMPRESSOR_RATIO)] = values[legacyBase + 2]
            values[outputIndex(output, FIELD_COMPRESSOR_KNEE)] = values[legacyBase + 3]
            values[outputIndex(output, FIELD_COMPRESSOR_ATTACK)] = values[legacyBase + 4]
            values[outputIndex(output, FIELD_COMPRESSOR_RELEASE)] = values[legacyBase + 5]
            values[outputIndex(output, FIELD_COMPRESSOR_MAKEUP)] = values[legacyBase + 6]
        }

        // FIELD_CROSSOVER_TYPE here just carries over the legacy 2-value bool's raw bytes
        // (0/1, meaning BW3/LR4 under the pre-removal encoding) -- not correct under the new
        // 3-value encoding (0/1/2 = BW2/BW3/LR4), but harmless: this whole path only runs for
        // installs old enough to predate the independent-output block, and
        // migrateCrossoverTypeIfNeeded() unconditionally forces LR4 afterward for anyone not yet
        // past that marker, which every such install is.
        listOf(OUTPUT_LOW_LEFT, OUTPUT_LOW_RIGHT).forEach { output ->
            values[outputIndex(output, FIELD_CROSSOVER_FREQ)] = values[INDEX_LOW_CROSSOVER_FREQ]
            values[outputIndex(output, FIELD_CROSSOVER_TYPE)] = values[INDEX_LOW_LR4]
            values[outputIndex(output, FIELD_SUBSONIC_ENABLED)] = values[INDEX_SUBSONIC_ENABLED]
            values[outputIndex(output, FIELD_SUBSONIC_FREQ)] = values[INDEX_SUBSONIC_FREQ]
            values[outputIndex(output, FIELD_MUTE)] = values[INDEX_LOW_MUTE]
            values[outputIndex(output, FIELD_INVERT)] = values[INDEX_LOW_INVERT]
            copyCompressor(output, INDEX_LOW_COMPRESSOR_ENABLED)
        }
        listOf(OUTPUT_MID_LEFT, OUTPUT_MID_RIGHT).forEach { output ->
            values[outputIndex(output, FIELD_CROSSOVER_FREQ)] = values[INDEX_MID_CROSSOVER_FREQ]
            values[outputIndex(output, FIELD_CROSSOVER_TYPE)] = 1f
            values[outputIndex(output, FIELD_SUBSONIC_ENABLED)] = 0f
            values[outputIndex(output, FIELD_SUBSONIC_FREQ)] = values[INDEX_SUBSONIC_FREQ]
            values[outputIndex(output, FIELD_MUTE)] = values[INDEX_MID_MUTE]
            values[outputIndex(output, FIELD_INVERT)] = values[INDEX_MID_INVERT]
            copyCompressor(output, INDEX_MID_COMPRESSOR_ENABLED)
        }

        values[INDEX_OUTPUT_SCHEMA_VERSION] = OUTPUT_SCHEMA_VERSION
        Timber.i("BMW DSP migrated independent four-output config")
    }

    /**
     * Seed the measurement-mute stopband offset (index 139, reclaimed from the removed Pultec
     * stage) on configs saved before the control existed -- their slot 139 holds a leftover 0,
     * which would silently mean "corner exactly on the crossover". Runs once, then the marker at
     * index 140 (>= 1) stops it so a later deliberate 0 is respected.
     */
    private fun migrateMeasMuteStopbandIfNeeded(values: FloatArray) {
        if (values[INDEX_MEAS_MUTE_STOPBAND_MIGRATED] >= 1f) return
        values[INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES] = DEFAULT_MEAS_MUTE_STOPBAND_OCTAVES
        values[INDEX_MEAS_MUTE_STOPBAND_MIGRATED] = 1f
        Timber.i("BMW DSP seeded meas-mute stopband offset default")
    }

    /**
     * Zero slots 141/142 once, when reclaiming them for the stage-centering L/R alignment delay.
     * A config saved while the short-lived Mid-band LPF feature was live holds its enable flag /
     * corner frequency there; read as ms of stage delay, a leftover corner (e.g. 2000) would
     * clamp to the full [STAGE_DELAY_MAX_MS]. Advances the shared 139..142 marker at index 140 to
     * 2; runs after [migrateMeasMuteStopbandIfNeeded] so the marker climbs 0 -> 1 -> 2.
     */
    private fun migrateStageDelayReclaimIfNeeded(values: FloatArray) {
        if (values[INDEX_MEAS_MUTE_STOPBAND_MIGRATED] >= 2f) return
        values[INDEX_STAGE_DELAY_L] = 0f
        values[INDEX_STAGE_DELAY_R] = 0f
        values[INDEX_MEAS_MUTE_STOPBAND_MIGRATED] = 2f
        Timber.i("BMW DSP zeroed reclaimed slots 141/142 for stage delay")
    }

    /**
     * Seed the multiband-compressor + per-bus-limiter block (indices 144..191, added in the
     * 144 -> 192 growth) on configs saved before it existed. The index-keyed [NativeBmwDspStore]
     * already backfills missing indices from [DEFAULTS] on load, so this mostly just claims the
     * marker at index 181 -- but it also (re)seeds the whole block from [DEFAULTS] and
     * force-clears every MBC/limiter enable, so the feature stays OFF for existing users even if
     * a future build ships it enabled by default. Runs once, then the marker stops it. Mirrors
     * [migrateMeasMuteStopbandIfNeeded].
     */
    private fun migrateMbcIfNeeded(values: FloatArray) {
        if (values[INDEX_MBC_MIGRATED] == 1f) return
        DEFAULTS.copyInto(values, INDEX_MBC_ENABLED, INDEX_MBC_ENABLED, SIZE)
        values[INDEX_MBC_ENABLED] = 0f
        values[INDEX_BUS_LIMITER_LOW_ENABLED] = 0f
        values[INDEX_BUS_LIMITER_MID_ENABLED] = 0f
        values[INDEX_MBC_MIGRATED] = 1f
        Timber.i("BMW DSP seeded multiband compressor + bus limiter block disabled")
    }

    /**
     * Force the legacy per-output compressor off, once. It is superseded by the multiband
     * compressor + per-bus limiters and its editing screens were retired, so an existing
     * config that still has the Low bus compressor enabled (the historical default) would keep
     * a hidden, now-uncontrollable dynamics stage running. Clears both the legacy scalar enable
     * slots and every per-output FIELD_COMPRESSOR_ENABLED, then sets the marker at index 188.
     * Mirrors [migrateMeasMuteStopbandIfNeeded].
     */
    private fun migrateDisableLegacyCompressorIfNeeded(values: FloatArray) {
        if (values[INDEX_LEGACY_COMP_DISABLED_MIGRATED] == 1f) return
        values[INDEX_LOW_COMPRESSOR_ENABLED] = 0f
        values[INDEX_MID_COMPRESSOR_ENABLED] = 0f
        for (output in 0 until OUTPUT_COUNT) {
            values[outputIndex(output, FIELD_COMPRESSOR_ENABLED)] = 0f
        }
        values[INDEX_LEGACY_COMP_DISABLED_MIGRATED] = 1f
        Timber.i("BMW DSP force-disabled legacy per-output compressor")
    }

    /** One-time pickup of the pre-[NativeBmwDspStore] SharedPreferences blob. */
    private fun migrateLegacy(context: Context, store: NativeBmwDspStore): FloatArray? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY, null) ?: return null
        val parsed = saved.split(',').mapNotNull(String::toFloatOrNull).toFloatArray()
        val values = DEFAULTS.copyOf()
        parsed.copyInto(values, endIndex = minOf(parsed.size, values.size))
        val migrated = store.save(values)
        Timber.i("BMW DSP migrated legacy SharedPreferences blob size=${parsed.size} success=$migrated")
        if (migrated) prefs.edit { remove(KEY) }
        return values
    }

    /** Same-side unity routing, zero crossfeed. */
    fun resetRoutingToDefaults(values: FloatArray) {
        DEFAULTS.copyInto(values, INDEX_ROUTING, INDEX_ROUTING, INDEX_ROUTING + ROUTING_VALUE_COUNT)
    }

    /** [origin], when given, tags the broadcast so its sender can recognise its own echo. */
    fun broadcast(context: Context, values: FloatArray, origin: String? = null) {
        val intent = Intent(Constants.ACTION_NATIVE_BMW_DSP_UPDATED)
            .putExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES, values)
        if (origin != null) intent.putExtra(Constants.EXTRA_NATIVE_BMW_DSP_ORIGIN, origin)
        context.sendLocalBroadcast(intent)
    }
}
