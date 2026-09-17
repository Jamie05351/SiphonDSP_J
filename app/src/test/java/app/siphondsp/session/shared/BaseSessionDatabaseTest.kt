package app.siphondsp.session.shared

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.model.AudioSessionDumpEntry
import app.siphondsp.model.IEffectSession
import app.siphondsp.session.dump.data.ISessionInfoDump
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private data class FakeSession(override var uid: Int, override var packageName: String) : IEffectSession

private class FakeDump(sessionMap: Map<Int, AudioSessionDumpEntry>) : ISessionInfoDump {
    override val sessions = HashMap(sessionMap)
    override fun toString() = "FakeDump($sessions)"
}

private fun entry(uid: Int = 1000, pkg: String = "com.spotify.music") =
    AudioSessionDumpEntry(uid, pkg, "USAGE_MEDIA", "CONTENT_TYPE_MUSIC")

/**
 * Covers the session-removal grace period added to fix repeated idle<->active flapping: some
 * apps (Spotify observed) briefly tear down and recreate their own playback session roughly once
 * a second even while paused, which without debouncing made every poll fire a spurious
 * remove+re-add (destroying/recreating that session's mute effect, and flapping the rootless
 * service's notification/isProcessorIdle in lockstep).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaseSessionDatabaseTest {
    private lateinit var db: TestSessionDatabase
    private val removedCallbacks = mutableListOf<Int>()
    private val changeCallbackCounts = mutableListOf<Int>()

    private class TestSessionDatabase(context: android.content.Context) : BaseSessionDatabase(context) {
        var onRemoved: (IEffectSession) -> Unit = {}
        var accept: (AudioSessionDumpEntry) -> Boolean = { true }
        override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry) = accept(session)
        override fun shouldAddSession(id: Int, uid: Int, packageName: String) = true
        override fun createSession(id: Int, uid: Int, packageName: String): IEffectSession =
            FakeSession(uid, packageName)
        override fun onSessionRemoved(item: IEffectSession) = onRemoved(item)
    }

    @Before
    fun setUp() {
        db = TestSessionDatabase(ApplicationProvider.getApplicationContext())
        db.onRemoved = { session -> removedCallbacks.add((session as FakeSession).uid) }
        db.registerOnSessionChangeListener(object : BaseSessionDatabase.OnSessionChangeListener {
            override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
                changeCallbackCounts.add(sessionList.size)
            }
        })
        changeCallbackCounts.clear() // drop the immediate replay from registerOnSessionChangeListener
    }

    @Test fun sessionReappearingWithinGraceWindowIsNeverRemoved() {
        db.update(FakeDump(mapOf(1 to entry())))
        assertTrue(db.sessionList.containsKey(1))
        val addCount = changeCallbackCounts.size

        // Session vanishes from the dump (Spotify's own blip), then reappears well inside the
        // 1500ms grace window -- must never actually leave sessionList or fire onSessionRemoved.
        db.update(FakeDump(emptyMap()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue("session removed during grace window", db.sessionList.containsKey(1))
        assertTrue("mute effect torn down during grace window", removedCallbacks.isEmpty())

        db.update(FakeDump(mapOf(1 to entry())))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
        assertTrue(db.sessionList.containsKey(1))
        assertTrue(removedCallbacks.isEmpty())
        // No spurious extra onSessionChanged firings from the blip that never should have happened.
        assertEquals(addCount, changeCallbackCounts.size)
    }

    @Test fun sessionGoneForLongerThanGraceWindowIsActuallyRemoved() {
        db.update(FakeDump(mapOf(1 to entry())))
        db.update(FakeDump(emptyMap()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
        assertFalse(db.sessionList.containsKey(1))
        assertEquals(listOf(1000), removedCallbacks)
    }

    @Test fun repeatedBlipsNeverActuallyRemoveTheSession() {
        db.update(FakeDump(mapOf(1 to entry())))
        repeat(5) {
            db.update(FakeDump(emptyMap()))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            db.update(FakeDump(mapOf(1 to entry())))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        }
        assertTrue(db.sessionList.containsKey(1))
        assertTrue(removedCallbacks.isEmpty())
    }

    @Test fun reusedSessionIdWithDifferentUidDuringGraceWindowReplacesTheOldSession() {
        // Android can hand the same session id to a completely different player while the old
        // one's grace period is still running. The id matching alone must not be read as "the
        // same session reappeared" -- the old (uid 1000) session must actually be torn down, and
        // the new one (uid 2000) evaluated fresh, not silently ignored because sessionList still
        // held the old entry under this id.
        db.update(FakeDump(mapOf(1 to entry(uid = 1000))))
        db.update(FakeDump(emptyMap()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        db.update(FakeDump(mapOf(1 to entry(uid = 2000, pkg = "com.other.app"))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))

        assertEquals(listOf(1000), removedCallbacks)
        assertTrue(db.sessionList.containsKey(1))
        assertEquals(2000, (db.sessionList[1] as FakeSession).uid)
    }

    @Test fun reusedSessionIdThatBecomesExcludedDuringGraceWindowIsDropped() {
        db.update(FakeDump(mapOf(1 to entry(uid = 1000))))
        db.update(FakeDump(emptyMap()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        db.setExcludedUids(arrayOf(1000))
        db.update(FakeDump(mapOf(1 to entry(uid = 1000))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))

        assertFalse("excluded uid must not be silently retained as active", db.sessionList.containsKey(1))
    }

    @Test fun sameIdAndUidButNowIneligibleDuringGraceWindowIsDropped() {
        // Same uid/package reappearing under the id doesn't automatically mean "still valid" --
        // its usage/content can itself have become ineligible in the interim.
        db.accept = { it.uid != 1000 || it.content != "CONTENT_TYPE_IGNORED" }
        db.update(FakeDump(mapOf(1 to entry(uid = 1000))))
        db.update(FakeDump(emptyMap()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        db.update(FakeDump(mapOf(1 to AudioSessionDumpEntry(1000, "com.spotify.music", "USAGE_MEDIA", "CONTENT_TYPE_IGNORED"))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))

        assertFalse(db.sessionList.containsKey(1))
        assertEquals(listOf(1000), removedCallbacks)
    }
}
