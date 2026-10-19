package app.siphondsp.session.rootless

import android.content.*
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

    override fun handleSessionDump(sessionDump: ISessionInfoDump?) {
        if(sessionDump is ISessionPolicyInfoDump) {
            sessionPolicyDatabase.update(sessionDump)
        }
        else {
            dumpManager.dumpCaptureAllowlistLog()?.let { sessionPolicyDatabase.update(it) }
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
