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
}
