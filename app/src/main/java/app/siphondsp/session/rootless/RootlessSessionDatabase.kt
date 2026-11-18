package app.siphondsp.session.rootless

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.SystemClock
import app.siphondsp.model.AudioSessionDumpEntry
import app.siphondsp.model.IEffectSession
import app.siphondsp.model.rootless.MutedEffectSession
import app.siphondsp.session.dump.data.ISessionInfoDump
import app.siphondsp.session.shared.BaseSessionDatabase
import app.siphondsp.session.rootless.SessionMuteLog.Kind
import app.siphondsp.session.rootless.SessionMuteLog.Trigger
import app.siphondsp.utils.MutedAudioEffectFactory
import timber.log.Timber

class RootlessSessionDatabase(context: Context) : BaseSessionDatabase(context) {

    private val factory by lazy { MutedAudioEffectFactory() }
    private var sessionLossListener: OnSessionLossListener? = null
    private var appProblemListener: OnAppProblemListener? = null

    // Mutes kept for sessions that went quiet, by session id, oldest first. The app's own audio
    // only stays silent while its session has a mute on it: one made only after it starts
    // playing again lets its first audio out unprocessed, at the head unit's full volume. Kept
    // here, apart from sessionList, so a quiet session still reads as idle everywhere else. A
    // mute on a session with nothing playing does nothing. Released when the id comes back for
    // anything that wouldn't be processed, when the app is excluded, past MAX_HELD_MUTES, and
    // whenever the sessions are cleared (a dump-method change, or the service stopping). Main thread, plus the audio worker via setExcludedUids().
    //
    // Mutes armed ahead of any dump (see arm()) wait here too: to everything else they are a
    // held mute, so the dump that finds the session playing takes it as KEPT.
    private val heldMutes = LinkedHashMap<Int, Held>()
    private val heldLock = Any()

    /** A mute waiting on a session that isn't (known to be) playing, and when it was attached. */
    private class Held(val session: MutedEffectSession, val attachedAtMs: Long, val trigger: Trigger)

    // Sessions of processed apps that have played, newest last: what the playback callback arms
    // again when a player starts, before a dump can say which session it is. Session ids aren't
    // visible to a normal app in the callback, but a player that recreates its session usually
    // gets the same id back -- and without this, a released mute on such an id stays off until
    // the next dump finds it (hundreds of ms of unprocessed audio). An id is dropped as soon as
    // anything shows it isn't a processed app's any more (another app, excluded, usage), so this
    // can never arm a mute on audio nothing would process. Main thread, plus the audio worker via
    // setExcludedUids(); under heldLock.
    private val recent = LinkedHashMap<Int, Owner>()
    private class Owner(val uid: Int, val packageName: String, val lastPlayedAtMs: Long)
    // Last arm attempt per id: a mute that keeps losing control isn't remade on every callback.
    private val lastArmAttemptMs = HashMap<Int, Long>()

    private fun heldCount() = synchronized(heldLock) { heldMutes.size }

    private fun log(
        sid: Int, packageName: String, kind: Kind, detail: String = "",
        trigger: Trigger? = null, attachedAtMs: Long? = null,
    ) = SessionMuteLog.record(SystemClock.elapsedRealtime(), sid, packageName, kind, heldCount(), detail, trigger, attachedAtMs)

    private fun noteRecent(sid: Int, uid: Int, packageName: String) = synchronized(heldLock) {
        recent.remove(sid)
        recent[sid] = Owner(uid, packageName, SystemClock.elapsedRealtime())
        while (recent.size > MAX_RECENT) recent.remove(recent.keys.first())
    }

    private fun forgetRecent(sid: Int) = synchronized(heldLock) { recent.remove(sid) }

    /**
     * Attach a mute to session [sid] of [uid]/[packageName] now, ahead of any dump: called on
     * the app's session-open broadcast, or on the playback callback reporting a player start.
     * Nothing if the session already has one, or its app is never processed. Main thread.
     */
    fun arm(sid: Int, uid: Int, packageName: String, trigger: Trigger): Boolean {
        if (sid <= 0 || sessionList.containsKey(sid)) return false
        if (synchronized(heldLock) { heldMutes.containsKey(sid) }) return false
        if (isExcludedOwner(uid, packageName)) return false
        val now = SystemClock.elapsedRealtime()
        val last = lastArmAttemptMs[sid]
        if (last != null && now - last < ARM_RETRY_MS) return false
        lastArmAttemptMs[sid] = now
        if (lastArmAttemptMs.size > MAX_RECENT * 4) {
            val keep = synchronized(heldLock) { recent.keys + sid }
            lastArmAttemptMs.keys.retainAll(keep)
        }

        val effect = factory.make(sid, packageName) ?: return false
        val attachedAt = SystemClock.elapsedRealtime()
        hold(sid, Held(MutedEffectSession(uid, packageName, effect), attachedAt, trigger))
        Timber.d("Session $sid ($packageName) armed on ${trigger.label} in ${attachedAt - now} ms")
        log(sid, packageName, Kind.ARMED, trigger = trigger, attachedAtMs = attachedAt)
        return true
    }

    /**
     * A player started (playback callback) and its session isn't known yet: arm every recent
     * session of a processed app that has no mute, so a recreated session that gets its id
     * back is muted from about its first sample. A mute on an id with nothing playing does
     * nothing; one on an id that turns out to be another app's is released by the next dump.
     */
    fun armRecent(trigger: Trigger) {
        val now = SystemClock.elapsedRealtime()
        val candidates = synchronized(heldLock) {
            recent.entries.reversed()
                .filter { (sid, o) -> now - o.lastPlayedAtMs < RECENT_WINDOW_MS && !heldMutes.containsKey(sid) }
                .take(MAX_ARM_PER_START)
                .map { it.key to it.value }
        }
        candidates.forEach { (sid, o) -> arm(sid, o.uid, o.packageName, trigger) }
    }

    /** Hold [held] for [sid], releasing whatever that pushes out. */
    private fun hold(sid: Int, held: Held) {
        val evicted = mutableListOf<Pair<Int, MutedEffectSession>>()
        synchronized(heldLock) {
            heldMutes.remove(sid)?.takeIf { it.session !== held.session }?.let { evicted += sid to it.session }
            heldMutes[sid] = held
            while (heldMutes.size > MAX_HELD_MUTES) {
                val oldest = heldMutes.keys.first()
                evicted += oldest to heldMutes.remove(oldest)!!.session
            }
        }
        evicted.forEach { (id, s) -> release(s); log(id, s.packageName, Kind.RELEASED, "too many held") }
    }

    override val excludedPackages = arrayOf(
        context.packageName,

        // Non-music apps known to cause issues
        "com.google.android.googlequicksearchbox",
        "com.google.android.as",
        "com.kieronquinn.app.pixelambientmusic",
        "com.draftkings.sportsbook",
        "com.samsung.gpuwatchapp",
        "com.kalkiarts.hexabloompro"
    )

    override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean {
        if (!session.isUsageRecordable()) {
            Timber.d("Skipped session $id due to usage ($session)")
            return false
        }
        return true
    }

    override fun shouldAddSession(id: Int, uid: Int, packageName: String) = true

    override fun createSession(id: Int, uid: Int, packageName: String): MutedEffectSession? {
        val held = synchronized(heldLock) { heldMutes.remove(id) }
        var replaced = false
        if (held != null) {
            val s = held.session
            if (s.uid == uid && s.packageName == packageName && reassertMute(s.audioMuteEffect)) {
                Timber.d("Session $id ($packageName) plays; its mute was already attached (${held.trigger.label})")
                noteRecent(id, uid, packageName)
                log(id, packageName, Kind.KEPT, trigger = held.trigger, attachedAtMs = held.attachedAtMs)
                return s
            }
            release(s)
            replaced = true
        }
        val muteEffect = factory.make(id, packageName)
        if(muteEffect == null)
        {
            forgetRecent(id)
            log(id, packageName, Kind.FAILED)
            // Something weird happened, request user to solve issue
            appProblemListener?.onAppProblemDetected(uid)
            return null
        }
        noteRecent(id, uid, packageName)
        log(id, packageName, if (replaced) Kind.REPLACED else Kind.NEW, trigger = Trigger.DUMP)
        return MutedEffectSession(uid, packageName, muteEffect)
    }

    override fun onSessionRemoved(item: IEffectSession) = release(item as MutedEffectSession)

    override fun onSessionGone(sid: Int, item: IEffectSession) {
        val session = item as MutedEffectSession
        if (!hasControl(session.audioMuteEffect)) {
            release(session)
            log(sid, session.packageName, Kind.RELEASED, "no control")
            return
        }
        // Attached before this session's last start, so it's there before the next one.
        hold(sid, Held(session, attachedAtMs = 0L, trigger = Trigger.HELD))
        Timber.d("Session $sid (${session.packageName}) went quiet; keeping its mute attached")
        log(sid, session.packageName, Kind.HELD)
    }

    override fun onDumpApplied(dump: ISessionInfoDump, accepted: (Int, AudioSessionDumpEntry) -> Boolean) {
        // A held id that's back for anything that won't be processed (another app, an excluded
        // one, a usage the capture doesn't take) must not stay muted: nothing would play it.
        // Back for the same app and processed, createSession() has already taken it.
        val stale = synchronized(heldLock) {
            // Not a processed app's session any more: never arm it again either.
            recent.entries.removeAll { (sid, owner) ->
                val entry = dump.sessions[sid] ?: return@removeAll false
                entry.uid != owner.uid || !accepted(sid, entry)
            }
            heldMutes.filter { (sid, held) ->
                val entry = dump.sessions[sid] ?: return@filter false
                entry.uid != held.session.uid || !accepted(sid, entry)
            }.onEach { heldMutes.remove(it.key) }
        }
        stale.forEach { (sid, h) -> release(h.session); log(sid, h.session.packageName, Kind.RELEASED, "id reused, not processed") }
    }

    override fun onExcludedUidsChanged(uids: Array<Int>) {
        val excluded = synchronized(heldLock) {
            recent.values.removeAll { uids.contains(it.uid) }
            heldMutes.filter { uids.contains(it.value.session.uid) }.onEach { heldMutes.remove(it.key) }
        }
        excluded.forEach { (sid, h) -> release(h.session); log(sid, h.session.packageName, Kind.RELEASED, "excluded") }
    }

    override fun onSessionsCleared() {
        val all = synchronized(heldLock) {
            recent.clear()
            heldMutes.values.map { it.session }.also { heldMutes.clear() }
        }
        lastArmAttemptMs.clear()
        all.forEach(::release)
    }

    private fun release(session: MutedEffectSession) {
        try {
            session.audioMuteEffect?.let { effect ->
                // Listeners off first: disabling fires the factory's enable-status listener, which
                // would try to re-enable a mute on its way out and, failing, report a session loss
                // -- for an id that may already be playing again under a new mute.
                effect.setEnableStatusListener(null)
                effect.setControlStatusListener(null)
                effect.enabled = false
                effect.release()
            }
        }
        catch (ex: Exception) {
            Timber.e("release: effect already destroyed")
            Timber.d(ex)
        }
        session.audioMuteEffect = null
    }

    private fun hasControl(effect: AudioEffect?): Boolean =
        try { effect != null && effect.hasControl() } catch (_: Exception) { false }

    // A held mute about to be used again: still ours, still enabled, still at full cut.
    private fun reassertMute(effect: AudioEffect?): Boolean {
        if (!hasControl(effect)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && effect is DynamicsProcessing)
                effect.setInputGainAllChannelsTo(-200f)
            effect!!.enabled = true
            true
        } catch (ex: Exception) {
            Timber.w(ex, "reassertMute: held mute unusable")
            false
        }
    }

    init {
        factory.sessionLossListener = { sid, packageName ->
            val held = synchronized(heldLock) {
                heldMutes[sid]?.takeIf { !sessionList.containsKey(sid) }?.also { heldMutes.remove(sid) }?.session
            }
            if (held != null) {
                // A quiet session's held mute lost control: nothing is playing through it, so
                // it's no reason to restart or stop. Let it go; the next player start arms the
                // id again (it stays in `recent`), or a dump makes a new one if it plays.
                release(held)
                log(sid, packageName, Kind.RELEASED, "lost control")
            } else {
                sendFxCloseBroadcast(packageName, sid)
                sessionLossListener?.onSessionLost(sid)
            }
        }
    }

    private fun sendFxCloseBroadcast(pkgName: String, sid: Int) {
        val intent = Intent("android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION")
        intent.putExtra("android.media.extra.PACKAGE_NAME", pkgName)
        intent.putExtra("android.media.extra.AUDIO_SESSION", sid)
        intent.putExtra(EXTRA_IGNORE, 1)
        context.sendBroadcast(intent)

        Timber.d("Sent control session close request for $pkgName")
    }

    fun setOnSessionLossListener(newSessionLossListener: OnSessionLossListener) {
        sessionLossListener = newSessionLossListener
    }

    fun setOnAppProblemListener(newAppProblemListener: OnAppProblemListener) {
        appProblemListener = newAppProblemListener
    }

    interface OnSessionLossListener {
        fun onSessionLost(sid: Int)
    }

    interface OnAppProblemListener {
        fun onAppProblemDetected(uid: Int)
    }

    companion object {
        const val EXTRA_IGNORE = "siphondsp.ignore"
        // Sessions are usually reused per player, so a handful covers every app in use.
        private const val MAX_HELD_MUTES = 16
        // Sessions remembered for arming on a player start, and how many one start may arm.
        private const val MAX_RECENT = 16
        private const val MAX_ARM_PER_START = 8
        // A session not played for this long isn't armed on a start any more.
        private const val RECENT_WINDOW_MS = 30 * 60_000L
        private const val ARM_RETRY_MS = 500L
    }
}

typealias OnRootlessSessionChangeListener = BaseSessionDatabase.OnSessionChangeListener