package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden test that the native flat-config schema agrees with the Kotlin one.
 *
 * `NativeBmwDspProcessor::configure()` reads `v[N]` with N hand-counted against
 * `NativeBmwDspValues`'s `INDEX_*` / `FIELD_*` / `MBC_FIELD_*` constants. `NativeBmwDspSchema.h`
 * lists those N once for the native side; this test asserts each equals the matching Kotlin
 * constant, so a bump on either side fails CI immediately. The header's indices are in turn
 * proven to be the ones `configure()` actually consumes by `schema_agreement_test.cpp` in
 * native-tests (behavioural probes).
 */
class NativeBmwSchemaAgreementTest {

    private val headerConstants: Map<String, Int> by lazy {
        val header = locateHeader()
        val regex = Regex("""inline constexpr (?:std::size_t|int) (k\w+)\s*=\s*(\d+);""")
        regex.findAll(header.readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
            .also { assertTrue("parsed no constants from ${header.path}", it.isNotEmpty()) }
    }

    // header constant name -> the Kotlin value it must equal
    private val expected: Map<String, Int> = mapOf(
        "kSize" to NativeBmwDspValues.SIZE,

        "kEnabled" to NativeBmwDspValues.INDEX_ENABLED,
        "kLpfPass" to NativeBmwDspValues.INDEX_LPF_PASS,
        "kHpfPass" to NativeBmwDspValues.INDEX_HPF_PASS,
        "kChannelMute" to NativeBmwDspValues.INDEX_CHANNEL_MUTE,
        "kMeasurementMute" to NativeBmwDspValues.INDEX_MEASUREMENT_MUTE,
        "kHeadroom" to NativeBmwDspValues.INDEX_HEADROOM,
        "kLowGainL" to NativeBmwDspValues.INDEX_LOW_GAIN_L,
        "kLowGainR" to NativeBmwDspValues.INDEX_LOW_GAIN_R,
        "kMidGainL" to NativeBmwDspValues.INDEX_MID_GAIN_L,
        "kMidGainR" to NativeBmwDspValues.INDEX_MID_GAIN_R,
        "kPostGainL" to NativeBmwDspValues.INDEX_POST_GAIN_L,
        "kPostGainR" to NativeBmwDspValues.INDEX_POST_GAIN_R,

        "kMidDelayL" to NativeBmwDspValues.INDEX_MID_DELAY_L,
        "kMidDelayR" to NativeBmwDspValues.INDEX_MID_DELAY_R,
        "kLowDelayL" to NativeBmwDspValues.INDEX_LOW_DELAY_L,
        "kLowDelayR" to NativeBmwDspValues.INDEX_LOW_DELAY_R,

        "kTiltEnabled" to NativeBmwDspValues.INDEX_TILT_ENABLED,
        "kTiltAmount" to NativeBmwDspValues.INDEX_TILT_AMOUNT,
        "kTiltFreq" to NativeBmwDspValues.INDEX_TILT_FREQ,

        "kMonoBassEnabled" to NativeBmwDspValues.INDEX_MONO_BASS_ENABLED,
        "kMonoBassFreq" to NativeBmwDspValues.INDEX_MONO_BASS_FREQ,
        "kMonoBassBlend" to NativeBmwDspValues.INDEX_MONO_BASS_BLEND,
        "kMonoBassMakeup" to NativeBmwDspValues.INDEX_MONO_BASS_MAKEUP,

        "kRoutingBase" to NativeBmwDspValues.INDEX_ROUTING,
        "kRoutingStride" to 2,

        "kAllPassBase" to NativeBmwDspValues.INDEX_ALL_PASS,
        "kAllPassSectionWidth" to NativeBmwDspValues.ALL_PASS_SECTION_WIDTH,
        "kAllPassSectionsPerOutput" to NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT,

        "kOutputConfigBase" to NativeBmwDspValues.INDEX_OUTPUT_CONFIG,
        "kOutputConfigWidth" to NativeBmwDspValues.OUTPUT_CONFIG_WIDTH,
        "kOutCrossoverFreq" to NativeBmwDspValues.FIELD_CROSSOVER_FREQ,
        "kOutSubsonicEnabled" to NativeBmwDspValues.FIELD_SUBSONIC_ENABLED,
        "kOutSubsonicFreq" to NativeBmwDspValues.FIELD_SUBSONIC_FREQ,
        "kOutMuted" to NativeBmwDspValues.FIELD_MUTE,
        "kOutPolarityInverted" to NativeBmwDspValues.FIELD_INVERT,
        "kOutCompressor" to NativeBmwDspValues.FIELD_COMPRESSOR_ENABLED,

        "kMeasMuteStopbandOctaves" to NativeBmwDspValues.INDEX_MEASUREMENT_MUTE_STOPBAND_OCTAVES,

        "kMbcEnabled" to NativeBmwDspValues.INDEX_MBC_ENABLED,
        "kMbcMix" to NativeBmwDspValues.INDEX_MBC_MIX,
        "kMbcXo0" to NativeBmwDspValues.INDEX_MBC_XO_0,
        "kMbcXo1" to NativeBmwDspValues.INDEX_MBC_XO_1,
        "kMbcXo2" to NativeBmwDspValues.INDEX_MBC_XO_2,
        "kMbcBandsBase" to NativeBmwDspValues.INDEX_MBC_BANDS,
        "kMbcBandWidth" to NativeBmwDspValues.MBC_BAND_WIDTH,
        "kMbcBandCount" to NativeBmwDspValues.MBC_BAND_COUNT,
        "kMbcBandEnabled" to NativeBmwDspValues.MBC_FIELD_ENABLED,
        "kMbcBandThreshold" to NativeBmwDspValues.MBC_FIELD_THRESHOLD,
        "kMbcBandRatio" to NativeBmwDspValues.MBC_FIELD_RATIO,
        "kMbcBandKnee" to NativeBmwDspValues.MBC_FIELD_KNEE,
        "kMbcBandAttack" to NativeBmwDspValues.MBC_FIELD_ATTACK,
        "kMbcBandRelease" to NativeBmwDspValues.MBC_FIELD_RELEASE,
        "kMbcBandMakeup" to NativeBmwDspValues.MBC_FIELD_MAKEUP,
        "kMbcBandStereoLink" to NativeBmwDspValues.MBC_FIELD_STEREO_LINK,

        "kBusLimLowEnabled" to NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED,
        "kBusLimLowThreshold" to NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_THRESHOLD,
        "kBusLimLowRelease" to NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_RELEASE,
        "kBusLimMidEnabled" to NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED,
        "kBusLimMidThreshold" to NativeBmwDspValues.INDEX_BUS_LIMITER_MID_THRESHOLD,
        "kBusLimMidRelease" to NativeBmwDspValues.INDEX_BUS_LIMITER_MID_RELEASE,

        "kMasterLimiterEnabled" to NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED,
        "kMasterLimiterThreshold" to NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD,
    )

    @Test
    fun everyNativeSchemaConstantEqualsItsKotlinCounterpart() {
        for ((name, kotlinValue) in expected) {
            val headerValue = headerConstants[name]
                ?: error("NativeBmwDspSchema.h has no `$name` -- header/test out of step")
            assertEquals("NativeBmwDspSchema.h::$name", kotlinValue, headerValue)
        }
    }

    @Test
    fun everyHeaderConstantIsCoveredByThisTest() {
        val unmapped = headerConstants.keys - expected.keys
        assertTrue(
            "NativeBmwDspSchema.h defines $unmapped with no assertion here -- add it to `expected`",
            unmapped.isEmpty(),
        )
    }

    @Test
    fun sizeMatchesDefaultsLength() {
        assertEquals(NativeBmwDspValues.SIZE, NativeBmwDspValues.DEFAULTS.size)
        assertEquals(NativeBmwDspValues.SIZE, headerConstants.getValue("kSize"))
    }

    private fun locateHeader(): File {
        val rel = "app/src/main/cpp/libjamesdsp-wrapper/NativeBmwDspSchema.h"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, rel)
            if (candidate.isFile) return candidate
            // also allow user.dir already being the app module
            val fromModule = File(dir, "src/main/cpp/libjamesdsp-wrapper/NativeBmwDspSchema.h")
            if (fromModule.isFile) return fromModule
            dir = dir.parentFile
        }
        error("could not locate $rel from ${System.getProperty("user.dir")}")
    }
}
