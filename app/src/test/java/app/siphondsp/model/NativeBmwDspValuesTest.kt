package app.siphondsp.model

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class NativeBmwDspValuesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val prefsByName = mutableMapOf<String, FakeSharedPreferences>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        prefsByName.clear()
        val noBackupDir = temporaryFolder.newFolder("no-backup")
        context = mock {
            on { getSharedPreferences(any(), any()) } doAnswer { invocation ->
                val name = invocation.getArgument<String>(0)
                prefsByName.getOrPut(name) { FakeSharedPreferences() }
            }
            on { noBackupFilesDir } doAnswer { noBackupDir }
        }
    }

    private fun migratedDefaults() = NativeBmwDspValues.DEFAULTS.copyOf().also { values ->
        seedIndependentOutputs(values)
        seedMbc(values)
        seedLegacyCompDisabled(values)
    }

    /** Mirrors [NativeBmwDspValues.migrateDisableLegacyCompressorIfNeeded]. */
    private fun seedLegacyCompDisabled(values: FloatArray) {
        values[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED] = 0f
        values[NativeBmwDspValues.INDEX_MID_COMPRESSOR_ENABLED] = 0f
        for (output in 0 until NativeBmwDspValues.OUTPUT_COUNT) {
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_ENABLED)] = 0f
        }
        values[NativeBmwDspValues.INDEX_LEGACY_COMP_DISABLED_MIGRATED] = 1f
    }

    /** Mirrors [NativeBmwDspValues.migrateMbcIfNeeded]: block reseeded from DEFAULTS, enables
     *  forced off, marker claimed. DEFAULTS already ships the block disabled, so on a fresh
     *  config this only flips the marker. */
    private fun seedMbc(values: FloatArray) {
        NativeBmwDspValues.DEFAULTS.copyInto(
            values, NativeBmwDspValues.INDEX_MBC_ENABLED,
            NativeBmwDspValues.INDEX_MBC_ENABLED, NativeBmwDspValues.SIZE,
        )
        values[NativeBmwDspValues.INDEX_MBC_ENABLED] = 0f
        values[NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED] = 0f
        values[NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED] = 0f
        values[NativeBmwDspValues.INDEX_MBC_MIGRATED] = 1f
    }

    private fun seedIndependentOutputs(values: FloatArray) {
        fun copyCompressor(output: Int, legacyBase: Int) {
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_ENABLED)] = values[legacyBase]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_THRESHOLD)] = values[legacyBase + 1]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_RATIO)] = values[legacyBase + 2]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_KNEE)] = values[legacyBase + 3]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_ATTACK)] = values[legacyBase + 4]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_RELEASE)] = values[legacyBase + 5]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_MAKEUP)] = values[legacyBase + 6]
        }

        listOf(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.OUTPUT_LOW_RIGHT).forEach { output ->
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = values[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_LR4)] = values[NativeBmwDspValues.INDEX_LOW_LR4]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED)] = values[NativeBmwDspValues.INDEX_SUBSONIC_ENABLED]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_SUBSONIC_FREQ)] = values[NativeBmwDspValues.INDEX_SUBSONIC_FREQ]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_MUTE)] = values[NativeBmwDspValues.INDEX_LOW_MUTE]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)] = values[NativeBmwDspValues.INDEX_LOW_INVERT]
            copyCompressor(output, NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED)
        }
        listOf(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.OUTPUT_MID_RIGHT).forEach { output ->
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = values[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_LR4)] = 1f
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED)] = 0f
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_SUBSONIC_FREQ)] = values[NativeBmwDspValues.INDEX_SUBSONIC_FREQ]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_MUTE)] = values[NativeBmwDspValues.INDEX_MID_MUTE]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)] = values[NativeBmwDspValues.INDEX_MID_INVERT]
            copyCompressor(output, NativeBmwDspValues.INDEX_MID_COMPRESSOR_ENABLED)
        }
        values[NativeBmwDspValues.INDEX_OUTPUT_SCHEMA_VERSION] = NativeBmwDspValues.OUTPUT_SCHEMA_VERSION
        values[NativeBmwDspValues.INDEX_ENABLED] = 1f
    }

    @Test
    fun loadFallsBackToMigratedDefaultsWhenNothingSaved() {
        val loaded = NativeBmwDspValues.load(context)
        assertArrayEquals(migratedDefaults(), loaded, 0f)
    }

    @Test
    fun loadMigratesLegacySharedPreferencesBlobByPosition() {
        val prefs = context.getSharedPreferences(NativeBmwDspValues.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(NativeBmwDspValues.KEY, "1,2,3").apply()

        val loaded = NativeBmwDspValues.load(context)

        val expected = NativeBmwDspValues.DEFAULTS.copyOf().also {
            it[0] = 1f; it[1] = 2f; it[2] = 3f
            seedIndependentOutputs(it)
            seedMbc(it)
            seedLegacyCompDisabled(it)
        }
        assertArrayEquals(expected, loaded, 0f)
        assertArrayEquals(expected, NativeBmwDspValues.load(context), 0f)
    }

    @Test
    fun saveLoadRoundTripsCurrentSchema() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_TILT_FREQ] = 777f
            it[NativeBmwDspValues.INDEX_HEADROOM] = -3.5f
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 143f
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 151f
        }

        NativeBmwDspValues.save(context, values)
        val loaded = NativeBmwDspValues.load(context)

        assertArrayEquals(values, loaded, 0f)
    }

    @Test
    fun migrationSeedsIndependentOutputsFromLinkedLegacySettings() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf().also {
            it[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ] = 147f
            it[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ] = 131f
            it[NativeBmwDspValues.INDEX_LOW_INVERT] = 1f
            it[NativeBmwDspValues.INDEX_MID_COMPRESSOR_THRESHOLD] = -7f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(147f, loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)], 0f)
        assertEquals(147f, loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)], 0f)
        assertEquals(131f, loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)], 0f)
        assertEquals(131f, loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)], 0f)
        assertEquals(1f, loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_INVERT)], 0f)
        assertEquals(1f, loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_INVERT)], 0f)
        assertEquals(-7f, loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_COMPRESSOR_THRESHOLD)], 0f)
        assertEquals(-7f, loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_COMPRESSOR_THRESHOLD)], 0f)
    }

    @Test
    fun resetRoutingToDefaultsRestoresIdentityRoutingWithoutTouchingOtherIndices() {
        val values = migratedDefaults()
        values[NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_LEFT] = 0.5f
        values[NativeBmwDspValues.INDEX_ROUTE_LOW_LEFT_FRONT_RIGHT] = 0.5f
        values[NativeBmwDspValues.INDEX_TILT_FREQ] = 777f

        NativeBmwDspValues.resetRoutingToDefaults(values)

        val expected = migratedDefaults().also { it[NativeBmwDspValues.INDEX_TILT_FREQ] = 777f }
        assertArrayEquals(expected, values, 0f)
    }

    @Test
    fun loadSeedsMeasMuteStopbandOffsetOnConfigsSavedBeforeTheControlExisted() {
        // A pre-control config: slot 139 still holds the leftover Pultec 0, marker at 140 unset.
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES] = 0f
            it[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(
            NativeBmwDspValues.DEFAULT_MEAS_MUTE_STOPBAND_OCTAVES,
            loaded[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES],
            0f,
        )
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED], 0f)
    }

    @Test
    fun loadLeavesADeliberateZeroStopbandOffsetAloneOnceMigrated() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES] = 0f
            it[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED] = 1f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES], 0f)
    }

    @Test
    fun loadSeedsMasterLimiterOnConfigsSavedBeforeTheControlExisted() {
        // Pre-control config: slots 189/190 hold leftover 0s, marker 191 unset. Leftover 0 at
        // 189 would read as "limiter bypassed" -- must be seeded back on.
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED] = 0f
            it[NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD] = 0f
            it[NativeBmwDspValues.INDEX_MASTER_LIMITER_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED], 0f)
        assertEquals(
            NativeBmwDspValues.DEFAULT_MASTER_LIMITER_THRESHOLD_DB,
            loaded[NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD],
            0f,
        )
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_MASTER_LIMITER_MIGRATED], 0f)
    }

    @Test
    fun loadLeavesADeliberatelyBypassedMasterLimiterAloneOnceMigrated() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED] = 0f
            it[NativeBmwDspValues.INDEX_MASTER_LIMITER_MIGRATED] = 1f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED], 0f)
    }

    @Test
    fun loadSeedsMbcBlockDisabledOnConfigsSavedBeforeItExisted() {
        // A pre-MBC config: the block sits at leftover values and the marker is unset. Even if
        // a stray "enabled" made it into the array, load() must bring the feature back OFF and
        // claim the marker.
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_MBC_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_MBC_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_MBC_ENABLED], 0f)
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED], 0f)
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED], 0f)
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_MBC_MIGRATED], 0f)
        // Crossover splits + band layout come back at their shipped defaults.
        assertEquals(120f, loaded[NativeBmwDspValues.INDEX_MBC_XO_0], 0f)
        assertEquals(500f, loaded[NativeBmwDspValues.INDEX_MBC_XO_1], 0f)
        assertEquals(4000f, loaded[NativeBmwDspValues.INDEX_MBC_XO_2], 0f)
        assertEquals(
            1f,
            loaded[NativeBmwDspValues.mbcBandIndex(0, NativeBmwDspValues.MBC_FIELD_STEREO_LINK)],
            0f,
        )
    }

    @Test
    fun loadLeavesMbcSettingsAloneOnceMigrated() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_MBC_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_MBC_MIX] = 60f
            it[NativeBmwDspValues.mbcBandIndex(2, NativeBmwDspValues.MBC_FIELD_THRESHOLD)] = -14f
            it[NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_MBC_MIGRATED] = 1f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_MBC_ENABLED], 0f)
        assertEquals(60f, loaded[NativeBmwDspValues.INDEX_MBC_MIX], 0f)
        assertEquals(
            -14f,
            loaded[NativeBmwDspValues.mbcBandIndex(2, NativeBmwDspValues.MBC_FIELD_THRESHOLD)],
            0f,
        )
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED], 0f)
    }

    @Test
    fun loadForceDisablesTheLegacyPerOutputCompressorOnce() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED] = 1f
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_COMPRESSOR_ENABLED)] = 1f
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_COMPRESSOR_ENABLED)] = 1f
            it[NativeBmwDspValues.INDEX_LEGACY_COMP_DISABLED_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED], 0f)
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_MID_COMPRESSOR_ENABLED], 0f)
        for (output in 0 until NativeBmwDspValues.OUTPUT_COUNT) {
            assertEquals(
                0f,
                loaded[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_COMPRESSOR_ENABLED)],
                0f,
            )
        }
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_LEGACY_COMP_DISABLED_MIGRATED], 0f)
    }

    @Test
    fun loadLeavesLegacyCompressorAloneOnceItsMarkerIsSet() {
        // Marker already set: a later deliberate re-enable (whatever sets it) must survive.
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_LEGACY_COMP_DISABLED_MIGRATED] = 1f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED], 0f)
    }

    @Test
    fun saveLoadRoundTripsWithMbcAndLimiterValuesSet() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_MBC_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_MBC_XO_1] = 550f
            it[NativeBmwDspValues.mbcBandIndex(1, NativeBmwDspValues.MBC_FIELD_RATIO)] = 3.5f
            it[NativeBmwDspValues.mbcBandIndex(3, NativeBmwDspValues.MBC_FIELD_STEREO_LINK)] = 0f
            it[NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_THRESHOLD] = -1.5f
        }

        NativeBmwDspValues.save(context, values)

        assertArrayEquals(values, NativeBmwDspValues.load(context), 0f)
    }

    @Test
    fun padToCurrentSizeFillsNewTrailingIndicesFromDefaults() {
        val legacy = FloatArray(144) { index -> NativeBmwDspValues.DEFAULTS[index] }
        legacy[NativeBmwDspValues.INDEX_HEADROOM] = -4.5f
        legacy[NativeBmwDspValues.INDEX_DELAY_LINKED] = 1f

        val padded = NativeBmwDspValues.padToCurrentSize(legacy)

        assertEquals(NativeBmwDspValues.SIZE, padded.size)
        assertEquals(-4.5f, padded[NativeBmwDspValues.INDEX_HEADROOM], 0f)
        assertEquals(1f, padded[NativeBmwDspValues.INDEX_DELAY_LINKED], 0f)
        assertEquals(0f, padded[NativeBmwDspValues.INDEX_MBC_ENABLED], 0f)
        assertEquals(120f, padded[NativeBmwDspValues.INDEX_MBC_XO_0], 0f)
        assertEquals(100f, padded[NativeBmwDspValues.INDEX_MBC_MIX], 0f)
    }
}
