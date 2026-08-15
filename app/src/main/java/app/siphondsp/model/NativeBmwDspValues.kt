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
    // 87..138 are the independent per-output settings described below.
    const val SIZE = 139

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

    // Indices 42-45 were Mono Bass (removed -- it duplicated the low crossover's own frequency-
    // dependent blend with no real use case). Left unread/reserved so nothing after them shifts;
    // do not reuse without checking existing persisted state on installs that still have a
    // pre-removal save file.

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
        0f, 125f,
        0f, 0f,
        0f, 0f, 0f, 0f,
        1f, 3f, 550f,
        1f, -12f, 2f, 8f, 40f, 250f, 1.5f,
        0f, -10f, 1.5f, 6f, 10f, 180f, 0f,
        0f, 80f, 100f, 0f, // indices 42-45: reserved, formerly Mono Bass -- never read.
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
        125f, 1f, 0f, 32f, 0f, 0f, 0f, -10f, 1.5f, 6f, 10f, 180f, 0f,
        // Mid Right.
        125f, 1f, 0f, 32f, 0f, 0f, 0f, -10f, 1.5f, 6f, 10f, 180f, 0f,
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
        return values
    }

    fun save(context: Context, values: FloatArray) {
        require(values.size == SIZE) { "Expected $SIZE BMW DSP values, got ${values.size}" }
        store(context).save(values)
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
