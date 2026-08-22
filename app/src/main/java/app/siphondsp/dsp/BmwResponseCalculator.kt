package app.siphondsp.dsp

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Incremental, allocation-free-after-warm-up evaluator of the complete native BMW signal
 * chain's frequency response, mirroring `NativeBmwDspProcessor.cpp`'s `processFrame` stage
 * order exactly for the linear/time-invariant stages represented by the graph.
 */
class BmwResponseCalculator(private val pointCount: Int = 192) {

    enum class Stage { FULL_BANK, LOW_BRANCH, MID_BRANCH, TILT }

    var includeDcBlocker: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate(Stage.FULL_BANK)
        }

    private var sampleRate = 48_000.0
    private var minFrequency = 20.0
    private var maxFrequency = 20_000.0
    private var axisDirty = true

    private val frequencies = DoubleArray(pointCount)
    private val cosW = DoubleArray(pointCount)
    private val sinW = DoubleArray(pointCount)
    private val cos2W = DoubleArray(pointCount)
    private val sin2W = DoubleArray(pointCount)

    private val fullCascade = arrayOf(BiquadCascade(20), BiquadCascade(20))
    private val lowCascade = arrayOf(BiquadCascade(22), BiquadCascade(22))
    private val midCascade = arrayOf(BiquadCascade(20), BiquadCascade(20))
    private val lowAllPass = arrayOf(BiquadCascade(2), BiquadCascade(2))
    private val midAllPass = arrayOf(BiquadCascade(2), BiquadCascade(2))
    private val tiltCascade = BiquadCascade(4)

    // Mono Bass's own LR4 (2 cascaded 2nd-order sections each) low/high split, mirroring
    // NativeBmwDspProcessor::rebuildMonoBass exactly (BUTTERWORTH_Q sections at monoBassFreq).
    // Shared per-channel like every other cascade here, under the same L≈R reference-input
    // assumption the rest of this calculator already makes -- see applyMonoBass for why that
    // assumption is what lets this be folded into a single per-point complex multiply instead of
    // needing a genuinely stereo (L+R-coupled) model.
    private val monoBassLpf = arrayOf(BiquadCascade(2), BiquadCascade(2))
    private val monoBassHpf = arrayOf(BiquadCascade(2), BiquadCascade(2))

    private val dirty = HashSet<Stage>().apply { addAll(Stage.entries) }

    private val preSplitAcc = arrayOf(ComplexAcc(), ComplexAcc())
    private val branchAcc = ComplexAcc()
    private val sumAcc = ComplexAcc()
    private val monoBassLpfAcc = ComplexAcc()
    private val monoBassHpfAcc = ComplexAcc()

    fun configureAxis(sampleRate: Double, minFrequency: Double, maxFrequency: Double) {
        if (this.sampleRate == sampleRate && this.minFrequency == minFrequency && this.maxFrequency == maxFrequency) return
        this.sampleRate = sampleRate
        this.minFrequency = minFrequency
        this.maxFrequency = maxFrequency
        axisDirty = true
        invalidateAll()
    }

    fun invalidateAll() {
        dirty.addAll(Stage.entries)
    }

    fun invalidate(stage: Stage) {
        dirty.add(stage)
    }

    fun invalidateBank(bank: BmwPeqBank) {
        when (bank) {
            BmwPeqBank.FULL -> dirty.add(Stage.FULL_BANK)
            BmwPeqBank.LOW -> dirty.add(Stage.LOW_BRANCH)
            BmwPeqBank.MID -> dirty.add(Stage.MID_BRANCH)
        }
    }

    private fun rebuildAxisIfNeeded() {
        if (!axisDirty) return
        val logMin = log10(minFrequency)
        val logMax = log10(maxFrequency.coerceAtMost(sampleRate * 0.5 * 0.999))
        val span = logMax - logMin
        for (i in 0 until pointCount) {
            val fraction = if (pointCount == 1) 0.0 else i.toDouble() / (pointCount - 1)
            val f = 10.0.pow(logMin + fraction * span)
            frequencies[i] = f
            val w = 2.0 * PI * f / sampleRate
            val cw = cos(w)
            val sw = sin(w)
            cosW[i] = cw
            sinW[i] = sw
            cos2W[i] = 2.0 * cw * cw - 1.0
            sin2W[i] = 2.0 * sw * cw
        }
        axisDirty = false
    }

    private fun rebuildFullCascade(peq: BmwPeqState, channel: BmwOutputChannel) {
        val cascade = fullCascade[channel.ordinal]
        cascade.clear()
        if (includeDcBlocker) cascade.addDcBlocker(DC_BLOCKER_HZ, sampleRate)
        if (peq.enabled) {
            for (band in peq.fullRangeBands) {
                if (BmwSignalChain.bandAppliesTo(band, channel)) cascade.addPeqBand(band, sampleRate)
            }
        }
    }

    private fun rebuildLowCascade(values: FloatArray, peq: BmwPeqState, channel: BmwOutputChannel) {
        val cascade = lowCascade[channel.ordinal]
        cascade.clear()
        val output = outputOrdinal(BmwSignalChain.internalIsLeftChainFor(channel), isLow = true)
        if (values[NativeBmwDspValues.INDEX_LPF_PASS] < .5f) {
            val subsonicEnabled = outputValue(values, output, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED) >= .5f
            val subsonicFreq = outputValue(values, output, NativeBmwDspValues.FIELD_SUBSONIC_FREQ).toDouble()
            if (subsonicEnabled) {
                cascade.addHighPass(subsonicFreq, BUTTERWORTH_Q, sampleRate)
            }

            // Always LR4 now -- the 18dB/oct option was removed (see
            // NativeBmwDspProcessor::rebuildLowCrossover/processLowCrossover).
            val crossoverFreq = outputValue(values, output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ).toDouble()
            cascade.addLowPass(crossoverFreq, BUTTERWORTH_Q, sampleRate)
            cascade.addLowPass(crossoverFreq, BUTTERWORTH_Q, sampleRate)
            if (peq.enabled) {
                for (band in peq.lowBandBands) if (BmwSignalChain.bandAppliesTo(band, channel)) cascade.addPeqBand(band, sampleRate)
            }
        }
        rebuildAllPassCascade(lowAllPass[channel.ordinal], values, output)
        rebuildMonoBassCascades(values, channel)
    }

    private fun rebuildMonoBassCascades(values: FloatArray, channel: BmwOutputChannel) {
        val freq = values[NativeBmwDspValues.INDEX_MONO_BASS_FREQ].toDouble()
        val lpf = monoBassLpf[channel.ordinal]
        lpf.clear()
        lpf.addLowPass(freq, BUTTERWORTH_Q, sampleRate)
        lpf.addLowPass(freq, BUTTERWORTH_Q, sampleRate)
        val hpf = monoBassHpf[channel.ordinal]
        hpf.clear()
        hpf.addHighPass(freq, BUTTERWORTH_Q, sampleRate)
        hpf.addHighPass(freq, BUTTERWORTH_Q, sampleRate)
    }

    /**
     * Mono Bass blends `branchAcc` (the low path so far) with a mono-summed, LR4-lowpassed copy
     * plus an LR4-highpassed copy of itself: `out = dry*(1-blend) + (LP(mono)*makeup + HP(dry))*
     * blend`, mirroring NativeBmwDspProcessor.cpp's per-sample math in
     * `processFrame`/`rebuildMonoBass` exactly.
     *
     * `mono = (lowL+lowR)*0.5` is a genuinely stereo, L+R-coupled quantity that a single-channel
     * frequency-response model has no way to represent on its own -- so this reuses the same
     * simplifying assumption every other stage in this calculator already makes for a reference
     * response curve: treat L and R as carrying identical content, so `mono ≈ dry`. Once L≈R,
     * `LP(mono)*makeup + HP(dry)` collapses to `(LP*makeup + HP)` applied to the *same* `dry`
     * value already in `branchAcc`, and because every stage involved (LP, HP, the dry
     * passthrough) is linear, the whole blend is just one more complex multiply:
     * `branchAcc *= (1-blend) + LP_transfer*makeup + HP_transfer*blend`. That transfer factor
     * doesn't depend on anything upstream or downstream of it, so applying it here (right after
     * the crossover, before this bank's own PEQ bands) versus wherever the native chain actually
     * inserts it makes no difference to the final magnitude -- cascaded multiplications commute.
     */
    private fun applyMonoBass(branchAcc: ComplexAcc, values: FloatArray, channel: BmwOutputChannel, cosW: Double, sinW: Double, cos2W: Double, sin2W: Double) {
        if (values[NativeBmwDspValues.INDEX_MONO_BASS_ENABLED] < .5f) return
        val blend = values[NativeBmwDspValues.INDEX_MONO_BASS_BLEND].toDouble() * .01
        if (blend <= 0.0) return
        val makeupLin = dbToLinear(values[NativeBmwDspValues.INDEX_MONO_BASS_MAKEUP].toDouble())

        monoBassLpfAcc.setUnity()
        monoBassLpf[channel.ordinal].accumulate(cosW, sinW, cos2W, sin2W, monoBassLpfAcc)
        monoBassHpfAcc.setUnity()
        monoBassHpf[channel.ordinal].accumulate(cosW, sinW, cos2W, sin2W, monoBassHpfAcc)

        val transferRe = (1.0 - blend) + monoBassLpfAcc.re * makeupLin * blend + monoBassHpfAcc.re * blend
        val transferIm = monoBassLpfAcc.im * makeupLin * blend + monoBassHpfAcc.im * blend
        branchAcc.mul(transferRe, transferIm)
    }

    private fun rebuildMidCascade(values: FloatArray, peq: BmwPeqState, channel: BmwOutputChannel) {
        val cascade = midCascade[channel.ordinal]
        cascade.clear()
        val output = outputOrdinal(BmwSignalChain.internalIsLeftChainFor(channel), isLow = false)
        if (values[NativeBmwDspValues.INDEX_HPF_PASS] < .5f) {
            val crossoverFreq = outputValue(values, output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ).toDouble()
            cascade.addHighPass(crossoverFreq, BUTTERWORTH_Q, sampleRate)
            cascade.addHighPass(crossoverFreq, BUTTERWORTH_Q, sampleRate)
            if (peq.enabled) {
                for (band in peq.midBandBands) if (BmwSignalChain.bandAppliesTo(band, channel)) cascade.addPeqBand(band, sampleRate)
            }
        }
        rebuildAllPassCascade(midAllPass[channel.ordinal], values, output)
    }

    /** Native OutputId ordinal: LowLeft=0, LowRight=1, MidLeft=2, MidRight=3. */
    private fun outputOrdinal(internalLeft: Boolean, isLow: Boolean): Int {
        val base = if (isLow) 0 else 2
        return base + if (internalLeft) 0 else 1
    }

    private fun outputValue(values: FloatArray, outputOrdinal: Int, field: Int): Float =
        values[NativeBmwDspValues.outputIndex(outputOrdinal, field)]

    private fun rebuildAllPassCascade(cascade: BiquadCascade, values: FloatArray, outputOrdinal: Int) {
        cascade.clear()
        repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
            val base = NativeBmwDspValues.INDEX_ALL_PASS +
                (outputOrdinal * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
            cascade.addAllPass(
                enabled = values[base] >= .5f,
                secondOrder = values[base + 1] >= 1.5f,
                frequencyHz = values[base + 2].toDouble(),
                q = values[base + 3].toDouble(),
                sampleRate = sampleRate,
            )
        }
    }

    private fun rebuildTiltCascade(values: FloatArray) {
        tiltCascade.clear()
        if (values[NativeBmwDspValues.INDEX_TILT_ENABLED] >= .5f) {
            val shelfGainDb = values[NativeBmwDspValues.INDEX_TILT_AMOUNT].toDouble() * .75
            val freq = values[NativeBmwDspValues.INDEX_TILT_FREQ].toDouble()
            repeat(2) {
                tiltCascade.addLowShelf(freq, shelfGainDb, sampleRate)
                tiltCascade.addHighShelf(freq, -shelfGainDb, sampleRate)
            }
        }
    }

    fun compute(values: FloatArray, peq: BmwPeqState, out: BmwResponseCurves) {
        require(values.size == BmwSignalChain.VALUE_COUNT) { "expected ${BmwSignalChain.VALUE_COUNT} values, got ${values.size}" }
        rebuildAxisIfNeeded()

        val processorEnabled = values[NativeBmwDspValues.INDEX_ENABLED] >= .5f
        val lpfPass = values[NativeBmwDspValues.INDEX_LPF_PASS] >= .5f
        val hpfPass = values[NativeBmwDspValues.INDEX_HPF_PASS] >= .5f
        val bothBypassed = lpfPass && hpfPass
        val channelMuteMode = values[NativeBmwDspValues.INDEX_CHANNEL_MUTE].toInt()
        val measurementMute = values[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE].toInt()
        val headroomLinear = dbToLinear(values[NativeBmwDspValues.INDEX_HEADROOM].toDouble())
        val preampLinear = if (peq.enabled) dbToLinear(peq.preampDb.toDouble()) else 1.0

        for (channel in BmwOutputChannel.entries) {
            if (Stage.FULL_BANK in dirty) rebuildFullCascade(peq, channel)
            if (Stage.LOW_BRANCH in dirty) rebuildLowCascade(values, peq, channel)
            if (Stage.MID_BRANCH in dirty) rebuildMidCascade(values, peq, channel)
        }
        if (Stage.TILT in dirty) rebuildTiltCascade(values)
        dirty.clear()

        var anyLowActive = false
        var anyMidActive = false

        for (channel in BmwOutputChannel.entries) {
            val ch = channel.ordinal
            val internalLeft = BmwSignalChain.internalIsLeftChainFor(channel)
            val lowOutput = outputOrdinal(internalLeft, isLow = true)
            val midOutput = outputOrdinal(internalLeft, isLow = false)

            val lowGainDb = if (internalLeft) values[NativeBmwDspValues.INDEX_LOW_GAIN_L] else values[NativeBmwDspValues.INDEX_LOW_GAIN_R]
            val lowDelayMs = if (internalLeft) values[NativeBmwDspValues.INDEX_LOW_DELAY_L] else values[NativeBmwDspValues.INDEX_LOW_DELAY_R]
            val midGainDb = if (internalLeft) values[NativeBmwDspValues.INDEX_MID_GAIN_L] else values[NativeBmwDspValues.INDEX_MID_GAIN_R]
            val midDelayMs = if (internalLeft) values[NativeBmwDspValues.INDEX_MID_DELAY_L] else values[NativeBmwDspValues.INDEX_MID_DELAY_R]
            val postGainDb = if (internalLeft) values[NativeBmwDspValues.INDEX_POST_GAIN_L] else values[NativeBmwDspValues.INDEX_POST_GAIN_R]

            val lowMuted = outputValue(values, lowOutput, NativeBmwDspValues.FIELD_MUTE) >= .5f || measurementMute == 1
            val midMuted = outputValue(values, midOutput, NativeBmwDspValues.FIELD_MUTE) >= .5f || measurementMute == 2
            val lowInvert = outputValue(values, lowOutput, NativeBmwDspValues.FIELD_INVERT) >= .5f
            val midInvert = outputValue(values, midOutput, NativeBmwDspValues.FIELD_INVERT) >= .5f
            anyLowActive = anyLowActive || !lowMuted
            anyMidActive = anyMidActive || !midMuted

            val muteThisOutput = (channelMuteMode == 1 && channel == BmwOutputChannel.RIGHT) ||
                (channelMuteMode == 2 && channel == BmwOutputChannel.LEFT)

            for (i in 0 until pointCount) {
                out.frequencies[i] = frequencies[i]

                if (!processorEnabled) {
                    out.preSplitDb[ch][i] = 0.0
                    out.lowBranchDb[ch][i] = 0.0
                    out.midBranchDb[ch][i] = 0.0
                    out.sumDb[ch][i] = 0.0
                    out.lowBranchPhase[ch][i] = 0.0
                    out.midBranchPhase[ch][i] = 0.0
                    out.sumPhase[ch][i] = 0.0
                    continue
                }

                val pre = preSplitAcc[ch]
                pre.setUnity()
                fullCascade[ch].accumulate(cosW[i], sinW[i], cos2W[i], sin2W[i], pre)
                pre.scale(preampLinear)
                pre.scale(headroomLinear)
                out.preSplitDb[ch][i] = pre.magnitudeDb()

                branchAcc.setFrom(pre)
                lowCascade[ch].accumulate(cosW[i], sinW[i], cos2W[i], sin2W[i], branchAcc)
                if (!lpfPass) {
                    applyMonoBass(branchAcc, values, channel, cosW[i], sinW[i], cos2W[i], sin2W[i])
                    lowAllPass[ch].accumulate(cosW[i], sinW[i], cos2W[i], sin2W[i], branchAcc)
                    applyDelay(branchAcc, frequencies[i], lowDelayMs.toDouble())
                    branchAcc.scale(dbToLinear(lowGainDb.toDouble()))
                }
                if (lowInvert) branchAcc.scale(-1.0)
                if (lowMuted) branchAcc.setZero()
                out.lowBranchDb[ch][i] = branchAcc.magnitudeDb()
                out.lowBranchPhase[ch][i] = branchAcc.phase()
                val lowRe = branchAcc.re
                val lowIm = branchAcc.im

                branchAcc.setFrom(pre)
                midCascade[ch].accumulate(cosW[i], sinW[i], cos2W[i], sin2W[i], branchAcc)
                if (!hpfPass) {
                    midAllPass[ch].accumulate(cosW[i], sinW[i], cos2W[i], sin2W[i], branchAcc)
                    applyDelay(branchAcc, frequencies[i], midDelayMs.toDouble())
                    branchAcc.scale(dbToLinear(midGainDb.toDouble()))
                }
                if (midInvert) branchAcc.scale(-1.0)
                if (midMuted) branchAcc.setZero()
                out.midBranchDb[ch][i] = branchAcc.magnitudeDb()
                out.midBranchPhase[ch][i] = branchAcc.phase()

                if (bothBypassed) {
                    sumAcc.setFrom(pre)
                } else {
                    sumAcc.re = lowRe + branchAcc.re
                    sumAcc.im = lowIm + branchAcc.im
                }

                tiltCascade.accumulate(cosW[i], sinW[i], cos2W[i], sin2W[i], sumAcc)
                sumAcc.scale(dbToLinear(postGainDb.toDouble()))
                if (muteThisOutput) sumAcc.setZero()
                out.sumDb[ch][i] = sumAcc.magnitudeDb()
                out.sumPhase[ch][i] = sumAcc.phase()
            }
        }

        out.processorEnabled = processorEnabled
        out.lowBranchActive = anyLowActive
        out.midBranchActive = anyMidActive
        out.bothCrossoversBypassed = bothBypassed
    }

    private fun applyDelay(acc: ComplexAcc, frequencyHz: Double, delayMs: Double) {
        if (delayMs <= 0.0) return
        val angle = -2.0 * PI * frequencyHz * delayMs / 1000.0
        acc.mul(cos(angle), sin(angle))
    }

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    companion object {
        private const val BUTTERWORTH_Q = .7071067812
        private const val DC_BLOCKER_HZ = 10.0
    }
}
