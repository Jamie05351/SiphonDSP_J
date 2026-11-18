package app.siphondsp.session.rootless

import android.content.*
import android.content.pm.PackageManager
import app.siphondsp.session.rootless.SessionMuteLog.Trigger
import app.siphondsp.utils.extensions.ContextExtensions.getPackageNameFromUid
import app.siphondsp.session.dump.DumpManager
import app.siphondsp.session.dump.data.ISessionInfoDump
import app.siphondsp.session.dump.data.ISessionPolicyInfoDump
import app.siphondsp.session.shared.BaseSessionManager


class RootlessSessionManager(context: Context) : BaseSessionManager(context)
{
    // Session database
    val sessionDatabase: RootlessSessionDatabase = RootlessSessionDatabase(context)
    // Session policy database
    val sessionPolicyDatabase: SessionRecordingPolicyManager = SessionRecordingPolicyManager(context)

    override fun destroy()
    {
        super.destroy()
        sessionDatabase.destroy()
        sessionPolicyDatabase.destroy()
    }

    private var failedDumps = 0
    // Taken with the session dump on the worker (dumpOnWorker), applied with it on the main thread.
    @Volatile private var allowlistDump: ISessionPolicyInfoDump? = null

    override fun dumpOnWorker(): ISessionInfoDump? {
        val dump = super.dumpOnWorker()
        allowlistDump = if (dump is ISessionPolicyInfoDump) null else dumpManager.dumpCaptureAllowlistLog()
        return dump
    }

    /**
     * A player started: mute it now, not when a dump gets round to finding it. Its session id
     * is only visible here on a device that shows it to us; otherwise arm the sessions of
     * processed apps that played recently, which is what a recreated player session gets back.
     */
    override fun onPlayersStarted(players: List<StartedPlayer>) {
        players.forEach { p ->
            if (p.sessionId > 0 && p.uid > 0) {
                val pkg = context.getPackageNameFromUid(p.uid) ?: return@forEach
                sessionDatabase.arm(p.sessionId, p.uid, pkg, Trigger.CALLBACK)
            }
        }
        sessionDatabase.armRecent(Trigger.CALLBACK)
    }

    /** The app said which session it's about to play on: mute it before it does. */
    override fun onSessionOpened(sessionId: Int, packageName: String) {
        val uid = try {
            context.packageManager.getPackageUid(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        sessionDatabase.arm(sessionId, uid, packageName, Trigger.BROADCAST)
    }

    override fun handleSessionDump(sessionDump: ISessionInfoDump?) {
        if(sessionDump is ISessionPolicyInfoDump) {
            sessionPolicyDatabase.update(sessionDump)
        }
        else {
            allowlistDump?.let { sessionPolicyDatabase.update(it) }
        }

        sessionDump?.let { sessionDatabase.update(it) }
        // Nothing playing anywhere: an excluded player that stopped isn't playing any more either.
        // A failed dump says nothing either way, but a few in a row mean the list can't be trusted.
        failedDumps = when {
            sessionDump != null -> 0
            dumpManager.lastDumpFoundNothing -> 0.also { sessionDatabase.clearExcludedPlaying() }
            else -> failedDumps + 1
        }
        if (failedDumps >= FAILED_DUMPS_BEFORE_CLEARING) sessionDatabase.clearExcludedPlaying()
    }

    override fun onDumpMethodChange(method: DumpManager.Method) {
        sessionDatabase.clearSessions()
        sessionPolicyDatabase.clearSessions()
        super.onDumpMethodChange(method)
    }

    private companion object {
        const val FAILED_DUMPS_BEFORE_CLEARING = 3
    }
}
