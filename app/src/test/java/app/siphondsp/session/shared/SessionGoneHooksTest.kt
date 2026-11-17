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

private data class Session(override var uid: Int, override var packageName: String) : IEffectSession

private class Dump(sessionMap: Map<Int, AudioSessionDumpEntry>) : ISessionInfoDump {
    override val sessions = HashMap(sessionMap)
    override fun toString() = "Dump($sessions)"
}

private fun media(uid: Int = 1000, pkg: String = "com.spotify.music") =
    AudioSessionDumpEntry(uid, pkg, "USAGE_MEDIA", "CONTENT_TYPE_MUSIC")

/**
 * The hooks the rootless database uses to keep a quiet session's mute attached: a session that
 * stops playing on its own goes to onSessionGone (held), anything taken away goes to
 * onSessionRemoved (released), and every dump reports which ids it would process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionGoneHooksTest {
    private class Db(context: android.content.Context) : BaseSessionDatabase(context) {
        val gone = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        val excludedChanges = mutableListOf<List<Int>>()
        val processable = mutableListOf<Map<Int, Boolean>>()
        var cleared = 0
        var accept: (AudioSessionDumpEntry) -> Boolean = { true }
        override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry) = accept(session)
        override fun shouldAddSession(id: Int, uid: Int, packageName: String) = true
        override fun createSession(id: Int, uid: Int, packageName: String): IEffectSession = Session(uid, packageName)
        override fun onSessionRemoved(item: IEffectSession) { removed += item.uid }
        override fun onSessionGone(sid: Int, item: IEffectSession) { gone += sid }
        override fun onExcludedUidsChanged(uids: Array<Int>) { excludedChanges += uids.toList() }
        override fun onSessionsCleared() { cleared++ }
        override fun onDumpApplied(dump: ISessionInfoDump, accepted: (Int, AudioSessionDumpEntry) -> Boolean) {
            processable += dump.sessions.mapValues { (sid, e) -> accepted(sid, e) }
        }
    }

    private lateinit var db: Db

    @Before fun setUp() { db = Db(ApplicationProvider.getApplicationContext()) }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun aSessionThatStopsPlayingIsGoneNotRemoved() {
        db.update(Dump(mapOf(5 to media())))
        db.update(Dump(emptyMap()))
        idle(2_000)
        assertFalse("a quiet session still reads as idle", db.sessionList.containsKey(5))
        assertEquals(listOf(5), db.gone)
        assertTrue(db.removed.isEmpty())
    }

    @Test fun anIdReusedByAnotherAppIsRemovedNotGone() {
        db.update(Dump(mapOf(5 to media(uid = 1000))))
        db.update(Dump(emptyMap()))
        idle(500)
        db.update(Dump(mapOf(5 to media(uid = 2000, pkg = "com.other"))))
        assertEquals(listOf(1000), db.removed)
        assertTrue(db.gone.isEmpty())
    }

    @Test fun exclusionAndClearingReleaseOutright() {
        db.update(Dump(mapOf(5 to media(uid = 1000), 6 to media(uid = 3000, pkg = "com.b"))))
        db.setExcludedUids(arrayOf(1000))
        assertEquals(listOf(1000), db.removed)
        assertEquals(listOf(listOf(1000)), db.excludedChanges)
        db.destroy()
        assertEquals(listOf(1000, 3000), db.removed)
        assertTrue(db.gone.isEmpty())
        assertEquals(1, db.cleared)
    }

    @Test fun clearingWithoutDestroyAlsoDropsWhatIsHeld() {
        db.update(Dump(mapOf(5 to media())))
        db.update(Dump(emptyMap()))
        idle(2_000)
        assertEquals(listOf(5), db.gone)
        db.clearSessions() // a dump-method change
        assertEquals(1, db.cleared)
    }

    @Test fun eachDumpReportsWhichIdsWouldBeProcessed() {
        db.setExcludedUids(arrayOf(4000))
        db.accept = { it.usage == "USAGE_MEDIA" }
        db.update(Dump(mapOf(
            5 to media(uid = 1000),
            6 to media(uid = 4000, pkg = "com.excluded"),
            7 to AudioSessionDumpEntry(1000, "com.spotify.music", "USAGE_VOICE_COMMUNICATION", "CONTENT_TYPE_SPEECH"),
        )))
        assertEquals(mapOf(5 to true, 6 to false, 7 to false), db.processable.last())
    }
}
