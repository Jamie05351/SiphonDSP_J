package app.siphondsp.session.shared

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process.myUid
import app.siphondsp.model.AudioSessionDumpEntry
import app.siphondsp.model.IEffectSession
import app.siphondsp.session.dump.data.ISessionInfoDump
import timber.log.Timber

abstract class BaseSessionDatabase(protected val context: Context) {

    val sessionList = hashMapOf<Int, IEffectSession>()
    private var isDisposing = false
    private val changeCallbacks = mutableListOf<OnSessionChangeListener>()
    private var excludedUids = arrayOf<Int>()
    private val removalHandler = Handler(Looper.getMainLooper())
    // A session id absent from the latest dump is not removed immediately: some apps (Spotify
    // observed) briefly tear down and recreate their AudioPlaybackConfiguration/session on their
    // own cadence even while paused/idle, so treating every disappearance as final made a session
    // flicker in and out on every poll -- tearing down and recreating its mute effect each time,
    // and flapping every idle/active-driven UI downstream (the rootless service notification,
    // isProcessorIdle) in lockstep. Held here, not per-caller, so both the root and rootless
    // session databases get it for free.
    private val pendingRemovals = mutableMapOf<Int, Runnable>()

    protected open val excludedPackages = arrayOf(
        context.packageName
    )
    protected abstract fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean
    protected abstract fun shouldAddSession(id: Int, uid: Int, packageName: String): Boolean
    protected abstract fun createSession(id: Int, uid: Int, packageName: String): IEffectSession?
    protected abstract fun onSessionRemoved(item: IEffectSession)

    fun destroy()
    {
        isDisposing = true
        cancelPendingRemovals()
        clearSessions()
    }

    fun clearSessions(){
        cancelPendingRemovals()
        sessionList.forEach { (_, session) -> onSessionRemoved(session) }
        sessionList.clear()
    }

    private fun cancelPendingRemovals() {
        pendingRemovals.values.forEach { removalHandler.removeCallbacks(it) }
        pendingRemovals.clear()
    }

    fun update(dump: ISessionInfoDump)
    {
        if(isDisposing) {
            Timber.d("update: SessionDatabase is disposing; ignoring dump")
            return
        }

        // A session id this dump still reports is not a real re-add even if it's mid-grace-period
        // below -- it never actually left sessionList, so just cancel the pending removal.
        dump.sessions.keys.forEach { sid ->
            pendingRemovals.remove(sid)?.let { removalHandler.removeCallbacks(it) }
        }

        val removedSessions = sessionList.filter {
            !dump.sessions.contains(it.key) && !pendingRemovals.contains(it.key)
        }
        val addedSessions = dump.sessions.filter {
            !sessionList.contains(it.key) && !excludedUids.contains(it.value.uid)
        }

        addedSessions.forEach next@ {
            val sid = it.key
            val data = it.value
            val name = context.packageManager.getNameForUid(it.value.uid)
            if (data.uid == myUid() || excludedPackages.contains(name)) {
                Timber.d("Skipped session $sid due to package name $name ($data)")
                return@next
            }
            if (sid == 0) {
                Timber.w("Session 0 skipped ($data)")
                return@next
            }

            if(shouldAcceptSessionDump(sid, data)) {
                addSession(sid, data.uid, data.packageName)
            }
        }

        removedSessions.forEach { scheduleRemoval(it.key) }
    }

    private fun scheduleRemoval(sid: Int) {
        if (pendingRemovals.containsKey(sid)) return
        val runnable = Runnable {
            pendingRemovals.remove(sid)
            removeSession(sid)
        }
        pendingRemovals[sid] = runnable
        removalHandler.postDelayed(runnable, SESSION_REMOVAL_GRACE_MS)
    }

    fun addSession(sid: Int, uid: Int, packageName: String, replace: Boolean = false){
        if(!shouldAddSession(sid, uid, packageName)) {
            return
        }

        if(excludedUids.contains(uid)) {
            Timber.d("Rejected session $sid from excluded uid $uid ($packageName)")
            return
        }

        if(replace) {
            // Remove old sessions from package
            sessionList
                .filter { it.value.packageName == packageName }
                .keys
                .forEach(::removeSession)
            changeCallbacks.forEach { it.onSessionChanged(sessionList) }
        }

        Timber.d("Found new session: sid=$sid; $packageName")
        sessionList[sid] = createSession(sid, uid, packageName) ?: return
        Timber.d("Successfully added session $sid")

        changeCallbacks.forEach { it.onSessionChanged(sessionList) }
    }

    fun removeSession(sid: Int) {
        sessionList[sid]?.let { it ->
            Timber.d("Removed session: session ${sid}; data: $it")
            onSessionRemoved(it)
            sessionList.remove(sid)
            changeCallbacks.forEach { it.onSessionChanged(sessionList) }
        }
    }

    fun setExcludedUids(uids: Array<Int>) {
        excludedUids = uids

        val excludedSessions = sessionList.filter {
            excludedUids.contains(it.value.uid)
        }
        val notify = excludedSessions.isNotEmpty()
        excludedSessions.forEach { (_, session) -> onSessionRemoved(session) }
        excludedSessions.map { it.key }.forEach { sid ->
            sessionList.remove(sid)
            pendingRemovals.remove(sid)?.let { removalHandler.removeCallbacks(it) }
        }
        if(notify)
            changeCallbacks.forEach { it.onSessionChanged(sessionList) }
    }

    fun registerOnSessionChangeListener(changeListener: OnSessionChangeListener) {
        changeCallbacks.add(changeListener)
        changeListener.onSessionChanged(sessionList)
    }

    fun unregisterOnSessionChangeListener(changeListener: OnSessionChangeListener) {
        changeCallbacks.remove(changeListener)
    }

    interface OnSessionChangeListener {
        fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>)
    }

    companion object {
        // Long enough to absorb an app briefly tearing down/recreating its own playback session
        // (observed roughly once a second), short enough that actually stopping playback still
        // reads as prompt.
        private const val SESSION_REMOVAL_GRACE_MS = 1_500L
    }
}