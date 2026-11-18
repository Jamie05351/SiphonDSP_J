package app.siphondsp.session.rootless

import android.content.*
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

    /** Only mute a currently identified, capture-eligible player before its dump arrives. */
    override fun onPlayersStarted(players: List<StartedPlayer>) {
        players.forEach { armPlayer(it, Trigger.CALLBACK) }
    }

    private fun armPlayer(player: StartedPlayer, trigger: Trigger, expectedPackage: String? = null) {
        if (player.sessionId <= 0 || player.uid <= 0) return
        val pkg = context.getPackageNameFromUid(player.uid) ?: return
        if (expectedPackage != null && pkg != expectedPackage) return
        sessionDatabase.arm(
            player.sessionId, player.uid, pkg, trigger, player.attributes,
            policyAllowed = sessionPolicyDatabase.isCaptureAllowed(player.uid, pkg),
        )
    }

    override fun onSessionOpened(sessionId: Int, packageName: String) {
        // The broadcast carries no usage or capture policy. Use current playback metadata for
        // the exact session if visible; otherwise the requested dump must establish eligibility.
        findPlaybackSession(sessionId)?.let { armPlayer(it, Trigger.BROADCAST, packageName) }
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
