package app.siphondsp.model

import android.content.Context
import android.content.Intent
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber

object NativeBmwDspValues {
    // No longer written to -- kept so load() can migrate anyone still on the old
    // SharedPreferences blob (pre-NativeBmwDspStore) onto the new atomic file store.
    const val PREFS = "native_bmw_dsp"
    const val KEY = "values"

    // 0..85 are the original/native four-output config. 86 is a schema marker and
    // 87..138 are the independent per-output settings described below. 139..142 were the
    // Pultec-style bass boost/cut stage (global, post-routing); the feature was unused and has
    // been removed. 139 and 140 have since been reclaimed in place (the trailing slots stay put
    // rather than being renumbered, same as OUTPUT_CONFIG_WIDTH's FIELD_CROSSOVER_LR4):
    //   139 -> INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES (read natively)
    //   140 -> INDEX_MEAS_MUTE_STOPBAND_MIGRATED, a one-time migration marker (Kotlin-only)
    //   141..142 -> still inert.
    // 143 is the Gains & Delay "Link L/R Delay" toggle -- UI-only, never read natively (see
    // INDEX_DELAY_LINKED), stored here anyway so it persists/syncs the same way every other
    // toggle in this array does.
    //
    // 144..191 are the pre-crossover multiband compressor (MBC) + per-bus output limiter block,
    // added in the 144 -> 192 growth. The store keys entries by index, so an older 144-value
    // save just leaves 144..191 at their DEFAULTS; migrateMbcIfNeeded() claims the marker and
    // force-clears the enables. See the INDEX_MBC_* / INDEX_BUS_LIMITER_* section lower down.
    const val SIZE = 192

    const val INDEX_ENABLED = 0
    const val INDEX_LPF_PASS = 1
    const val INDEX_HPF_PASS = 2
    const val INDEX_CHANNEL_MUTE = 3
    const val INDEX_MEASUREMENT_MUTE = 4
    const val INDEX_HEADROOM = 5
    const val INDEX_LOW_GAIN_L = 6
    const val INDEX_LOW_GAIN_R = 7
    const val INDEX_MID_GAIN_L = 8
    const val INDEX_MID_GAIN_R = 9
    const val INDEX_POST_GAIN_L = 10
    const val INDEX_POST_GAIN_R = 11

    // Legacy shared-band settings retained for migration/backward compatibility. The native
    // engine now consumes the independent per-output block at INDEX_OUTPUT_CONFIG.
    const val INDEX_SUBSONIC_ENABLED = 12
    const val INDEX_SUBSONIC_FREQ = 13
    const val INDEX_LOW_MUTE = 14
    const val INDEX_LOW_CROSSOVER_FREQ = 15
    const val INDEX_LOW_LR4 = 16
    const val INDEX_MID_MUTE = 17
    const val INDEX_MID_CROSSOVER_FREQ = 18
    const val INDEX_LOW_INVERT = 19
    const val INDEX_MID_INVERT = 20

    const val INDEX_MID_DELAY_L = 21
    const val INDEX_MID_DELAY_R = 22
    const val INDEX_LOW_DELAY_L = 23
    const val INDEX_LOW_DELAY_R = 24
    const val INDEX_TILT_ENABLED = 25
    const val INDEX_TILT_AMOUNT = 26
    const val INDEX_TILT_FREQ = 27

    // Legacy band-linked compressor settings, used as migration seeds.
    const val INDEX_LOW_COMPRESSOR_ENABLED = 28
    const val INDEX_LOW_COMPRESSOR_THRESHOLD = 29
    const val INDEX_LOW_COMPRESSOR_RATIO = 30
    const val INDEX_LOW_COMPRESSOR_KNEE = 31
    const val INDEX_LOW_COMPRESSOR_ATTACK = 32
    const val INDEX_LOW_COMPRESSOR_RELEASE = 33
    const val INDEX_LOW_COMPRESSOR_MAKEUP = 34

    const val INDEX_MID_COMPRESSOR_ENABLED = 35
    const val INDEX_MID_COMPRESSOR_THRESHOLD = 36
    const val INDEX_MID_COMPRESSOR_RATIO = 37
    const val INDEX_MID_COMPRESSOR_KNEE = 38
    const val INDEX_MID_COMPRESSOR_ATTACK = 39
    const val INDEX_MID_COMPRESSOR_RELEASE = 40
    const val INDEX_MID_COMPRESSOR_MAKEUP = 41

    // Frequency-dependent mono/stereo blend for the low paths. This is deliberately the only
    // stage that can couple Low L and Low R; when disabled the four processing paths remain
    // independent until the final stereo reconstruction.
    const val INDEX_MONO_BASS_ENABLED = 42
    const val INDEX_MONO_BASS_FREQ = 43
    const val INDEX_MONO_BASS_BLEND = 44
    const val INDEX_MONO_BASS_MAKEUP = 45

    // Advanced stereo routing matrix: four logical outputs x Front L/Front R source.
    const val INDEX_ROUTING = 46
    const val ROUTING_VALUE_COUNT = 8
    const val INDEX_ROUTE_LOW_LEFT_FRONT_LEFT = 46
    const val INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT = 47
    const val INDEX_ROUTE_LOW_RIGHT_FRONT_LEFT = 48
    const val INDEX_ROUTE_LOW_RIGHT_FRONT_RIGHT = 49
    const val INDEX_ROUTE_MID_LEFT_FRONT_LEFT = 50
    const val INDEX_ROUTE_MID_LEFT_FRONT_RIGHT = 51
    const val INDEX_ROUTE_MID_RIGHT_FRONT_LEFT = 52
    const val INDEX_ROUTE_MID_RIGHT_FRONT_RIGHT = 53

    // Two all-pass sections per exact output: [enabled, order(1|2), frequencyHz, Q].
    const val INDEX_ALL_PASS = 54
    const val ALL_PASS_SECTIONS_PER_OUTPUT = 2
    const val ALL_PASS_SECTION_WIDTH = 4
    const val ALL_PASS_VALUE_COUNT = 32

    // Versioned independent-output block. Output order matches NativeBmwRouting::OutputId.
    const val INDEX_OUTPUT_SCHEMA_VERSION = 86
    const val OUTPUT_SCHEMA_VERSION = 1f
    const val INDEX_OUTPUT_CONFIG = 87
    const val OUTPUT_CONFIG_WIDTH = 13
    const val OUTPUT_COUNT = 4

    const val OUTPUT_LOW_LEFT = 0
    const val OUTPUT_LOW_RIGHT = 1
    const val OUTPUT_MID_LEFT = 2
    const val OUTPUT_MID_RIGHT = 3

    const val FIELD_CROSSOVER_FREQ = 0
    const val FIELD_CROSSOVER_LR4 = 1
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
    // (unused feature, no native processing left); 139/140 reclaimed below, 141/142 still inert.

    // Measurement-mute bus brick-wall: octaves to walk the LR8 corner off the opposite band's
    // crossover and into the stopband, so the band still playing keeps its own transition region
    // for measurement. 0 == original on-crossover behaviour. Consumed natively (v[139]).
    const val INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES = 139
    const val DEFAULT_MEAS_MUTE_STOPBAND_OCTAVES = 1f

    // One-time marker: set to 1 once an existing saved config has had slot 139 seeded to the
    // default above. Lets the new control ship with a non-zero default without a full schema
    // version bump. Kotlin-only -- native never reads index 140.
    const val INDEX_MEAS_MUTE_STOPBAND_MIGRATED = 140

    // UI-only: links Low Left/Right and Mid Left/Right delay editing together on the Gains &
    // Delay page. Native never reads this index -- see NativeBmwDspProcessor::configure(), which
    // only consumes 0..142.
    const val INDEX_DELAY_LINKED = 143

    // ---------------------------------------------------------------------------------------
    // Pre-crossover multiband compressor (MBC) -- indices 144..181. New in the 144 -> 192
    // schema growth. Operates on the full-range stereo bus AFTER headroom and BEFORE the
    // routing matrix / band crossovers (see NativeBmwDspProcessor::processFrame). Four bands
    // split by three Linkwitz-Riley 24 dB/oct crossovers and reconstructed flat via all-pass
    // compensation on the already-split lower bands -- same principle as the mono-bass
    // all-pass fix. Ships DISABLED; the metering JNI and the UI land in later PRs.
    const val INDEX_MBC_ENABLED = 144
    const val INDEX_MBC_MIX = 145 // dry/wet blend, percent 0..100 (100 = fully processed)
    const val INDEX_MBC_XO_0 = 146 // band 0 | band 1 split, Hz
    const val INDEX_MBC_XO_1 = 147 // band 1 | band 2 split, Hz
    const val INDEX_MBC_XO_2 = 148 // band 2 | band 3 split, Hz

    const val INDEX_MBC_BANDS = 149
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
    const val INDEX_MBC_MIGRATED = 181

    // Per-bus output limiter (Low bus, Mid bus) -- indices 182..187. A brand-new native stage,
    // distinct from and additive to the legacy per-output processCompressor path, which is left
    // untouched. Ships DISABLED; the "Driver protection" UI card wires it up in a later PR.
    // threshold in dBFS, release in ms; native uses a fixed-fast attack and an infinite
    // (brick-wall) ratio.
    const val INDEX_BUS_LIMITER_LOW_ENABLED = 182
    const val INDEX_BUS_LIMITER_LOW_THRESHOLD = 183
    const val INDEX_BUS_LIMITER_LOW_RELEASE = 184
    const val INDEX_BUS_LIMITER_MID_ENABLED = 185
    const val INDEX_BUS_LIMITER_MID_THRESHOLD = 186
    const val INDEX_BUS_LIMITER_MID_RELEASE = 187

    // One-time marker: 1 once the legacy per-output compressor (INDEX_LOW/MID_COMPRESSOR_* and
    // the per-output FIELD_COMPRESSOR_ENABLED slots, still run by NativeBmwDspProcessor's
    // processCompressor) has been force-disabled. The multiband compressor + per-bus limiters
    // replace it, and its editing UI was retired, so leaving it enabled-by-default would be a
    // hidden, uncontrollable dynamics stage. Kotlin-only -- native still reads the enable slots
    // themselves, this marker just gates the one-time flip. See migrateDisableLegacyCompressorIfNeeded.
    const val INDEX_LEGACY_COMP_DISABLED_MIGRATED = 188

    // Master brick-wall limiter on the summed output (indices 189..191). Was a fixed -1 dBFS
    // ceiling with no controls; now enable + threshold. enable == 0 is a TRUE bypass -- nothing
    // constrains the output. Native reads 189/190; 191 is the one-time seed marker.
    const val INDEX_MASTER_LIMITER_ENABLED = 189
    const val INDEX_MASTER_LIMITER_THRESHOLD = 190
    const val INDEX_MASTER_LIMITER_MIGRATED = 191
    const val DEFAULT_MASTER_LIMITER_THRESHOLD_DB = -1f

    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_ENABLED = INDEX_LOW_COMPRESSOR_ENABLED
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_THRESHOLD = INDEX_LOW_COMPRESSOR_THRESHOLD
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_RATIO = INDEX_LOW_COMPRESSOR_RATIO
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_KNEE = INDEX_LOW_COMPRESSOR_KNEE
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_ATTACK = INDEX_LOW_COMPRESSOR_ATTACK
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_RELEASE = INDEX_LOW_COMPRESSOR_RELEASE
    @Deprecated("Use per-output compressor indices") const val INDEX_COMPRESSOR_MAKEUP = INDEX_LOW_COMPRESSOR_MAKEUP

    val DEFAULTS = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        -6f, 0f, 0f, -1f, -1f, 0f, 0f,
        1f, 32f,
        0f, 150f, 0f,
        0f, 150f,
        0f, 0f,
        0f, 0f, 0f, 0f,
        1f, 3f, 550f,
        1f, -12f, 2f, 8f, 40f, 250f, 1.5f,
        0f, -10f, 1.5f, 6f, 10f, 180f, 0f,
        0f, 80f, 100f, 0f,
        // Low L, Low R, Mid L, Mid R: [Front L, Front R].
        1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f,
        // Two disabled second-order all-pass sections per output.
        0f, 2f, 150f, 0.70710677f, 0f, 2f, 150f, 0.70710677f,
        0f, 2f, 150f, 0.70710677f, 0f, 2f, 150f, 0.70710677f,
        0f, 2f, 150f, 0.70710677f, 0f, 2f, 150f, 0.70710677f,
        0f, 2f, 150f, 0.70710677f, 0f, 2f, 150f, 0.70710677f,
        // Schema marker. Zero means "seed the independent output block from legacy settings".
        0f,
        // Low Left: XO Hz, LR4, sub on/Hz, mute, invert, compressor 7-tuple.
        150f, 0f, 1f, 32f, 0f, 0f, 1f, -12f, 2f, 8f, 40f, 250f, 1.5f,
        // Low Right.
        150f, 0f, 1f, 32f, 0f, 0f, 1f, -12f, 2f, 8f, 40f, 250f, 1.5f,
        // Mid Left: subsonic fields are retained for a uniform block but ignored by native.
        // XO Hz matches Low (both hand off at 150) -- the old 125 default left a 25 Hz gap.
        150f, 1f, 0f, 32f, 0f, 0f, 0f, -10f, 1.5f, 6f, 10f, 180f, 0f,
        // Mid Right.
        150f, 1f, 0f, 32f, 0f, 0f, 0f, -10f, 1.5f, 6f, 10f, 180f, 0f,
        // 139: meas-mute stopband octaves. 140: migration marker (1 = seeded). 141..142 inert
        // (formerly Pultec boost/cut dB).
        1f, 1f, 0f, 0f,
        // Link L/R Delay (UI-only).
        0f,
        // --- Multiband compressor (144..181), ships DISABLED ---
        0f, // 144 enabled
        100f, // 145 dry/wet mix %
        120f, 500f, 4000f, // 146..148 crossover splits: sub|warmth|body|air
        // Per band: enabled, threshold dBFS, ratio, knee dB, attack ms, release ms, makeup dB, stereoLink.
        0f, -24f, 2f, 6f, 15f, 150f, 0f, 1f, // 149..156 band 0 -- sub / boom (< 120 Hz)
        0f, -20f, 2f, 6f, 20f, 180f, 0f, 1f, // 157..164 band 1 -- warmth / boxiness (120..500)
        0f, -18f, 2f, 6f, 15f, 150f, 0f, 1f, // 165..172 band 2 -- body / honk (500..4k)
        0f, -24f, 2f, 6f, 5f, 80f, 0f, 1f, // 173..180 band 3 -- presence / air (> 4k)
        0f, // 181 MBC/limiter migration marker (0 = seed the block on next load)
        // --- Per-bus output limiter (182..187), ships DISABLED ---
        0f, -3f, 120f, // 182..184 Low bus: enabled, threshold dBFS, release ms
        0f, -3f, 120f, // 185..187 Mid bus: enabled, threshold dBFS, release ms
        // 188: legacy-per-output-compressor-disabled marker (0 = force it off on next load).
        // 189..191: master limiter enabled, threshold dBFS, migrated marker.
        0f, 1f, -1f, 1f,
    )

    init {
        check(DEFAULTS.size == SIZE) { "BMW DSP defaults size=${DEFAULTS.size}, expected=$SIZE" }
    }

    fun load(context: Context): FloatArray {
        val store = store(context)
        val values = store.load() ?: migrateLegacy(context, store) ?: DEFAULTS.copyOf()
        // App-level power owns the master enable. Force the native path enabled so a stale
        // historical value cannot strand the user in silence with no corresponding control.
        values[INDEX_ENABLED] = 1f
        migrateIndependentOutputsIfNeeded(store, values)
        migrateMeasMuteStopbandIfNeeded(store, values)
        migrateMbcIfNeeded(store, values)
        migrateDisableLegacyCompressorIfNeeded(store, values)
        migrateMasterLimiterIfNeeded(store, values)
        return values
    }

    /**
     * Seed the master-limiter enable + threshold (indices 189/190, reclaimed from the reserved
     * run) on configs saved before the control existed. Their slot 189 holds a leftover 0, which
     * reads as "limiter bypassed" -- silently removing the -1 dBFS safety ceiling every one of
     * those users has always had. Runs once; the 191 marker then stops it so a deliberate off is
     * respected.
     */
    private fun migrateMasterLimiterIfNeeded(store: NativeBmwDspStore, values: FloatArray) {
        if (values[INDEX_MASTER_LIMITER_MIGRATED] == 1f) return
        values[INDEX_MASTER_LIMITER_ENABLED] = 1f
        values[INDEX_MASTER_LIMITER_THRESHOLD] = DEFAULT_MASTER_LIMITER_THRESHOLD_DB
        values[INDEX_MASTER_LIMITER_MIGRATED] = 1f
        val saved = store.save(values)
        Timber.i("BMW DSP seeded master limiter enable/threshold default success=$saved")
    }

    fun save(context: Context, values: FloatArray) {
        require(values.size == SIZE) { "Expected $SIZE BMW DSP values, got ${values.size}" }
        store(context).save(values)
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

    private fun store(context: Context) = NativeBmwDspStore(context.noBackupFilesDir)

    private fun migrateIndependentOutputsIfNeeded(store: NativeBmwDspStore, values: FloatArray) {
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

        listOf(OUTPUT_LOW_LEFT, OUTPUT_LOW_RIGHT).forEach { output ->
            values[outputIndex(output, FIELD_CROSSOVER_FREQ)] = values[INDEX_LOW_CROSSOVER_FREQ]
            values[outputIndex(output, FIELD_CROSSOVER_LR4)] = values[INDEX_LOW_LR4]
            values[outputIndex(output, FIELD_SUBSONIC_ENABLED)] = values[INDEX_SUBSONIC_ENABLED]
            values[outputIndex(output, FIELD_SUBSONIC_FREQ)] = values[INDEX_SUBSONIC_FREQ]
            values[outputIndex(output, FIELD_MUTE)] = values[INDEX_LOW_MUTE]
            values[outputIndex(output, FIELD_INVERT)] = values[INDEX_LOW_INVERT]
            copyCompressor(output, INDEX_LOW_COMPRESSOR_ENABLED)
        }
        listOf(OUTPUT_MID_LEFT, OUTPUT_MID_RIGHT).forEach { output ->
            values[outputIndex(output, FIELD_CROSSOVER_FREQ)] = values[INDEX_MID_CROSSOVER_FREQ]
            values[outputIndex(output, FIELD_CROSSOVER_LR4)] = 1f
            values[outputIndex(output, FIELD_SUBSONIC_ENABLED)] = 0f
            values[outputIndex(output, FIELD_SUBSONIC_FREQ)] = values[INDEX_SUBSONIC_FREQ]
            values[outputIndex(output, FIELD_MUTE)] = values[INDEX_MID_MUTE]
            values[outputIndex(output, FIELD_INVERT)] = values[INDEX_MID_INVERT]
            copyCompressor(output, INDEX_MID_COMPRESSOR_ENABLED)
        }

        values[INDEX_OUTPUT_SCHEMA_VERSION] = OUTPUT_SCHEMA_VERSION
        val saved = store.save(values)
        Timber.i("BMW DSP migrated independent four-output config success=$saved")
    }

    /**
     * Seed the measurement-mute stopband offset (index 139, reclaimed from the removed Pultec
     * stage) on configs saved before the control existed -- their slot 139 holds a leftover 0,
     * which would silently mean "corner exactly on the crossover". Runs once, then the marker at
     * index 140 stops it so a later deliberate 0 is respected.
     */
    private fun migrateMeasMuteStopbandIfNeeded(store: NativeBmwDspStore, values: FloatArray) {
        if (values[INDEX_MEAS_MUTE_STOPBAND_MIGRATED] == 1f) return
        values[INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES] = DEFAULT_MEAS_MUTE_STOPBAND_OCTAVES
        values[INDEX_MEAS_MUTE_STOPBAND_MIGRATED] = 1f
        val saved = store.save(values)
        Timber.i("BMW DSP seeded meas-mute stopband offset default success=$saved")
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
    private fun migrateMbcIfNeeded(store: NativeBmwDspStore, values: FloatArray) {
        if (values[INDEX_MBC_MIGRATED] == 1f) return
        DEFAULTS.copyInto(values, INDEX_MBC_ENABLED, INDEX_MBC_ENABLED, SIZE)
        values[INDEX_MBC_ENABLED] = 0f
        values[INDEX_BUS_LIMITER_LOW_ENABLED] = 0f
        values[INDEX_BUS_LIMITER_MID_ENABLED] = 0f
        values[INDEX_MBC_MIGRATED] = 1f
        val saved = store.save(values)
        Timber.i("BMW DSP seeded multiband compressor + bus limiter block disabled success=$saved")
    }

    /**
     * Force the legacy per-output compressor off, once. It is superseded by the multiband
     * compressor + per-bus limiters and its editing screens were retired, so an existing
     * config that still has the Low bus compressor enabled (the historical default) would keep
     * a hidden, now-uncontrollable dynamics stage running. Clears both the legacy scalar enable
     * slots and every per-output FIELD_COMPRESSOR_ENABLED, then sets the marker at index 188.
     * Mirrors [migrateMeasMuteStopbandIfNeeded].
     */
    private fun migrateDisableLegacyCompressorIfNeeded(store: NativeBmwDspStore, values: FloatArray) {
        if (values[INDEX_LEGACY_COMP_DISABLED_MIGRATED] == 1f) return
        values[INDEX_LOW_COMPRESSOR_ENABLED] = 0f
        values[INDEX_MID_COMPRESSOR_ENABLED] = 0f
        for (output in 0 until OUTPUT_COUNT) {
            values[outputIndex(output, FIELD_COMPRESSOR_ENABLED)] = 0f
        }
        values[INDEX_LEGACY_COMP_DISABLED_MIGRATED] = 1f
        val saved = store.save(values)
        Timber.i("BMW DSP force-disabled legacy per-output compressor success=$saved")
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
        if (migrated) prefs.edit().remove(KEY).apply()
        return values
    }

    /** Same-side unity routing, zero crossfeed. */
    fun resetRoutingToDefaults(values: FloatArray) {
        DEFAULTS.copyInto(values, INDEX_ROUTING, INDEX_ROUTING, INDEX_ROUTING + ROUTING_VALUE_COUNT)
    }

    fun broadcast(context: Context, values: FloatArray) {
        context.sendLocalBroadcast(
            Intent(Constants.ACTION_NATIVE_BMW_DSP_UPDATED).putExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES, values)
        )
    }

    fun update(context: Context, mutate: (FloatArray) -> Unit): FloatArray {
        val values = load(context)
        mutate(values)
        save(context, values)
        broadcast(context, values)
        return values
    }
}
