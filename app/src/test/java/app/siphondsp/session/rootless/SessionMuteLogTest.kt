package app.siphondsp.session.rootless

import app.siphondsp.session.rootless.SessionMuteLog.Kind
import app.siphondsp.session.rootless.SessionMuteLog.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionMuteLogTest {
    @Before fun setUp() = SessionMuteLog.clear()

    @Test fun startIsTimedFromTheFirstUnansweredPlaybackChange() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.notePlaybackChange(1_050) // a second change doesn't restart the clock
        SessionMuteLog.record(1_180, 7, "com.spotify.music", Kind.NEW, held = 0)
        assertEquals(180L, SessionMuteLog.snapshot().first().unmutedMs)
    }

    @Test fun aDumpAnswersOnlyTheChangesBeforeItStarted() {
        SessionMuteLog.notePlaybackChange(1_000) // a pause: its dump adds nothing
        SessionMuteLog.noteDumpApplied(dumpStartedAtMs = 1_010)
        SessionMuteLog.record(5_000, 7, "pkg", Kind.NEW, held = 0)
        assertNull("the pause's change must not time a later start", SessionMuteLog.snapshot().first().unmutedMs)

        SessionMuteLog.notePlaybackChange(6_000) // came in while a dump begun at 5 990 ran
        SessionMuteLog.noteDumpApplied(dumpStartedAtMs = 5_990)
        SessionMuteLog.record(6_200, 8, "pkg", Kind.NEW, held = 0)
        assertEquals(200L, SessionMuteLog.snapshot().first().unmutedMs)
    }

    @Test fun heldAndReleasedAreNeverTimedNorCountAsAStart() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(1_100, 7, "pkg", Kind.HELD, held = 1)
        SessionMuteLog.record(1_200, 7, "pkg", Kind.RELEASED, held = 0, detail = "excluded")
        SessionMuteLog.snapshot().forEach {
            assertNull(it.unmutedMs)
            assertFalse(it.isStart)
        }
        SessionMuteLog.record(1_300, 7, "pkg", Kind.NEW, held = 0)
        assertFalse("only a start marks an id as seen", SessionMuteLog.snapshot().first().seenBefore)
    }

    @Test fun seenBeforeMarksAnIdThatStartedEarlier() {
        SessionMuteLog.record(1_000, 7, "pkg", Kind.NEW, held = 0)
        SessionMuteLog.record(2_000, 7, "pkg", Kind.HELD, held = 1)
        SessionMuteLog.record(3_000, 7, "pkg", Kind.KEPT, held = 0)
        SessionMuteLog.record(4_000, 9, "pkg", Kind.NEW, held = 0)
        val (newest, kept) = SessionMuteLog.snapshot()
        assertFalse(newest.seenBefore)
        assertTrue(kept.seenBefore)
        assertEquals(Kind.KEPT, kept.kind)
    }

    @Test fun keepsOnlyTheNewestEvents() {
        repeat(SessionMuteLog.EVENTS + 5) { SessionMuteLog.record(it.toLong(), it, "pkg", Kind.NEW, held = 0) }
        val events = SessionMuteLog.snapshot()
        assertEquals(SessionMuteLog.EVENTS, events.size)
        assertEquals(SessionMuteLog.EVENTS + 4, events.first().sessionId)
    }

    @Test fun aMuteAttachedBeforeTheStartReadsZero() {
        // Armed on the session-open broadcast, then the player starts, then the dump confirms.
        SessionMuteLog.record(900, 7, "pkg", Kind.ARMED, held = 1, trigger = Trigger.BROADCAST, attachedAtMs = 900)
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(1_400, 7, "pkg", Kind.KEPT, held = 0, trigger = Trigger.BROADCAST, attachedAtMs = 900)
        val (kept, armed) = SessionMuteLog.snapshot()
        assertEquals(0L, armed.unmutedMs)
        assertEquals(0L, kept.unmutedMs)
        assertEquals(Trigger.BROADCAST, kept.trigger)
        assertFalse(armed.isStart)
    }

    @Test fun aMuteArmedOnTheCallbackIsTimedFromTheStartNotTheDump() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(1_004, 7, "pkg", Kind.ARMED, held = 1, trigger = Trigger.CALLBACK, attachedAtMs = 1_004)
        // The dump that confirms it lands 600 ms later: the window is still the arm's 4 ms.
        SessionMuteLog.record(1_620, 7, "pkg", Kind.KEPT, held = 0, trigger = Trigger.CALLBACK, attachedAtMs = 1_004)
        val (kept, armed) = SessionMuteLog.snapshot()
        assertEquals(4L, armed.unmutedMs)
        assertEquals(4L, kept.unmutedMs)
        assertTrue(kept.isStart)
    }

    @Test fun aMuteHeldFromTheLastPlayReadsZero() {
        SessionMuteLog.notePlaybackChange(5_000)
        SessionMuteLog.record(5_300, 7, "pkg", Kind.KEPT, held = 0, trigger = Trigger.HELD, attachedAtMs = 0)
        assertEquals(0L, SessionMuteLog.snapshot().first().unmutedMs)
    }

    @Test fun aFailedStartHasNoWindow() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(1_100, 7, "pkg", Kind.FAILED, held = 0)
        assertNull(SessionMuteLog.snapshot().first().unmutedMs)
    }

    @Test fun aFailedEarlyArmIsVisibleWithoutInventingAPlaybackStart() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(1_004, 7, "pkg", Kind.ARM_FAILED, held = 0,
            detail = "early mute creation failed", trigger = Trigger.CALLBACK)
        val failed = SessionMuteLog.snapshot().first()
        assertNull(failed.unmutedMs)
        assertFalse(failed.isStart)
        assertFalse(SessionMuteLog.wasSeen(7))
        assertEquals(Trigger.CALLBACK, failed.trigger)
        // The failed attempt doesn't consume the pending start; a retry reports its actual delay.
        SessionMuteLog.record(1_010, 7, "pkg", Kind.ARMED, held = 1,
            trigger = Trigger.CALLBACK, attachedAtMs = 1_010)
        assertEquals(10L, SessionMuteLog.snapshot().first().unmutedMs)
    }
    @Test fun staleUnansweredStartDoesNotTimeAnUnrelatedBroadcastArm() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(10_000, 7, "pkg", Kind.ARMED, held = 1,
            trigger = Trigger.BROADCAST, attachedAtMs = 10_000)
        assertNull(SessionMuteLog.snapshot().first().unmutedMs)
        SessionMuteLog.record(10_100, 7, "pkg", Kind.KEPT, held = 0,
            trigger = Trigger.BROADCAST, attachedAtMs = 10_000)
        assertNull(SessionMuteLog.snapshot().first().unmutedMs)
    }

    @Test fun aNewStartReplacesAnExpiredUnansweredStart() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.notePlaybackChange(10_000)
        SessionMuteLog.noteDumpApplied(9_900) // an older dump must not clear the new start
        SessionMuteLog.record(10_004, 7, "pkg", Kind.ARMED, held = 1,
            trigger = Trigger.CALLBACK, attachedAtMs = 10_004)
        assertEquals(4L, SessionMuteLog.snapshot().first().unmutedMs)
    }

    @Test fun aHeldMuteStillReadsZeroAfterAnExpiredStart() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(10_000, 7, "pkg", Kind.KEPT, held = 0,
            trigger = Trigger.HELD, attachedAtMs = 0)
        assertEquals(0L, SessionMuteLog.snapshot().first().unmutedMs)
    }

    @Test fun aNewSessionShowsWhereItsWindowWent() {
        SessionMuteLog.notePlaybackChange(1_000)
        assertTrue(SessionMuteLog.hasPendingStart(1_005))
        SessionMuteLog.beginLookup(SessionMuteLog.Lookup("dump", startedAtMs = 1_002, endedAtMs = 1_070, detail = "AudioPolicyService 68 ms"))
        SessionMuteLog.record(1_085, 7, "pkg", Kind.NEW, held = 0, trigger = Trigger.DUMP)
        SessionMuteLog.endLookup()
        SessionMuteLog.noteDumpApplied(1_002)
        assertFalse(SessionMuteLog.hasPendingStart(1_090))
        val e = SessionMuteLog.snapshot().first()
        assertEquals(85L, e.unmutedMs) // from the callback, not from the dump finding it
        val t = e.timing!!
        assertEquals("dump", t.path)
        assertEquals(2L, t.callbackToLookupMs)
        assertEquals(68L, t.lookupMs)
        assertEquals(15L, t.lookupToMuteMs)
        assertEquals("AudioPolicyService 68 ms", t.detail)
    }

    @Test fun aKeptMuteAndAnEventOutsideALookupCarryNoTiming() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(1_004, 7, "pkg", Kind.ARMED, held = 1, trigger = Trigger.CALLBACK, attachedAtMs = 1_004)
        SessionMuteLog.beginLookup(SessionMuteLog.Lookup("dump", 1_001, 1_060))
        SessionMuteLog.record(1_062, 7, "pkg", Kind.KEPT, held = 0, trigger = Trigger.CALLBACK, attachedAtMs = 1_004)
        SessionMuteLog.endLookup()
        SessionMuteLog.snapshot().forEach { assertNull(it.timing) }
    }

    @Test fun aFailedMuteHasNoLookupToMute() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.beginLookup(SessionMuteLog.Lookup("dump", 1_010, 1_090))
        SessionMuteLog.record(1_095, 7, "pkg", Kind.FAILED, held = 0)
        SessionMuteLog.endLookup()
        val t = SessionMuteLog.snapshot().first().timing!!
        assertEquals(10L, t.callbackToLookupMs)
        assertNull(t.lookupToMuteMs)
    }

    @Test fun aStartDuringADumpStillNeedsADumpAndKeepsItsOwnWindow() {
        SessionMuteLog.notePlaybackChange(100) // A
        SessionMuteLog.notePlaybackChange(120) // B, while A's dump (begun at 101) runs
        SessionMuteLog.record(170, 7, "a", Kind.NEW, held = 0)
        SessionMuteLog.noteDumpApplied(dumpStartedAtMs = 101)
        assertEquals(70L, SessionMuteLog.snapshot().first().unmutedMs)
        assertTrue("B is not answered by a dump begun before it", SessionMuteLog.hasPendingStart(175))
        SessionMuteLog.record(240, 8, "b", Kind.NEW, held = 0)
        SessionMuteLog.noteDumpApplied(dumpStartedAtMs = 180)
        assertEquals(120L, SessionMuteLog.snapshot().first().unmutedMs) // timed from B, not A
        assertFalse(SessionMuteLog.hasPendingStart(245))
    }

    @Test fun aLookupAlreadyRunningAtTheStartReadsZeroInFlight() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.beginLookup(SessionMuteLog.Lookup("dump", startedAtMs = 980, endedAtMs = 1_050))
        SessionMuteLog.record(1_060, 7, "pkg", Kind.NEW, held = 0, trigger = Trigger.DUMP)
        SessionMuteLog.endLookup()
        val t = SessionMuteLog.snapshot().first().timing!!
        assertEquals(0L, t.callbackToLookupMs)
        assertTrue(t.lookupInFlight)
        assertEquals(60L, SessionMuteLog.snapshot().first().unmutedMs)
    }
}
