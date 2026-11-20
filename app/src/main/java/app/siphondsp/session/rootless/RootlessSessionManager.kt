package app.siphondsp.session.rootless

import android.content.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.siphondsp.R
import app.siphondsp.session.rootless.SessionMuteLog.Trigger
import app.siphondsp.utils.extensions.ContextExtensions.getPackageNameFromUid
import app.siphondsp.session.dump.DumpManager
import app.siphondsp.session.dump.data.ISessionInfoDump
import app.siphondsp.session.dump.data.ISessionPolicyInfoDump
import app.siphondsp.session.shared.BaseSessionManager
import app.siphondsp.utils.preferences.Preferences
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class RootlessSessionManager(context: Context) : BaseSessionManager(context)
{
    // Session database
    val sessionDatabase: RootlessSessionDatabase = RootlessSessionDatabase(context)
    // Session policy database
    val sessionPolicyDatabase: SessionRecordingPolicyManager = SessionRecordingPolicyManager(context)

    private val appPreferences: Preferences.App by inject()
    private val mainHandler = Handler(Looper.getMainLooper())

    // The capture-allowlist check (a second system dump, 50-65 ms on a head unit) runs here,
    // never on the main thread that attaches mutes and never ahead of them: a new session is
    // muted from the session dump alone, then checked. Single thread, so checks never overlap.
    private val allowlistWorker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SessionAllowlist").apply { isDaemon = true }
    }
    // Main thread. The allowlist results are cached per package (sessionPolicyDatabase); these
    // say how fresh that cache is, so the check only runs when it has something to add.
    private val policyCheckedPackages = HashSet<String>()
    private var policyCheckedAtMs = 0L
    private var allowlistQueued = false
    private var allowlistRequeue: String? = null
    private val allowlistRequeuePackages = HashSet<String>()
    @Volatile private var destroyed = false

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // An install, update or removal can change an app's capture policy: forget its
            // cached result, and refresh in the background if it's playing now.
            val pkg = intent.data?.schemeSpecificPart ?: return
            mainHandler.post {
                if (policyCheckedPackages.remove(pkg)) requestAllowlistCheck("package changed: $pkg", listOf(pkg))
            }
        }
    }

    init {
        sessionDatabase.captureBlocked = { uid -> excludesRestricted() && sessionPolicyDatabase.isRestricted(uid) }
        ContextCompat.registerReceiver(
            context, packageChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun destroy()
    {
        destroyed = true
        runCatching { context.unregisterReceiver(packageChangeReceiver) }
        allowlistWorker.shutdownNow()
        super.destroy()
        sessionDatabase.destroy()
        sessionPolicyDatabase.destroy()
    }

    private var failedDumps = 0

    private fun excludesRestricted(): Boolean =
        runCatching { appPreferences.get<Boolean>(R.string.key_session_exclude_restricted) }.getOrDefault(false)

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
        if (sessionDump is ISessionPolicyInfoDump) {
            // AudioPolicyService's dump carries the allowlist itself: nothing extra to take.
            sessionPolicyDatabase.update(sessionDump)
            markPolicyChecked(sessionDump)
        }

        // Mute first: the only check ahead of it is the in-memory one (excluded apps, and apps
        // already known to block capture).
        sessionDump?.let { sessionDatabase.update(it) }
        // Nothing playing anywhere: an excluded player that stopped isn't playing any more either.
        // A failed dump says nothing either way, but a few in a row mean the list can't be trusted.
        failedDumps = when {
            sessionDump != null -> 0
            dumpManager.lastDumpFoundNothing -> 0.also { sessionDatabase.clearExcludedPlaying() }
            else -> failedDumps + 1
        }
        if (failedDumps >= FAILED_DUMPS_BEFORE_CLEARING) sessionDatabase.clearExcludedPlaying()

        // Then verify, off this thread, only if the cache can't already answer.
        if (sessionDump != null && sessionDump !is ISessionPolicyInfoDump) {
            val now = SystemClock.elapsedRealtime()
            val packages = sessionDump.sessions.values.map { it.packageName }.distinct()
            val unknown = packages.filter { it !in policyCheckedPackages }
            when {
                unknown.isNotEmpty() -> requestAllowlistCheck("new app: ${unknown.joinToString()}", unknown)
                packages.isNotEmpty() && now - policyCheckedAtMs > POLICY_REFRESH_MS ->
                    requestAllowlistCheck("periodic refresh", packages)
            }
        }
    }

    private fun markPolicyChecked(dump: ISessionPolicyInfoDump, alsoChecked: Collection<String> = emptyList()) {
        policyCheckedPackages += dump.capturePermissionLog.keys
        // Playing but absent from the log: checked all the same (the log has nothing on it).
        policyCheckedPackages += alsoChecked
        policyCheckedAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Coalesced: one check at a time, and at most one more queued behind it. [packages]: what
     * this check answers for, cached as checked even if the log doesn't list them (so an app
     * the log never mentions isn't checked again on every dump). Main thread.
     */
    private fun requestAllowlistCheck(reason: String, packages: Collection<String>) {
        if (destroyed) return
        if (allowlistQueued) {
            allowlistRequeue = reason
            allowlistRequeuePackages += packages
            return
        }
        allowlistQueued = true
        val playing = packages.toList()
        val requestedAt = SystemClock.elapsedRealtime()
        try {
            allowlistWorker.execute {
                val t0 = SystemClock.elapsedRealtime()
                val dump = runCatching { dumpManager.dumpCaptureAllowlistLog() }
                    .onFailure { Timber.e(it, "allowlist dump failed") }.getOrNull()
                val tookMs = SystemClock.elapsedRealtime() - t0
                mainHandler.post { applyAllowlist(dump, reason, requestedAt, tookMs, playing) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            allowlistQueued = false
        }
    }

    // Main thread: the same thread, and the same order of updates, as applying a session dump.
    private fun applyAllowlist(
        dump: ISessionPolicyInfoDump?, reason: String, requestedAt: Long, tookMs: Long, playing: List<String>,
    ) {
        allowlistQueued = false
        if (destroyed) return
        SessionMuteLog.noteAllowlistCheck(SessionMuteLog.AllowlistCheck(
            atMs = SystemClock.elapsedRealtime(), reason = reason,
            waitedMs = SystemClock.elapsedRealtime() - requestedAt - tookMs, tookMs = tookMs, ok = dump != null,
        ))
        if (dump != null) {
            sessionPolicyDatabase.update(dump)
            markPolicyChecked(dump, playing)
            // Verify after the mute: an app found to block capture can't be recorded, so its
            // mute would only silence it. Let it go (with a short fade) until the recorder
            // excludes it.
            sessionDatabase.releaseCaptureBlocked()
        }
        allowlistRequeue?.let {
            val packages = allowlistRequeuePackages.toList()
            allowlistRequeue = null
            allowlistRequeuePackages.clear()
            requestAllowlistCheck(it, packages)
        }
    }

    override fun onDumpMethodChange(method: DumpManager.Method) {
        sessionDatabase.clearSessions()
        sessionPolicyDatabase.clearSessions()
        policyCheckedPackages.clear()
        policyCheckedAtMs = 0L
        super.onDumpMethodChange(method)
    }

    private companion object {
        const val FAILED_DUMPS_BEFORE_CLEARING = 3
        // How stale the cached allowlist may get while something plays before it's refreshed
        // (in the background).
        const val POLICY_REFRESH_MS = 60_000L
    }
}
