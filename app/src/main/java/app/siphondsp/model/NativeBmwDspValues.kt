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
    const val SIZE = 46

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

    // Frequency-dependent mono/stereo blend for the low (door woofer) band -- see
    // NativeBmwDspProcessor::rebuildMonoBass()/processFrame() for the native side.
    const val INDEX_MONO_BASS_ENABLED = 42
    const val INDEX_MONO_BASS_FREQ = 43
    const val INDEX_MONO_BASS_BLEND = 44
    const val INDEX_MONO_BASS_MAKEUP = 45

    @Deprecated("Use INDEX_LOW_COMPRESSOR_ENABLED") const val INDEX_COMPRESSOR_ENABLED = INDEX_LOW_COMPRESSOR_ENABLED
    @Deprecated("Use INDEX_LOW_COMPRESSOR_THRESHOLD") const val INDEX_COMPRESSOR_THRESHOLD = INDEX_LOW_COMPRESSOR_THRESHOLD
    @Deprecated("Use INDEX_LOW_COMPRESSOR_RATIO") const val INDEX_COMPRESSOR_RATIO = INDEX_LOW_COMPRESSOR_RATIO
    @Deprecated("Use INDEX_LOW_COMPRESSOR_KNEE") const val INDEX_COMPRESSOR_KNEE = INDEX_LOW_COMPRESSOR_KNEE
    @Deprecated("Use INDEX_LOW_COMPRESSOR_ATTACK") const val INDEX_COMPRESSOR_ATTACK = INDEX_LOW_COMPRESSOR_ATTACK
    @Deprecated("Use INDEX_LOW_COMPRESSOR_RELEASE") const val INDEX_COMPRESSOR_RELEASE = INDEX_LOW_COMPRESSOR_RELEASE
    @Deprecated("Use INDEX_LOW_COMPRESSOR_MAKEUP") const val INDEX_COMPRESSOR_MAKEUP = INDEX_LOW_COMPRESSOR_MAKEUP

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
        0f, 80f, 100f, 0f,
    )

    fun load(context: Context): FloatArray {
        val store = store(context)
        val values = store.load() ?: migrateLegacy(context, store) ?: DEFAULTS.copyOf()
        // The master enable switch was removed from the UI (redundant with the app-level
        // on/off, which already achieves the same thing) - force it on so anyone who had
        // it persisted off from before can't end up silently stuck with no way to re-enable it.
        values[INDEX_ENABLED] = 1f
        return values
    }

    fun save(context: Context, values: FloatArray) {
        require(values.size == SIZE) { "Expected $SIZE BMW DSP values, got ${values.size}" }
        store(context).save(values)
    }

    private fun store(context: Context) = NativeBmwDspStore(context.noBackupFilesDir)

    /** One-time pickup of the pre-[NativeBmwDspStore] SharedPreferences blob, tolerant of any
     *  saved length (not just the old hardcoded 35->42 case) since it pads/truncates against
     *  [DEFAULTS] by position instead of rejecting the whole array on a size mismatch. */
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
