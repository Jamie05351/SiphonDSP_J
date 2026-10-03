package app.siphondsp.model

import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_AP_ENABLED
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_AP_FREQ
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_AP_ORDER
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_AP_Q
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_DELAY
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_GAIN
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_POLARITY
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_SIDE_LEFT
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_SIDE_RIGHT
import app.siphondsp.model.NativeBmwDspValues.virtualFeedIndex
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The CENTRE page's presets for the virtual centre (docs/NATIVE_BMW_VIRTUAL_CHANNELS.md). Pure,
 * so it's unit tested without Android.
 *
 * - [BOTH_SEATS]: no per-side delay; a complementary all-pass pair (Left at [SPREAD_LEFT_HZ],
 *   Right at [SPREAD_RIGHT_HZ]) so the centre reaches the two doors with a frequency-dependent
 *   phase difference, and neither front seat locks onto its nearer speaker.
 * - [DRIVER]: no all-pass; the nearer side's centre feed is delayed by the driver seat's L/R
 *   path difference, so the centre arrives from both sides together for the driver.
 * - [CUSTOM]: whatever the user set; picking it changes nothing.
 *
 * Levels (centre/side, per-feed gain) and polarity are left neutral by both real presets.
 */
enum class VirtualCentrePreset {
    BOTH_SEATS,
    DRIVER,
    CUSTOM;

    companion object {
        const val SPREAD_LEFT_HZ = 700f
        const val SPREAD_RIGHT_HZ = 2500f
        const val SPREAD_Q = 0.70710677f
        /** Second order, in the all-pass order slot's encoding (1 = 1st, 2 = 2nd). */
        const val SPREAD_ORDER = 2f
        /** Speed of sound, cm per ms (same constant as the speaker map's alignment). */
        private const val SOUND_CM_PER_MS = 34.3f

        /**
         * The values [preset] writes, keyed by flat index. [driverPathLeftCm]/[driverPathRightCm]
         * are the driver seat's paths to the Left/Right mid drivers (only [DRIVER] uses them).
         * Empty for [CUSTOM].
         */
        fun updates(preset: VirtualCentrePreset, driverPathLeftCm: Float, driverPathRightCm: Float): Map<Int, Float> {
            if (preset == CUSTOM) return emptyMap()
            val out = HashMap<Int, Float>()
            out[NativeBmwDspValues.INDEX_VIRTUAL_CENTRE_LEVEL] = 0f
            out[NativeBmwDspValues.INDEX_VIRTUAL_SIDE_LEVEL] = 0f
            val (delayLeft, delayRight) =
                if (preset == DRIVER) driverDelaysMs(driverPathLeftCm, driverPathRightCm) else 0f to 0f
            for ((side, delay, spreadHz) in listOf(
                Triple(VIRTUAL_SIDE_LEFT, delayLeft, SPREAD_LEFT_HZ),
                Triple(VIRTUAL_SIDE_RIGHT, delayRight, SPREAD_RIGHT_HZ),
            )) {
                out[virtualFeedIndex(side, VIRTUAL_FEED_GAIN)] = 0f
                out[virtualFeedIndex(side, VIRTUAL_FEED_DELAY)] = delay
                out[virtualFeedIndex(side, VIRTUAL_FEED_POLARITY)] = 0f
                out[virtualFeedIndex(side, VIRTUAL_FEED_AP_ENABLED)] = if (preset == BOTH_SEATS) 1f else 0f
                out[virtualFeedIndex(side, VIRTUAL_FEED_AP_FREQ)] = spreadHz
                out[virtualFeedIndex(side, VIRTUAL_FEED_AP_Q)] = SPREAD_Q
                out[virtualFeedIndex(side, VIRTUAL_FEED_AP_ORDER)] = SPREAD_ORDER
            }
            return out
        }

        /** The preset the current values match, or [CUSTOM] if they match neither. */
        fun detect(get: (Int) -> Float, driverPathLeftCm: Float, driverPathRightCm: Float): VirtualCentrePreset =
            listOf(BOTH_SEATS, DRIVER).firstOrNull { preset ->
                updates(preset, driverPathLeftCm, driverPathRightCm).all { (index, value) -> abs(get(index) - value) < 1e-3f }
            } ?: CUSTOM

        /**
         * (Left, Right) centre-feed delays for the driver: the nearer side waits for the farther
         * one, the farther side gets 0. Rounded to 0.01 ms and capped at the feed's 10 ms range.
         */
        fun driverDelaysMs(driverPathLeftCm: Float, driverPathRightCm: Float): Pair<Float, Float> {
            val gapMs = (abs(driverPathLeftCm - driverPathRightCm) / SOUND_CM_PER_MS)
                .coerceAtMost(NativeBmwDspValues.STAGE_DELAY_MAX_MS)
            val rounded = (gapMs * 100f).roundToInt() / 100f
            return if (driverPathLeftCm < driverPathRightCm) rounded to 0f else 0f to rounded
        }
    }
}
