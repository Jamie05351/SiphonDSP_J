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
 * order exactly (see file header comment in the `dsp` package for the stage list and the
 * bugs this extraction fixes versus the earlier settings-card-only model).
 *
 * Coefficient rebuilding (the only trig-heavy work, done per biquad *section*) is gated by
 * dirty flags via [invalidateBank]/[invalidate]. Per-point complex combination reuses
 * per-axis cached cos/sin(z^-1, z^-2) and is multiply/divide only, so recomputing every
 * point on every [compute] call (regardless of dirty state) stays cheap: ~192 points x 2
 * channels x <=30 sections x ~10 flops, no trig, no allocation.
 *
 * Takes [BmwPeqState] and the 35-float native config array as plain parameters -- this
 * class does no disk I/O and has no Context dependency.
 */
class BmwResponseCalculator(private val pointCount: Int = 192) {

    enum class Stage { FULL_BANK, LOW_BRANCH, MID_BRANCH, TILT }

    /** Cheap (one extra cascade section) and closes a real sub-30Hz modelling gap; on by default. */
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

    // Precomputed per-point axis trig: z^-1 = (cosW, -sinW), z^-2 = (cos2W, -sin2W).
    private val frequencies = DoubleArray(pointCount)
    private val cosW = DoubleArray(pointCount)
    private val sinW = DoubleArray(pointCount)
    private val cos2W = DoubleArray(pointCount)
    private val sin2W = DoubleArray(pointCount)

    // One cascade per output channel per bank -- PEQ channel tagging (L/R/L+R) means the
    // two output channels generally have different section lists even though the
    // crossover portion is identical between them.
    private val fullCascade = arrayOf(BiquadCascade(20), BiquadCascade(20))
    private val lowCascade = arrayOf(BiquadCascade(22), BiquadCascade(22))
    private val midCascade = arrayOf(BiquadCascade(20), BiquadCascade(20))
    // Two sections each, mirroring NativeBmwDspProcessor's per-output all-pass stage --
    // applied after crossover+PEQ, before delay/gain (see processFrame()'s ordering).
    private val lowAllPass = arrayOf(BiquadCascade(2), BiquadCascade(2))
    private val midAllPass = arrayOf(BiquadCascade(2), BiquadCascade(2))
    private val tiltCascade = BiquadCascade(4)

    private val dirty = HashSet<Stage>().apply { addAll(Stage.entries) }

    private val preSplitAcc = arrayOf(ComplexAcc(), ComplexAcc())
    private val branchAcc = ComplexAcc()
    private val sumAcc = ComplexAcc()

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
        // Native LR4 subsonic protection: two cascaded Butterworth HPF sections.
        // It applies independently of the low-crossover bypass control.
        if (values[12] >= .5f) {
            cascade.addHighPass(values[13].toDouble(), BUTTERWORTH_Q, sampleRate)
            cascade.addHighPass(values[13].toDouble(), BUTTERWORTH_Q, sampleRate)
        }
        if (values[1] < .5f) {
            if (values[16] >= .5f) {
                cascade.addLowPass(values[15].toDouble(), BUTTERWORTH_Q, sampleRate)
                cascade.addLowPass(values[15].toDouble(), BUTTERWORTH_Q, sampleRate)
            } else {
                cascade.addLowPass(values[15].toDouble(), 1.0, sampleRate)
                cascade.addOnePoleLow(values[15].toDouble(), sampleRate)
            }
            if (peq.enabled) {
                for (band in peq.lowBandBands) if (BmwSignalChain.bandAppliesTo(band, channel)) cascade.addPeqBand(band, sampleRate)
            }
        }
        rebuildAllPassCascade(lowAllPass[channel.ordinal], values, outputOrdinal(BmwSignalChain.internalIsLeftChainFor(channel), isLow = true))
    }

    private fun rebuildMidCascade(values: FloatArray, peq: BmwPeqState, channel: BmwOutputChannel) {
        val cascade = midCascade[channel.ordinal]
        cascade.clear()
        if (values[2] < .5f) {
            cascade.addHighPass(values[18].toDouble(), BUTTERWORTH_Q, sampleRate)
            cascade.addHighPass(values[18].toDouble(), BUTTERWORTH_Q, sampleRate)
            if (peq.enabled) {
                for (band in peq.midBandBands) if (BmwSignalChain.bandAppliesTo(band, channel)) cascade.addPeqBand(band, sampleRate)
            }
        }
        rebuildAllPassCascade(midAllPass[channel.ordinal], values, outputOrdinal(BmwSignalChain.internalIsLeftChainFor(channel), isLow = false))
    }

    /** Native `NativeBmwRouting::OutputId` ordinal: LowLeft=0, LowRight=1, MidLeft=2, MidRight=3. */
    private fun outputOrdinal(internalLeft: Boolean, isLow: Boolean): Int {
        val base = if (isLow) 0 else 2
        return base + if (internalLeft) 0 else 1
    }

    private fun rebuildAllPassCascade(cascade: BiquadCascade, values: FloatArray, outputOrdinal: Int) {
        cascade.clear()
        repeat(NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT) { section ->
            val base = NativeBmwDspValues.INDEX_ALL_PASS +
                (outputOrdinal * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
            val enabled = values[base] >= .5f
            val secondOrder = values[base + 1] >= 1.5f
            val frequencyHz = values[base + 2].toDouble()
            val q = values[base + 3].toDouble()
            cascade.addAllPass(enabled, secondOrder, frequencyHz, q, sampleRate)
        }
    }

    private fun rebuildTiltCascade(values: FloatArray) {
        tiltCascade.clear()
        if (values[25] >= .5f) {
            val shelfGainDb = values[26].toDouble() * .75
            val freq = values[27].toDouble()
            repeat(2) {
                tiltCascade.addLowShelf(freq, shelfGainDb, sampleRate)
                tiltCascade.addHighShelf(freq, -shelfGainDb, sampleRate)
            }
        }
    }

    /** Recomputes only dirty stages' coefficients, then recombines every point into [out]. */
    fun compute(values: FloatArray, peq: BmwPeqState, out: BmwResponseCurves) {
        require(values.size == BmwSignalChain.VALUE_COUNT) { "expected ${BmwSignalChain.VALUE_COUNT} values, got ${values.size}" }
        rebuildAxisIfNeeded()

        val processorEnabled = values[0] >= .5f
        val lpfPass = values[1] >= .5f
        val hpfPass = values[2] >= .5f
        val bothBypassed = lpfPass && hpfPass
        val channelMuteMode = values[3].toInt()
        val measurementMute = values[4].toInt()
        val lowMuted = values[14] >= .5f || measurementMute == 1
        val midMuted = values[17] >= .5f || measurementMute == 2
        val lowInvert = values[19] >= .5f
        val midInvert = values[20] >= .5f
        val headroomLinear = dbToLinear(values[5].toDouble())
        val preampLinear = if (peq.enabled) dbToLinear(peq.preampDb.toDouble()) else 1.0

        for (channel in BmwOutputChannel.entries) {
            if (Stage.FULL_BANK in dirty) rebuildFullCascade(peq, channel)
            if (Stage.LOW_BRANCH in dirty) rebuildLowCascade(values, peq, channel)
            if (Stage.MID_BRANCH in dirty) rebuildMidCascade(values, peq, channel)
        }
        if (Stage.TILT in dirty) rebuildTiltCascade(values)
        dirty.clear()

        for (channel in BmwOutputChannel.entries) {
            val ch = channel.ordinal
            val internalLeft = BmwSignalChain.internalIsLeftChainFor(channel)
            val lowGainDb = if (internalLeft) values[6] else values[7]
            val lowDelayMs = if (internalLeft) values[23] else values[24]
            val midGainDb = if (internalLeft) values[8] else values[9]
            val midDelayMs = if (internalLeft) values[21] else values[22]
            val postGainDb = if (internalLeft) values[10] else values[11]
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

                // Pre-split: DC blocker + Full Range PEQ, then preamp, then headroom.
                val pre = preSplitAcc[ch]
                pre.setUnity()
                fullCascade[ch].accumulate(cosW[i], sinW[i], cos2W[i], sin2W[i], pre)
                pre.scale(preampLinear)
                pre.scale(headroomLinear)
                out.preSplitDb[ch][i] = pre.magnitudeDb()

                // Low branch: crossover -> branch PEQ -> all-pass -> delay -> gain -> polarity/mute,
                // matching NativeBmwDspProcessor::processFrame's per-output ordering.
                branchAcc.setFrom(pre)
                lowCascade[ch].accumulate(cosW[i], sinW[i], cos2W[i], sin2W[i], branchAcc)
                if (!lpfPass) {
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

                // Mid branch.
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

                // Sum: low+mid, or the raw pre-split signal when both crossovers are bypassed.
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
        out.lowBranchActive = !lowMuted
        out.midBranchActive = !midMuted
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
