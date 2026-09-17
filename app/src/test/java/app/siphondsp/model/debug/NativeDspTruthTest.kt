package app.siphondsp.model.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBiquadStageTest {
    private fun identity() = NativeBiquadStage(NativeBiquadStage.TOPOLOGY_SVF2, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
    private fun lowpass(a1: Double = 0.5) =
        NativeBiquadStage(NativeBiquadStage.TOPOLOGY_SVF2, a1, a1 * a1, a1 * a1 * a1, 0.0, 0.0, 1.0, 0.0)
    private fun onePole(opA: Double = 0.3) =
        NativeBiquadStage(NativeBiquadStage.TOPOLOGY_ONE_POLE_LOWPASS, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, opA)

    @Test fun identityStageIsNotActive() {
        assertTrue(identity().isIdentity)
        assertFalse(identity().isActive)
    }

    @Test fun realFilterStageIsActive() {
        assertFalse(lowpass().isIdentity)
        assertTrue(lowpass().isActive)
        assertFalse(onePole().isIdentity)
        assertTrue(onePole().isActive)
    }

    @Test fun fingerprintIsDeterministicAndDistinguishesCoefficients() {
        assertEquals(lowpass(0.5).fingerprint(), lowpass(0.5).fingerprint())
        assertNotEquals(lowpass(0.5).fingerprint(), lowpass(0.6).fingerprint())
        assertNotEquals(lowpass(0.5).fingerprint(), onePole(0.5).fingerprint())
        assertNotEquals(identity().fingerprint(), lowpass(0.5).fingerprint())
    }
}

class NativeCrossoverSnapshotTest {
    private fun stage(a1: Double) =
        NativeBiquadStage(NativeBiquadStage.TOPOLOGY_SVF2, a1, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    private fun identityStage() =
        NativeBiquadStage(NativeBiquadStage.TOPOLOGY_SVF2, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
    private fun onePoleStage(opA: Double) =
        NativeBiquadStage(NativeBiquadStage.TOPOLOGY_ONE_POLE_LOWPASS, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, opA)

    private fun crossover(type: NativeCrossoverType, stage1: NativeBiquadStage, stage2: NativeBiquadStage) =
        NativeCrossoverSnapshot(
            NativeDspOutput.LOW_LEFT, 150.0, type, false, 32.0, false, false, 0.0, 0.0, stage1, stage2,
        )

    @Test fun lr4HasTwoActiveStages() {
        val lr4 = crossover(NativeCrossoverType.LINKWITZ_RILEY4, stage(0.5), stage(0.5))
        assertEquals(2, lr4.activeStageCount)
    }

    @Test fun bw2HasOneActiveStageAndOneIdentity() {
        val bw2 = crossover(NativeCrossoverType.BUTTERWORTH2, stage(0.5), identityStage())
        assertEquals(1, bw2.activeStageCount)
    }

    @Test fun bw3HasOnePoleAndSvfBothActive() {
        val bw3 = crossover(NativeCrossoverType.BUTTERWORTH3, onePoleStage(0.3), stage(1.0))
        assertEquals(2, bw3.activeStageCount)
    }

    @Test fun bw1HasOneActiveOnePoleStageAndOneIdentity() {
        val bw1 = crossover(NativeCrossoverType.BUTTERWORTH1, onePoleStage(0.3), identityStage())
        assertEquals(1, bw1.activeStageCount)
    }

    @Test fun topologyFingerprintChangesWhenCrossoverTypeChanges() {
        // Same nominal corner, different real installed topology (as a config-type change would
        // actually produce) -- proves the fingerprint tracks the installed filter, not the enum.
        val lr4 = crossover(NativeCrossoverType.LINKWITZ_RILEY4, stage(0.5), stage(0.5))
        val bw3 = crossover(NativeCrossoverType.BUTTERWORTH3, onePoleStage(0.3), stage(1.0))
        val bw2 = crossover(NativeCrossoverType.BUTTERWORTH2, stage(0.5), identityStage())
        val bw1 = crossover(NativeCrossoverType.BUTTERWORTH1, onePoleStage(0.3), identityStage())
        val fingerprints = setOf(
            lr4.topologyFingerprint(), bw3.topologyFingerprint(), bw2.topologyFingerprint(),
            bw1.topologyFingerprint(),
        )
        assertEquals(4, fingerprints.size)
    }

    @Test fun disablingCrossoverStageChangesFingerprint() {
        val enabled = crossover(NativeCrossoverType.BUTTERWORTH2, stage(0.5), identityStage())
        val disabled = crossover(NativeCrossoverType.BUTTERWORTH2, identityStage(), identityStage())
        assertNotEquals(enabled.topologyFingerprint(), disabled.topologyFingerprint())
    }
}

class NativeConfigRevisionStatusTest {
    private fun status(
        requestedDsp: Long = 0, activeDsp: Long = 0, lastDspSuccess: Boolean? = null,
        requestedPeq: Long = 0, activePeq: Long = 0, lastPeqSuccess: Boolean? = null,
    ) = app.siphondsp.interop.NativeConfigRevisionStatus(
        requestedDspRevision = requestedDsp, nativeActiveDspRevision = activeDsp,
        requestedPeqRevision = requestedPeq, nativeActivePeqRevision = activePeq,
        lastDspApplySuccess = lastDspSuccess, lastPeqApplySuccess = lastPeqSuccess,
        lastDspFailure = null, lastPeqFailure = null,
        dspRevisionRequestedAtMs = 1_000L, peqRevisionRequestedAtMs = 1_000L,
    )

    @Test fun neverConfiguredIsUninitializedNotMatch() {
        val s = status()
        assertEquals(
            app.siphondsp.interop.NativeConfigRevisionStatus.SyncState.UNINITIALIZED, s.dspSyncState,
        )
        assertEquals(
            app.siphondsp.interop.NativeConfigRevisionStatus.SyncState.UNINITIALIZED, s.peqSyncState,
        )
    }

    @Test fun matchingRevisionsAfterSuccessIsMatch() {
        val s = status(requestedDsp = 3, activeDsp = 3, lastDspSuccess = true)
        assertEquals(app.siphondsp.interop.NativeConfigRevisionStatus.SyncState.MATCH, s.dspSyncState)
    }

    @Test fun mismatchedRevisionsWithNoFailureIsStaleNotError() {
        // In-flight: requested advanced but native hasn't acknowledged yet, no failure recorded.
        val s = status(requestedDsp = 4, activeDsp = 3, lastDspSuccess = null)
        assertEquals(app.siphondsp.interop.NativeConfigRevisionStatus.SyncState.STALE, s.dspSyncState)
    }

    @Test fun explicitFailureIsErrorEvenThoughStructurallyAlsoAMismatch() {
        val s = status(requestedDsp = 4, activeDsp = 3, lastDspSuccess = false)
        assertEquals(app.siphondsp.interop.NativeConfigRevisionStatus.SyncState.ERROR, s.dspSyncState)
    }

    @Test fun failedConfigurationNeverReportsRequestedAsActive() {
        // The whole point of the ack mechanism: a rejected/failed configure() must never advance
        // nativeActiveDspRevision to the requested value. This is a Kotlin-level sanity check that
        // ERROR and MATCH are mutually exclusive outcomes of the same status object.
        val s = status(requestedDsp = 5, activeDsp = 4, lastDspSuccess = false)
        assertNotEquals(app.siphondsp.interop.NativeConfigRevisionStatus.SyncState.MATCH, s.dspSyncState)
        assertEquals(app.siphondsp.interop.NativeConfigRevisionStatus.SyncState.ERROR, s.dspSyncState)
    }

    @Test fun mismatchDurationOnlyReportedWhileNotMatched() {
        val matched = status(requestedDsp = 3, activeDsp = 3, lastDspSuccess = true)
        assertNull(matched.dspMismatchDurationMs(nowMs = 5_000L))
        val stale = status(requestedDsp = 4, activeDsp = 3)
        assertEquals(4_000L, stale.dspMismatchDurationMs(nowMs = 5_000L))
    }
}

class ParseNativeTruthSnapshotTest {
    private fun stage(topology: Int = 0, a1: Double = 0.0): DoubleArray =
        doubleArrayOf(topology.toDouble(), a1, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)

    private fun outputBlock(
        freq: Double = 150.0, type: Double = 2.0, subsonicEnabled: Double = 0.0,
        subsonicFreq: Double = 32.0, muted: Double = 0.0, polarityInverted: Double = 0.0,
        gainDb: Double = 0.0, delayMs: Double = 0.0, stage1: DoubleArray = stage(),
        stage2: DoubleArray = stage(),
    ): DoubleArray = doubleArrayOf(
        freq, type, subsonicEnabled, subsonicFreq, muted, polarityInverted, gainDb, delayMs,
    ) + stage1 + stage2

    private fun emptyBank(): DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)

    private fun buildArray(peqEnabled: Boolean = false, fullBank: DoubleArray = emptyBank()): DoubleArray {
        val header = doubleArrayOf(48000.0, if (peqEnabled) 1.0 else 0.0, 0.0)
        val outputs = outputBlock() + outputBlock() + outputBlock() + outputBlock()
        val low = emptyBank()
        val mid = emptyBank()
        return header + outputs + fullBank + low + mid
    }

    @Test fun parsesSampleRateAndOutputCount() {
        val snapshot = parseNativeTruthSnapshot(buildArray())
        assertNotNull(snapshot)
        assertEquals(48000.0, snapshot!!.sampleRate, 0.0)
        assertEquals(4, snapshot.crossovers.size)
    }

    @Test fun nullOnTooShortArray() {
        assertNull(parseNativeTruthSnapshot(doubleArrayOf(1.0, 2.0)))
    }

    @Test fun nullOnNullInput() {
        assertNull(parseNativeTruthSnapshot(null))
    }

    @Test fun parsesConfiguredPeqBands() {
        // One active band (gain != 0) in the full-range bank.
        val band = doubleArrayOf(1000.0, 3.0, 0.7, 0.0, 0.0, 1.0)
        val fullBank = doubleArrayOf(1.0, 1.0, 1.0) + band
        val snapshot = parseNativeTruthSnapshot(buildArray(peqEnabled = true, fullBank = fullBank))
        assertNotNull(snapshot)
        assertTrue(snapshot!!.peq.enabled)
        assertEquals(1, snapshot.peq.full.bands.size)
        val parsedBand = snapshot.peq.full.bands[0]
        assertEquals(1000.0, parsedBand.frequencyHz, 0.0)
        assertEquals(3.0, parsedBand.gainDb, 0.0)
        assertTrue(parsedBand.active)
    }

    @Test fun skippedNearZeroGainBandReportedAsInactive() {
        // type 0 (peak/bell), gain ~0 -- configurePeqLocked's build() drops this as a no-op, so
        // native's own peqBandSkipped() would have written the trailing "active" field as 0 here.
        val band = doubleArrayOf(1000.0, 0.0, 0.7, 0.0, 0.0, 0.0)
        val fullBank = doubleArrayOf(1.0, 0.0, 0.0) + band
        val snapshot = parseNativeTruthSnapshot(buildArray(fullBank = fullBank))
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.peq.full.bands.size)
        assertFalse(snapshot.peq.full.bands[0].active)
    }
}
