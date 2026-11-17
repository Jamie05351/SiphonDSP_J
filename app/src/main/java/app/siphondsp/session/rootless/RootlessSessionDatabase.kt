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
    // when the service stops. Main thread, plus the audio worker via setExcludedUids().
    private val heldMutes = LinkedHashMap<Int, MutedEffectSession>()
    private val heldLock = Any()

    private fun heldCount() = synchronized(heldLock) { heldMutes.size }

    private fun log(sid: Int, packageName: String, kind: Kind, detail: String = "") =
        SessionMuteLog.record(SystemClock.elapsedRealtime(), sid, packageName, kind, heldCount(), detail)

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
            if (held.uid == uid && held.packageName == packageName && reassertMute(held.audioMuteEffect)) {
                Timber.d("Session $id ($packageName) plays again; its mute was still attached")
                log(id, packageName, Kind.KEPT)
                return held
            }
            release(held)
            replaced = true
        }
        val muteEffect = factory.make(id, packageName)
        if(muteEffect == null)
        {
            log(id, packageName, Kind.FAILED)
            // Something weird happened, request user to solve issue
            appProblemListener?.onAppProblemDetected(uid)
            return null
        }
        log(id, packageName, if (replaced) Kind.REPLACED else Kind.NEW)
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
        val evicted = mutableListOf<Pair<Int, MutedEffectSession>>()
        synchronized(heldLock) {
            heldMutes.remove(sid)?.takeIf { it !== session }?.let { evicted += sid to it }
            heldMutes[sid] = session
            while (heldMutes.size > MAX_HELD_MUTES) {
                val oldest = heldMutes.keys.first()
                evicted += oldest to heldMutes.remove(oldest)!!
            }
        }
        evicted.forEach { (id, s) -> release(s); log(id, s.packageName, Kind.RELEASED, "too many held") }
        Timber.d("Session $sid (${session.packageName}) went quiet; keeping its mute attached")
        log(sid, session.packageName, Kind.HELD)
    }

    override fun onDumpApplied(dump: ISessionInfoDump, accepted: (Int, AudioSessionDumpEntry) -> Boolean) {
        // A held id that's back for anything that won't be processed (another app, an excluded
        // one, a usage the capture doesn't take) must not stay muted: nothing would play it.
        // Back for the same app and processed, createSession() has already taken it.
        val stale = synchronized(heldLock) {
            heldMutes.filter { (sid, held) ->
                val entry = dump.sessions[sid] ?: return@filter false
                entry.uid != held.uid || !accepted(sid, entry)
            }.onEach { heldMutes.remove(it.key) }
        }
        stale.forEach { (sid, s) -> release(s); log(sid, s.packageName, Kind.RELEASED, "id reused, not processed") }
    }

    override fun onExcludedUidsChanged(uids: Array<Int>) {
        val excluded = synchronized(heldLock) {
            heldMutes.filter { uids.contains(it.value.uid) }.onEach { heldMutes.remove(it.key) }
        }
        excluded.forEach { (sid, s) -> release(s); log(sid, s.packageName, Kind.RELEASED, "excluded") }
    }

    override fun onDestroyed() {
        val all = synchronized(heldLock) { heldMutes.values.toList().also { heldMutes.clear() } }
        all.forEach(::release)
    }

    private fun release(session: MutedEffectSession) {
        try {
            session.audioMuteEffect?.enabled = false
            session.audioMuteEffect?.release()
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
                heldMutes[sid]?.takeIf { !sessionList.containsKey(sid) }?.also { heldMutes.remove(sid) }
            }
            if (held != null) {
                // A quiet session's held mute lost control: nothing is playing through it, so
                // it's no reason to restart or stop. Let it go; a new one is made if it plays.
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
    }
}

typealias OnRootlessSessionChangeListener = BaseSessionDatabase.OnSessionChangeListener