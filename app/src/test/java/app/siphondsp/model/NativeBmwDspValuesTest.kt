package app.siphondsp.model

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
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
        seedCrossoverTypeMigrated(values)
        seedMidUpperCrossoverMigrated(values)
        seedHighBandMigrated(values)
        seedHighBusLimiterMigrated(values)
        seedVirtualMigrated(values)
        seedMidUpperXoTypeMigrated(values)
    }

    /** Mirrors [NativeBmwDspValues.migrateMidUpperXoTypeIfNeeded]: each Mid upper slope copied
     *  from that output's own type, marker claimed. Runs last, matching load()'s real call order. */
    private fun seedMidUpperXoTypeMigrated(values: FloatArray) {
        for (output in intArrayOf(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.OUTPUT_MID_RIGHT)) {
            values[NativeBmwDspValues.midUpperXoTypeIndex(output)] =
                values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)]
        }
        values[NativeBmwDspValues.INDEX_MID_UPPER_XO_TYPE_MIGRATED] = 1f
    }

    /** Mirrors [NativeBmwDspValues.migrateVirtualIfNeeded]: marker claimed. DEFAULTS already
     *  ships the virtual stage disabled, so this only flips the marker. Runs last, matching
     *  load()'s real call order. */
    private fun seedVirtualMigrated(values: FloatArray) {
        values[NativeBmwDspValues.INDEX_VIRTUAL_MIGRATED] = 1f
    }

    /** Mirrors [NativeBmwDspValues.migrateHighBusLimiterIfNeeded]: marker claimed. DEFAULTS
     *  already ships the limiter disabled, so this only flips the marker. Must run last, matching
     *  load()'s real call order. */
    private fun seedHighBusLimiterMigrated(values: FloatArray) {
        values[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_MIGRATED] = 1f
    }

    /** Mirrors [NativeBmwDspValues.migrateMidUpperCrossoverIfNeeded]: marker claimed. DEFAULTS
     *  already ships the block disabled, so this only flips the marker. Must run last, matching
     *  load()'s real call order. */
    private fun seedMidUpperCrossoverMigrated(values: FloatArray) {
        values[NativeBmwDspValues.INDEX_MID_UPPER_XO_MIGRATED] = 1f
    }

    /** Mirrors [NativeBmwDspValues.migrateHighBandIfNeeded]: marker claimed. DEFAULTS already
     *  ships the block muted, so this only flips the marker. Must run last, matching load()'s
     *  real call order. */
    private fun seedHighBandMigrated(values: FloatArray) {
        values[NativeBmwDspValues.INDEX_HIGH_BAND_MIGRATED] = 1f
    }

    /** Mirrors [NativeBmwDspValues.migrateCrossoverTypeIfNeeded]: all 4 outputs forced to LR4,
     *  marker claimed. Must run after [seedIndependentOutputs] in [migratedDefaults], matching
     *  load()'s real call order (it overwrites whatever that legacy-copy step wrote). */
    private fun seedCrossoverTypeMigrated(values: FloatArray) {
        for (output in 0 until NativeBmwDspValues.OUTPUT_COUNT) {
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                NativeBmwDspValues.CROSSOVER_TYPE_LR4
        }
        values[NativeBmwDspValues.INDEX_CROSSOVER_TYPE_MIGRATED] = NativeBmwDspValues.CROSSOVER_TYPE_MIGRATED_MARKER
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
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] = values[NativeBmwDspValues.INDEX_LOW_LR4]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED)] = values[NativeBmwDspValues.INDEX_SUBSONIC_ENABLED]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_SUBSONIC_FREQ)] = values[NativeBmwDspValues.INDEX_SUBSONIC_FREQ]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_MUTE)] = values[NativeBmwDspValues.INDEX_LOW_MUTE]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_INVERT)] = values[NativeBmwDspValues.INDEX_LOW_INVERT]
            copyCompressor(output, NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED)
        }
        listOf(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.OUTPUT_MID_RIGHT).forEach { output ->
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = values[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ]
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] = 1f
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
        prefs.edit().putString(NativeBmwDspValues.KEY, "1,2,3,1").apply()

        val loaded = NativeBmwDspValues.load(context)

        // Slots 1 and 2 are the retired passthroughs, held at 0 on load.
        val expected = NativeBmwDspValues.DEFAULTS.copyOf().also {
            it[0] = 1f; it[1] = 0f; it[2] = 0f; it[3] = 1f
            seedIndependentOutputs(it)
            seedMbc(it)
            seedLegacyCompDisabled(it)
            seedCrossoverTypeMigrated(it)
            seedMidUpperCrossoverMigrated(it)
            seedHighBandMigrated(it)
            seedHighBusLimiterMigrated(it)
            seedVirtualMigrated(it)
            seedMidUpperXoTypeMigrated(it)
        }
        assertArrayEquals(expected, loaded, 0f)
        assertArrayEquals(expected, NativeBmwDspValues.load(context), 0f)
    }

    @Test
    fun retiredPassthroughSlotsAreHeldAtZeroOnLoadAndSave() {
        // A save from before the crossover bypass with a passthrough flag left on must not carry
        // it: nothing reads those slots any more, and they stay 0 whatever writes them.
        val values = migratedDefaults()
        values[1] = 1f
        values[2] = 1f
        values[291] = 1f
        assertTrue(NativeBmwDspValues.save(context, values))
        assertEquals(0f, values[1], 0f)
        val loaded = NativeBmwDspValues.load(context)
        for (slot in intArrayOf(1, 2, 291)) assertEquals(0f, loaded[slot], 0f)
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_CROSSOVER_BYPASS], 0f)
    }

    @Test
    fun loadSavesAStaleConfigOnceAndThenLeavesTheFileAlone() {
        // An old save: every migration still has to run. The pass ends in one save, and a second
        // load finds nothing left to migrate and leaves the file alone.
        val stale = NativeBmwDspValues.DEFAULTS.copyOf()
        NativeBmwDspStore(context.noBackupFilesDir).save(stale)
        val primary = NativeBmwDspStore(context.noBackupFilesDir).primaryPath()

        val first = NativeBmwDspValues.load(context)
        assertArrayEquals(migratedDefaults(), first, 0f)
        val afterFirst = primary.readText()
        primary.setLastModified(0L)

        val second = NativeBmwDspValues.load(context)
        assertArrayEquals(first, second, 0f)
        assertEquals(afterFirst, primary.readText())
        assertEquals(0L, primary.lastModified())
    }

    @Test
    fun aStoreReplacedUnderneathIsMigratedAgain() {
        // e.g. a whole-app backup restore swapping the file in place after settings were loaded.
        NativeBmwDspValues.load(context)
        NativeBmwDspStore(context.noBackupFilesDir).save(NativeBmwDspValues.DEFAULTS.copyOf())
        assertArrayEquals(migratedDefaults(), NativeBmwDspValues.load(context), 0f)
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
        // The shared 139..142 marker climbs 0 -> 1 (meas-mute seeded) -> 2 (stage-delay reclaim).
        assertEquals(2f, loaded[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED], 0f)
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
    fun loadZeroesLeftoverMidLpfValuesWhenReclaimingStageDelaySlots() {
        // A config saved while the short-lived Mid-band LPF lived at 141/142: enable flag +
        // corner Hz. Read as stage delay ms, the corner would clamp to STAGE_DELAY_MAX_MS.
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_STAGE_DELAY_L] = 1f
            it[NativeBmwDspValues.INDEX_STAGE_DELAY_R] = 2000f
            it[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED] = 1f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_STAGE_DELAY_L], 0f)
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_STAGE_DELAY_R], 0f)
        assertEquals(2f, loaded[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED], 0f)
    }

    @Test
    fun stageDelayIsHeldAtZeroNowItHasNoControl() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_STAGE_DELAY_L] = 3.5f
            it[NativeBmwDspValues.INDEX_STAGE_DELAY_R] = 1.2f
            it[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED] = 2f
        }
        NativeBmwDspValues.save(context, values)

        // Zeroed in the caller's array (what gets broadcast) and on disk.
        assertEquals(0f, values[NativeBmwDspValues.INDEX_STAGE_DELAY_L], 0f)
        val loaded = NativeBmwDspValues.load(context)
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_STAGE_DELAY_L], 0f)
        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_STAGE_DELAY_R], 0f)

        val restored = NativeBmwDspValues.migrateRestoredValues(
            context, migratedDefaults().also { it[NativeBmwDspValues.INDEX_STAGE_DELAY_R] = 4f },
        )
        assertEquals(0f, restored[NativeBmwDspValues.INDEX_STAGE_DELAY_R], 0f)
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
    fun loadSeedsCrossoverTypeLr4WhenTheMigrationMarkerIsUnset() {
        // Slot 42 was the legacy Mono Bass enable boolean (0/1), so an old install could easily
        // have a stored 1f there -- the marker must be a value that boolean never took on its
        // own (CROSSOVER_TYPE_MIGRATED_MARKER = 2f), not 1f, or a real "already migrated" config
        // would be misread as still-unmigrated forever. 1f here must still trigger the reseed.
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                NativeBmwDspValues.CROSSOVER_TYPE_BW2
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                NativeBmwDspValues.CROSSOVER_TYPE_BW3
            it[NativeBmwDspValues.INDEX_CROSSOVER_TYPE_MIGRATED] = 1f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        for (output in 0 until NativeBmwDspValues.OUTPUT_COUNT) {
            assertEquals(
                NativeBmwDspValues.CROSSOVER_TYPE_LR4,
                loaded[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)],
                0f,
            )
        }
        assertEquals(
            NativeBmwDspValues.CROSSOVER_TYPE_MIGRATED_MARKER,
            loaded[NativeBmwDspValues.INDEX_CROSSOVER_TYPE_MIGRATED],
            0f,
        )
    }

    @Test
    fun loadLeavesADeliberatelySelectedCrossoverTypeAloneOnceMigrated() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                NativeBmwDspValues.CROSSOVER_TYPE_BW2
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                NativeBmwDspValues.CROSSOVER_TYPE_BW3
            it[NativeBmwDspValues.INDEX_CROSSOVER_TYPE_MIGRATED] = NativeBmwDspValues.CROSSOVER_TYPE_MIGRATED_MARKER
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(
            NativeBmwDspValues.CROSSOVER_TYPE_BW2,
            loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)],
            0f,
        )
        assertEquals(
            NativeBmwDspValues.CROSSOVER_TYPE_BW3,
            loaded[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)],
            0f,
        )
    }

    @Test
    fun bw4CrossoverSurvivesSaveAndReload() {
        val values = migratedDefaults()
        for (output in 0..3) {
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                NativeBmwDspValues.CROSSOVER_TYPE_BW4
        }
        NativeBmwDspValues.save(context, values)
        val loaded = NativeBmwDspValues.load(context)
        for (output in 0..3) {
            assertEquals(4f, loaded[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)], 0f)
        }
    }

    @Test
    fun firstOrderCrossoverSurvivesSaveAndReload() {
        val values = migratedDefaults()
        for (output in 0..3) {
            values[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                NativeBmwDspValues.CROSSOVER_TYPE_BW1
        }
        NativeBmwDspValues.save(context, values)
        val loaded = NativeBmwDspValues.load(context)
        for (output in 0..3) {
            assertEquals(3f, loaded[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)], 0f)
        }
    }

    @Test
    fun loadSeedsMidUpperCrossoverDisabledOnConfigsSavedBeforeItExisted() {
        // A pre-feature config: the block sits at leftover/uninitialized values and the marker
        // is unset. Even if a stray "enabled" made it into the array, load() must bring the
        // upper corner back OFF and claim the marker.
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.midUpperXoIndex(
                NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED,
            )] = 1f
            it[NativeBmwDspValues.INDEX_MID_UPPER_XO_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(
            0f,
            loaded[NativeBmwDspValues.midUpperXoIndex(
                NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED,
            )],
            0f,
        )
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_MID_UPPER_XO_MIGRATED], 0f)
    }

    @Test
    fun loadLeavesADeliberatelyEnabledMidUpperCrossoverAloneOnceMigrated() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.midUpperXoIndex(
                NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED,
            )] = 1f
            it[NativeBmwDspValues.midUpperXoIndex(
                NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.MID_UPPER_XO_FIELD_FREQ,
            )] = 3500f
            it[NativeBmwDspValues.INDEX_MID_UPPER_XO_MIGRATED] = 1f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(
            1f,
            loaded[NativeBmwDspValues.midUpperXoIndex(
                NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED,
            )],
            0f,
        )
        assertEquals(
            3500f,
            loaded[NativeBmwDspValues.midUpperXoIndex(
                NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.MID_UPPER_XO_FIELD_FREQ,
            )],
            0f,
        )
    }

    @Test
    fun loadSeedsMidUpperSlopeFromMidTypeOnConfigsSavedBeforeItExisted() {
        // A pre-feature save whose Mid band uses BW2: its lowpass was shaped by that BW2, so the
        // new slope fields must come up BW2 too, not the LR4 default.
        val midLeftType = NativeBmwDspValues.outputIndex(
            NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE,
        )
        val midRightType = NativeBmwDspValues.outputIndex(
            NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE,
        )
        val values = migratedDefaults().also {
            it[midLeftType] = 0f
            it[midRightType] = 0f
            it[NativeBmwDspValues.midUpperXoTypeIndex(NativeBmwDspValues.OUTPUT_MID_LEFT)] = 2f
            it[NativeBmwDspValues.midUpperXoTypeIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT)] = 2f
            it[NativeBmwDspValues.INDEX_MID_UPPER_XO_TYPE_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(0f, loaded[NativeBmwDspValues.midUpperXoTypeIndex(NativeBmwDspValues.OUTPUT_MID_LEFT)], 0f)
        assertEquals(0f, loaded[NativeBmwDspValues.midUpperXoTypeIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT)], 0f)
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_MID_UPPER_XO_TYPE_MIGRATED], 0f)

        // Once migrated, a deliberately different lowpass slope survives the next load.
        loaded[NativeBmwDspValues.midUpperXoTypeIndex(NativeBmwDspValues.OUTPUT_MID_LEFT)] = 4f
        NativeBmwDspValues.save(context, loaded)
        assertEquals(
            4f,
            NativeBmwDspValues.load(context)[NativeBmwDspValues.midUpperXoTypeIndex(NativeBmwDspValues.OUTPUT_MID_LEFT)],
            0f,
        )
    }

    @Test
    fun migrateRestoredValuesSeedsMidUpperCrossoverDisabledLikeLoadDoes() {
        // A pre-feature private-backup-shaped array (205 values, predates the 205 -> 210 growth).
        val legacy = FloatArray(205) { index -> NativeBmwDspValues.DEFAULTS[index] }

        val restored = NativeBmwDspValues.migrateRestoredValues(context, legacy)

        assertEquals(NativeBmwDspValues.SIZE, restored.size)
        assertEquals(
            0f,
            restored[NativeBmwDspValues.midUpperXoIndex(
                NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED,
            )],
            0f,
        )
        assertEquals(1f, restored[NativeBmwDspValues.INDEX_MID_UPPER_XO_MIGRATED], 0f)
    }

    @Test
    fun loadSeedsHighBandMutedOnConfigsSavedBeforeItExisted() {
        // A pre-feature config: the block sits at leftover/uninitialized values and the marker
        // is unset. Even if a stray "enabled" made it into the array, load() must bring High
        // back muted/bypassed and claim the marker.
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_HIGH_XO_PASS] = 0f
            it[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_MUTE)] = 0f
            it[NativeBmwDspValues.INDEX_HIGH_BAND_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_HIGH_XO_PASS], 0f)
        assertEquals(
            1f,
            loaded[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_MUTE)],
            0f,
        )
        assertEquals(1f, loaded[NativeBmwDspValues.INDEX_HIGH_BAND_MIGRATED], 0f)
    }

    @Test
    fun loadLeavesADeliberatelyEnabledHighBandAloneOnceMigrated() {
        val values = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_HIGH_XO_PASS] = 0f
            it[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_MUTE)] = 0f
            it[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 4000f
            it[NativeBmwDspValues.INDEX_HIGH_BAND_MIGRATED] = 1f
        }
        NativeBmwDspValues.save(context, values)

        val loaded = NativeBmwDspValues.load(context)

        assertEquals(0f, loaded[NativeBmwDspValues.INDEX_HIGH_XO_PASS], 0f)
        assertEquals(
            0f,
            loaded[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_MUTE)],
            0f,
        )
        assertEquals(
            4000f,
            loaded[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)],
            0f,
        )
    }

    @Test
    fun migrateRestoredValuesSeedsHighBandMutedLikeLoadDoes() {
        // A pre-High private-backup-shaped array (210 values, predates the 210 -> 262 growth).
        val legacy = FloatArray(210) { index -> NativeBmwDspValues.DEFAULTS[index] }

        val restored = NativeBmwDspValues.migrateRestoredValues(context, legacy)

        assertEquals(NativeBmwDspValues.SIZE, restored.size)
        assertEquals(1f, restored[NativeBmwDspValues.INDEX_HIGH_XO_PASS], 0f)
        assertEquals(
            1f,
            restored[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_MUTE)],
            0f,
        )
        assertEquals(1f, restored[NativeBmwDspValues.INDEX_HIGH_BAND_MIGRATED], 0f)
    }

    @Test
    fun migrateRestoredValuesSeedsHighBusLimiterDisabledLikeLoadDoes() {
        // A pre-High-bus-limiter backup-shaped array (262 values, predates the 262 -> 266 growth).
        val legacy = FloatArray(262) { index -> NativeBmwDspValues.DEFAULTS[index] }

        val restored = NativeBmwDspValues.migrateRestoredValues(context, legacy)

        assertEquals(NativeBmwDspValues.SIZE, restored.size)
        assertEquals(0f, restored[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED], 0f)
        assertEquals(-3f, restored[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_THRESHOLD], 0f)
        assertEquals(120f, restored[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_RELEASE], 0f)
        assertEquals(1f, restored[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_MIGRATED], 0f)
    }

    @Test
    fun loadForceDisablesHighBusLimiterOnceThenRespectsALaterEnable() {
        val unmigrated = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, unmigrated)

        val first = NativeBmwDspValues.load(context)
        assertEquals(0f, first[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED], 0f)
        assertEquals(1f, first[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_MIGRATED], 0f)

        NativeBmwDspValues.save(context, first.also { it[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED] = 1f })
        val second = NativeBmwDspValues.load(context)
        assertEquals(1f, second[NativeBmwDspValues.INDEX_BUS_LIMITER_HIGH_ENABLED], 0f)
    }

    @Test
    fun migrateRestoredValuesSeedsVirtualStageOffLikeLoadDoes() {
        // A pre-virtual-stage backup-shaped array (266 values, predates the 266 -> 288 growth).
        val legacy = FloatArray(266) { index -> NativeBmwDspValues.DEFAULTS[index] }

        val restored = NativeBmwDspValues.migrateRestoredValues(context, legacy)

        assertEquals(NativeBmwDspValues.SIZE, restored.size)
        assertEquals(0f, restored[NativeBmwDspValues.INDEX_VIRTUAL_ENABLED], 0f)
        assertEquals(0f, restored[NativeBmwDspValues.INDEX_VIRTUAL_CENTRE_LEVEL], 0f)
        assertEquals(
            0f,
            restored[NativeBmwDspValues.virtualFeedIndex(NativeBmwDspValues.VIRTUAL_SIDE_RIGHT, NativeBmwDspValues.VIRTUAL_FEED_DELAY)],
            0f,
        )
        assertEquals(1f, restored[NativeBmwDspValues.INDEX_VIRTUAL_MIGRATED], 0f)
    }

    @Test
    fun loadForceDisablesVirtualStageOnceThenRespectsALaterEnable() {
        val unmigrated = migratedDefaults().also {
            it[NativeBmwDspValues.INDEX_VIRTUAL_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_VIRTUAL_MIGRATED] = 0f
        }
        NativeBmwDspValues.save(context, unmigrated)

        val first = NativeBmwDspValues.load(context)
        assertEquals(0f, first[NativeBmwDspValues.INDEX_VIRTUAL_ENABLED], 0f)
        assertEquals(1f, first[NativeBmwDspValues.INDEX_VIRTUAL_MIGRATED], 0f)

        NativeBmwDspValues.save(context, first.also { it[NativeBmwDspValues.INDEX_VIRTUAL_ENABLED] = 1f })
        val second = NativeBmwDspValues.load(context)
        assertEquals(1f, second[NativeBmwDspValues.INDEX_VIRTUAL_ENABLED], 0f)
    }

    @Test
    fun virtualFeedIndexLaysOutLeftThenRight() {
        assertEquals(273, NativeBmwDspValues.virtualFeedIndex(NativeBmwDspValues.VIRTUAL_SIDE_LEFT, NativeBmwDspValues.VIRTUAL_FEED_GAIN))
        assertEquals(279, NativeBmwDspValues.virtualFeedIndex(NativeBmwDspValues.VIRTUAL_SIDE_LEFT, NativeBmwDspValues.VIRTUAL_FEED_AP_ORDER))
        assertEquals(280, NativeBmwDspValues.virtualFeedIndex(NativeBmwDspValues.VIRTUAL_SIDE_RIGHT, NativeBmwDspValues.VIRTUAL_FEED_GAIN))
        assertEquals(286, NativeBmwDspValues.virtualFeedIndex(NativeBmwDspValues.VIRTUAL_SIDE_RIGHT, NativeBmwDspValues.VIRTUAL_FEED_AP_ORDER))
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
        assertEquals(80f, loaded[NativeBmwDspValues.INDEX_MBC_XO_0], 0f)
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
    fun migrateRestoredValuesReclaimsStageDelaySlotsLikeLoadDoes() {
        // A v2 private-backup-shaped array: 192 values (predates the 192 -> 205
        // measurement-generator growth), captured while the short-lived Mid-band LPF still lived
        // at 141/142 (enable flag, corner Hz) and before the stage-delay reclaim migration
        // existed (marker at 140 is 0). Without migrateRestoredValues(), padToCurrentSize() alone
        // would leave these bytes exactly where they are, and the restore path (ParametricEqScreen
        // applyBackupRestore) would save+broadcast the leftover corner Hz straight to the running
        // DSP as a stage delay (clamped to STAGE_DELAY_MAX_MS).
        val legacy = FloatArray(192) { index -> NativeBmwDspValues.DEFAULTS[index] }
        legacy[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED] = 0f
        legacy[NativeBmwDspValues.INDEX_STAGE_DELAY_L] = 1f       // leftover Mid-LPF "enabled"
        legacy[NativeBmwDspValues.INDEX_STAGE_DELAY_R] = 2000f    // leftover Mid-LPF "corner Hz"

        val restored = NativeBmwDspValues.migrateRestoredValues(context, legacy)

        assertEquals(NativeBmwDspValues.SIZE, restored.size)
        assertEquals(0f, restored[NativeBmwDspValues.INDEX_STAGE_DELAY_L], 0f)
        assertEquals(0f, restored[NativeBmwDspValues.INDEX_STAGE_DELAY_R], 0f)
        assertEquals(2f, restored[NativeBmwDspValues.INDEX_MEAS_MUTE_STOPBAND_MIGRATED], 0f)
    }

    @Test
    fun saveReturnsFalseWhenThePrimaryCannotBeWritten() {
        val previous = NativeBmwDspValues.load(context)
        assertTrue(File(context.noBackupFilesDir, NativeBmwDspStore.FILE_NAME + ".tmp").mkdir())
        val next = previous.copyOf().also { it[NativeBmwDspValues.INDEX_HEADROOM] = -9f }

        assertFalse(NativeBmwDspValues.save(context, next))
        assertArrayEquals(previous, NativeBmwDspValues.load(context), 0f)
    }

    @Test
    fun preparingAnOldBackupDoesNotWriteAnyIntermediateState() {
        val previous = NativeBmwDspValues.load(context)
        val before = File(context.noBackupFilesDir, NativeBmwDspStore.FILE_NAME).readBytes()
        val recoveryBefore = File(context.noBackupFilesDir, NativeBmwDspStore.RECOVERY_FILE_NAME).readBytes()
        val oldBackup = NativeBmwDspValues.DEFAULTS.copyOf(192).also { it[5] = -9f }

        val prepared = NativeBmwDspValues.migrateRestoredValues(context, oldBackup)

        assertEquals(-9f, prepared[5], 0f)
        assertArrayEquals(previous, NativeBmwDspValues.load(context), 0f)
        assertTrue(before.contentEquals(File(context.noBackupFilesDir, NativeBmwDspStore.FILE_NAME).readBytes()))
        assertTrue(recoveryBefore.contentEquals(File(context.noBackupFilesDir, NativeBmwDspStore.RECOVERY_FILE_NAME).readBytes()))
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
        assertEquals(80f, padded[NativeBmwDspValues.INDEX_MBC_XO_0], 0f)
        assertEquals(100f, padded[NativeBmwDspValues.INDEX_MBC_MIX], 0f)
    }

    @Test
    fun monoBassShipsOffAndItsSplitStaysInRange() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        assertEquals(0f, values[NativeBmwDspValues.INDEX_MONO_BASS_ENABLED], 0f)
        assertEquals(90f, values[NativeBmwDspValues.INDEX_MONO_BASS_FREQ], 0f)
        assertEquals(0.0, NativeBmwDspValues.monoBassSplitHz(values), 0.0)
        values[NativeBmwDspValues.INDEX_MONO_BASS_ENABLED] = 1f
        assertEquals(90.0, NativeBmwDspValues.monoBassSplitHz(values), 0.0)
        values[NativeBmwDspValues.INDEX_MONO_BASS_FREQ] = 400f
        assertEquals(120.0, NativeBmwDspValues.monoBassSplitHz(values), 0.0)
        values[NativeBmwDspValues.INDEX_MONO_BASS_FREQ] = Float.NaN
        assertEquals(90.0, NativeBmwDspValues.monoBassSplitHz(values), 0.0)
        // A save from before the 297 -> 299 growth has neither slot: off.
        assertEquals(0.0, NativeBmwDspValues.monoBassSplitHz(values.copyOf(297)), 0.0)
    }

    @Test
    fun virtualBassShipsOffAndOnlyItsSubRemovalIsAFilter() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        assertEquals(0f, values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED], 0f)
        assertEquals(40f, values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ], 0f)
        assertEquals(50f, values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_AMOUNT], 0f)
        assertEquals(0f, values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_REMOVE_SUB], 0f)
        assertEquals(50f, values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_CHARACTER], 0f)
        // The subsonic slope defaults to LR4, so older saves sound the same.
        assertEquals(NativeBmwDspValues.SUBSONIC_SLOPE_LR4, values[NativeBmwDspValues.INDEX_SUBSONIC_SLOPE], 0f)
        assertEquals(0.0, NativeBmwDspValues.virtualBassRemovalHz(values), 0.0)
        // On, but keeping the sub: still no filter to draw.
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 1f
        assertEquals(0.0, NativeBmwDspValues.virtualBassRemovalHz(values), 0.0)
        // Removal is an HP2 at 0.8 x the reach: 32 Hz for the default 40 Hz reach.
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_REMOVE_SUB] = 1f
        assertEquals(32.0, NativeBmwDspValues.virtualBassRemovalHz(values), 1e-9)
        // The reach is capped at 60 Hz, so removal never reaches into the woofers' band.
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ] = 400f
        assertEquals(48.0, NativeBmwDspValues.virtualBassRemovalHz(values), 1e-9)
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ] = Float.NaN
        assertEquals(32.0, NativeBmwDspValues.virtualBassRemovalHz(values), 1e-9)
        // Remove sub set but Virtual Bass off: nothing.
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 0f
        assertEquals(0.0, NativeBmwDspValues.virtualBassRemovalHz(values), 0.0)
        // A save from before the 299 -> 303 growth has none of the slots: off.
        assertEquals(0.0, NativeBmwDspValues.virtualBassRemovalHz(values.copyOf(299)), 0.0)
    }

    @Test
    fun convolverShipsOffAtUnityGain() {
        // A save from before the 307 -> 309 growth loads these defaults: the chain sounds as before.
        assertTrue(NativeBmwDspValues.SIZE > NativeBmwDspValues.INDEX_CONVOLVER_GAIN)
        assertEquals(0f, NativeBmwDspValues.DEFAULTS[NativeBmwDspValues.INDEX_CONVOLVER_ENABLED], 0f)
        assertEquals(0f, NativeBmwDspValues.DEFAULTS[NativeBmwDspValues.INDEX_CONVOLVER_GAIN], 0f)
    }

    @Test
    fun deepBassShipsOffWithA12DbCap() {
        // A save from before the 312 -> 313 growth loads the 12 dB cap the old loudness used.
        assertEquals(313, NativeBmwDspValues.SIZE)
        assertEquals(0f, NativeBmwDspValues.DEFAULTS[NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED], 0f)
        assertEquals(100f, NativeBmwDspValues.DEFAULTS[NativeBmwDspValues.INDEX_DEEP_BASS_STRENGTH], 0f)
        assertEquals(12f, NativeBmwDspValues.DEFAULTS[NativeBmwDspValues.INDEX_DEEP_BASS_MAX_DB], 0f)
    }

    @Test
    fun deepBassCurveMatchesNative() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        values[NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED] = 1f
        // Full volume: nothing, centred at 50 Hz.
        var c = NativeBmwDspValues.deepBassCurve(values, volumeDb = 0.0)
        assertEquals(50.0, c.centreHz, 1e-9)
        assertEquals(0.0, c.gainDb, 0.0)
        // -20 dB: +6 dB at 50 * (32/50)^0.5 = 40 Hz.
        c = NativeBmwDspValues.deepBassCurve(values, volumeDb = -20.0)
        assertEquals(40.0, c.centreHz, 1e-9)
        assertEquals(6.0, c.gainDb, 1e-9)
        // -60 dB: capped at Max boost, centre held at 32 Hz.
        values[NativeBmwDspValues.INDEX_DEEP_BASS_MAX_DB] = 9f
        c = NativeBmwDspValues.deepBassCurve(values, volumeDb = -60.0)
        assertEquals(32.0, c.centreHz, 1e-9)
        assertEquals(9.0, c.gainDb, 1e-9)
        // Strength scales the gain.
        values[NativeBmwDspValues.INDEX_DEEP_BASS_STRENGTH] = 50f
        assertEquals(4.5, NativeBmwDspValues.deepBassCurve(values, volumeDb = -60.0).gainDb, 1e-9)
        // Off: no gain.
        values[NativeBmwDspValues.INDEX_DEEP_BASS_ENABLED] = 0f
        assertEquals(0.0, NativeBmwDspValues.deepBassCurve(values, volumeDb = -60.0).gainDb, 0.0)
    }

    @Test
    fun crossoverBypassAndMeasurementNeutralShipOff() {
        // A save from before the 309 -> 312 growth loads these defaults: the split runs, neutral off.
        assertTrue(NativeBmwDspValues.SIZE > NativeBmwDspValues.INDEX_NEUTRAL_ALLOW)
        assertEquals(0f, NativeBmwDspValues.DEFAULTS[NativeBmwDspValues.INDEX_CROSSOVER_BYPASS], 0f)
        assertEquals(0f, NativeBmwDspValues.DEFAULTS[NativeBmwDspValues.INDEX_MEASUREMENT_NEUTRAL], 0f)
        assertEquals(0f, NativeBmwDspValues.DEFAULTS[NativeBmwDspValues.INDEX_NEUTRAL_ALLOW], 0f)
        // The retired per-band passthrough slots read 0 and nothing native reads them.
        for (slot in intArrayOf(1, 2, 291)) {
            assertEquals(0f, NativeBmwDspValues.DEFAULTS[slot], 0f)
            assertFalse(NativeBmwDspSchema.READ_BY_NATIVE[slot])
        }
    }

    @Test
    fun virtualBassRangeAndResponseDefaultToTheFixedValuesTheyReplace() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        // 5x the reach and a 200 ms release: what the engine used before they were adjustable.
        assertEquals(5f, values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_RANGE], 0f)
        assertEquals(200f, values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_RESPONSE], 0f)
        // Off: no harmonics, so no top.
        assertEquals(0.0, NativeBmwDspValues.virtualBassTopHz(values), 0.0)
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 1f
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ] = 35f
        assertEquals(175.0, NativeBmwDspValues.virtualBassTopHz(values), 1e-9)
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_RANGE] = 3f
        assertEquals(105.0, NativeBmwDspValues.virtualBassTopHz(values), 1e-9)
        // Clamped as native clamps it; a non-finite value falls back to the default.
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_RANGE] = 20f
        assertEquals(8.0, NativeBmwDspValues.virtualBassRange(values), 1e-9)
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_RANGE] = Float.NaN
        assertEquals(5.0, NativeBmwDspValues.virtualBassRange(values), 1e-9)
        // The harmonics mostly reach the 4th of a note at the input corner (0.85 x 35 x 4 = 119 Hz),
        // with only a steep tail above; a narrower band trims them.
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_RANGE] = 5f
        assertEquals(119.0, NativeBmwDspValues.virtualBassHarmonicsTopHz(values), 1e-9)
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_RANGE] = 3f
        assertEquals(105.0, NativeBmwDspValues.virtualBassHarmonicsTopHz(values), 1e-9)
        values[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 0f
        assertEquals(0.0, NativeBmwDspValues.virtualBassHarmonicsTopHz(values), 0.0)
        // A save from before the 305 -> 307 growth reads as the default range.
        assertEquals(5.0, NativeBmwDspValues.virtualBassRange(values.copyOf(305)), 1e-9)
    }
}
