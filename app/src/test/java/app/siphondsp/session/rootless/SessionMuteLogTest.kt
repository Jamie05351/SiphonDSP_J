package app.siphondsp.session.rootless

import app.siphondsp.session.rootless.SessionMuteLog.Kind
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
        assertEquals(180L, SessionMuteLog.snapshot().first().afterPlaybackChangeMs)
    }

    @Test fun aDumpAnswersOnlyTheChangesBeforeItStarted() {
        SessionMuteLog.notePlaybackChange(1_000) // a pause: its dump adds nothing
        SessionMuteLog.noteDumpApplied(dumpStartedAtMs = 1_010)
        SessionMuteLog.record(5_000, 7, "pkg", Kind.NEW, held = 0)
        assertNull("the pause's change must not time a later start", SessionMuteLog.snapshot().first().afterPlaybackChangeMs)

        SessionMuteLog.notePlaybackChange(6_000) // came in while a dump begun at 5 990 ran
        SessionMuteLog.noteDumpApplied(dumpStartedAtMs = 5_990)
        SessionMuteLog.record(6_200, 8, "pkg", Kind.NEW, held = 0)
        assertEquals(200L, SessionMuteLog.snapshot().first().afterPlaybackChangeMs)
    }

    @Test fun heldAndReleasedAreNeverTimedNorCountAsAStart() {
        SessionMuteLog.notePlaybackChange(1_000)
        SessionMuteLog.record(1_100, 7, "pkg", Kind.HELD, held = 1)
        SessionMuteLog.record(1_200, 7, "pkg", Kind.RELEASED, held = 0, detail = "excluded")
        SessionMuteLog.snapshot().forEach {
            assertNull(it.afterPlaybackChangeMs)
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
}
