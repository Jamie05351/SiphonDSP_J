package app.siphondsp.model

import app.siphondsp.model.NativeBmwDspSchema as S

/**
 * The DSP chain's stages as the native engine names them: the `kStage*` bits of
 * native_bmw_dsp.schema (pinned to the C++ header by NativeBmwSchemaAgreementTest). The engine's
 * status reports which are running, [MeasurementNeutral] bypasses the first [NEUTRAL_COUNT] of
 * them, and the self-test lists those still altering the signal.
 *
 * [label] is the short name used in the status chips, the stage list and the self-test result.
 */
enum class DspStage(val bit: Int, val label: String, val neutralised: Boolean = true) {
    PRE_EQ(0, "Pre EQ"),
    CROSSOVER(1, "Crossover"),
    BAND_EQ(2, "Band EQ"),
    BAND_GAIN(3, "Band gains"),
    BAND_DELAY(4, "Band delays"),
    POLARITY(5, "Polarity"),
    ALL_PASS(6, "All-pass"),
    SUBSONIC(7, "Subsonic"),
    ROUTING(8, "Routing"),
    BUS_LIMITER(9, "Bus limiters"),
    MONO_BASS(10, "Mono bass"),
    VIRTUAL_CENTRE(11, "Virtual centre"),
    VIRTUAL_BASS(12, "Virtual bass"),
    MBC(13, "MBC"),
    TILT(14, "Tilt"),
    CONVOLVER(15, "Convolver IR"),
    MASTER_LIMITER(16, "Limiter"),
    DEEP_BASS(17, "Deep bass"),
    HEADROOM(18, "Headroom"),
    POST_GAIN(19, "Post gain"),
    DC_BLOCKER(20, "DC blocker"),
    MEASUREMENT_MUTE(21, "Measurement mute"),
    CHANNEL_MUTE(22, "Channel mute", neutralised = false),
    SIGNAL_GENERATOR(23, "Signal generator", neutralised = false),
    ;

    val mask: Int get() = 1 shl bit

    /** Only meaningful with the band split running: tried together with [CROSSOVER]. */
    val bandStage: Boolean
        get() = this in BAND_STAGES

    companion object {
        /** Bits 0 until this are the stages measurement neutral bypasses. */
        const val NEUTRAL_COUNT = 22
        val NEUTRAL: List<DspStage> = entries.filter { it.neutralised }
        private val BAND_STAGES = setOf(BAND_EQ, BAND_GAIN, BAND_DELAY, POLARITY, ALL_PASS, SUBSONIC, ROUTING, BUS_LIMITER, MEASUREMENT_MUTE)

        fun fromMask(mask: Int): List<DspStage> = entries.filter { mask and it.mask != 0 }
    }
}

/**
 * Measurement neutral: one switch that makes the native engine bypass every stage in
 * [DspStage.NEUTRAL] -- Pre EQ, crossovers (one full-range path), band EQ / gains / delays /
 * polarity, mono bass, the convolver IR, MBC, limiters, Deep bass, tilt and the rest -- while the
 * master volume keeps working. It is applied by native to a copy of the config
 * (NativeBmwDspProcessor::applyMeasurementNeutral()), so the saved settings are never touched and
 * switching it off brings the previous chain back exactly, also after a restart.
 *
 * While it is on, any stage can be turned back on on its own: its bit in
 * [NativeBmwDspValues.INDEX_NEUTRAL_ALLOW]. Switching neutral on or off clears those.
 */
object MeasurementNeutral {
    fun isOn(values: FloatArray): Boolean = values[NativeBmwDspValues.INDEX_MEASUREMENT_NEUTRAL] >= .5f

    fun allowMask(values: FloatArray): Int = values[NativeBmwDspValues.INDEX_NEUTRAL_ALLOW].toInt().coerceIn(0, NEUTRAL_MASK)

    fun isAllowed(values: FloatArray, stage: DspStage): Boolean = allowMask(values) and stage.mask != 0

    /** Neutral [on] or off, with no stage allowed back. */
    fun updates(on: Boolean): Map<Int, Float> = mapOf(
        NativeBmwDspValues.INDEX_MEASUREMENT_NEUTRAL to if (on) 1f else 0f,
        NativeBmwDspValues.INDEX_NEUTRAL_ALLOW to 0f,
    )

    /** [stage] allowed back on ([allowed]) or bypassed again while neutral is on. */
    fun allowUpdate(values: FloatArray, stage: DspStage, allowed: Boolean): Map<Int, Float> {
        require(stage.neutralised) { "$stage isn't in the neutral set" }
        val mask = if (allowed) allowMask(values) or stage.mask else allowMask(values) and stage.mask.inv()
        return mapOf(NativeBmwDspValues.INDEX_NEUTRAL_ALLOW to mask.toFloat())
    }

    /** The band split removed: the crossover bypass, or neutral without the crossover allowed. */
    fun crossoverBypassed(values: FloatArray): Boolean =
        values[NativeBmwDspValues.INDEX_CROSSOVER_BYPASS] >= .5f || !runs(values, DspStage.CROSSOVER)

    /** Whether [stage] runs under the config: not neutralised, or allowed back. */
    fun runs(values: FloatArray, stage: DspStage): Boolean = !isOn(values) || !stage.neutralised || isAllowed(values, stage)

    /**
     * The Kotlin mirror of NativeBmwDspProcessor::applyMeasurementNeutral(): [values] with every
     * bypassed stage's slots set to their bypass values, into [out] (a copy when neutral is off).
     * Pre EQ, band EQ and the DC blocker aren't slots: ask [runs]. Checked against the engine by
     * the golden response scenarios (ResponseGoldenAgreementTest).
     */
    fun effective(values: FloatArray, out: FloatArray = FloatArray(values.size)): FloatArray {
        values.copyInto(out)
        if (!isOn(values)) return out
        val allow = allowMask(values)
        fun bypassed(stage: DspStage) = allow and stage.mask == 0
        fun forEachOutputBase(fn: (Int) -> Unit) {
            for (o in 0 until 4) fn(S.INDEX_OUTPUT_CONFIG + o * NativeBmwDspValues.OUTPUT_CONFIG_WIDTH)
            for (o in 0 until 2) fn(S.INDEX_HIGH_OUTPUT_CONFIG + o * NativeBmwDspValues.OUTPUT_CONFIG_WIDTH)
        }
        if (bypassed(DspStage.CROSSOVER)) out[S.INDEX_CROSSOVER_BYPASS] = 1f
        if (bypassed(DspStage.BAND_GAIN)) {
            for (i in intArrayOf(S.INDEX_LOW_GAIN_L, S.INDEX_LOW_GAIN_R, S.INDEX_MID_GAIN_L, S.INDEX_MID_GAIN_R, S.INDEX_HIGH_GAIN_L, S.INDEX_HIGH_GAIN_R)) out[i] = 0f
        }
        if (bypassed(DspStage.BAND_DELAY)) {
            for (i in intArrayOf(S.INDEX_LOW_DELAY_L, S.INDEX_LOW_DELAY_R, S.INDEX_MID_DELAY_L, S.INDEX_MID_DELAY_R, S.INDEX_HIGH_DELAY_L, S.INDEX_HIGH_DELAY_R)) out[i] = 0f
        }
        if (bypassed(DspStage.POLARITY)) forEachOutputBase { out[it + NativeBmwDspValues.FIELD_INVERT] = 0f }
        if (bypassed(DspStage.SUBSONIC)) forEachOutputBase { out[it + NativeBmwDspValues.FIELD_SUBSONIC_ENABLED] = 0f }
        if (bypassed(DspStage.ALL_PASS)) {
            for (s in 0 until 8) out[S.INDEX_ALL_PASS + s * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH] = 0f
            for (s in 0 until 4) out[S.INDEX_HIGH_ALL_PASS + s * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH] = 0f
        }
        if (bypassed(DspStage.ROUTING)) {
            for (o in 0 until 4) {
                out[S.INDEX_ROUTING + o * 2] = if (o % 2 == 0) 1f else 0f
                out[S.INDEX_ROUTING + o * 2 + 1] = if (o % 2 == 0) 0f else 1f
            }
            for (o in 0 until 2) {
                out[S.INDEX_ROUTE_HIGH_LEFT_FRONT_LEFT + o * 2] = if (o == 0) 1f else 0f
                out[S.INDEX_ROUTE_HIGH_LEFT_FRONT_LEFT + o * 2 + 1] = if (o == 0) 0f else 1f
            }
        }
        if (bypassed(DspStage.BUS_LIMITER)) {
            out[S.INDEX_BUS_LIMITER_LOW_ENABLED] = 0f
            out[S.INDEX_BUS_LIMITER_MID_ENABLED] = 0f
            out[S.INDEX_BUS_LIMITER_HIGH_ENABLED] = 0f
        }
        if (bypassed(DspStage.MONO_BASS)) out[S.INDEX_MONO_BASS_ENABLED] = 0f
        if (bypassed(DspStage.VIRTUAL_CENTRE)) out[S.INDEX_VIRTUAL_ENABLED] = 0f
        if (bypassed(DspStage.VIRTUAL_BASS)) out[S.INDEX_VIRTUAL_BASS_ENABLED] = 0f
        if (bypassed(DspStage.MBC)) out[S.INDEX_MBC_ENABLED] = 0f
        if (bypassed(DspStage.TILT)) out[S.INDEX_TILT_ENABLED] = 0f
        if (bypassed(DspStage.CONVOLVER)) out[S.INDEX_CONVOLVER_ENABLED] = 0f
        if (bypassed(DspStage.MASTER_LIMITER)) out[S.INDEX_MASTER_LIMITER_ENABLED] = 0f
        if (bypassed(DspStage.DEEP_BASS)) out[S.INDEX_DEEP_BASS_ENABLED] = 0f
        if (bypassed(DspStage.HEADROOM)) out[S.INDEX_HEADROOM] = 0f
        if (bypassed(DspStage.POST_GAIN)) {
            out[S.INDEX_POST_GAIN_L] = 0f
            out[S.INDEX_POST_GAIN_R] = 0f
        }
        if (bypassed(DspStage.MEASUREMENT_MUTE)) out[S.INDEX_MEASUREMENT_MUTE] = 0f
        return out
    }

    private const val NEUTRAL_MASK = (1 shl DspStage.NEUTRAL_COUNT) - 1
}
