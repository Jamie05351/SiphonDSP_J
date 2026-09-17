package app.siphondsp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import app.siphondsp.BuildConfig
import app.siphondsp.R
import app.siphondsp.audio.SpectrumEngine
import app.siphondsp.flavor.CrashlyticsImpl
import app.siphondsp.interop.JamesDspLocalEngine
import app.siphondsp.interop.ProcessorMessageHandler
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.IEffectSession
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.preference.AudioEncoding
import app.siphondsp.model.room.AppBlocklistDatabase
import app.siphondsp.model.room.AppBlocklistRepository
import app.siphondsp.model.room.BlockedApp
import app.siphondsp.model.rootless.SessionRecordingPolicyEntry
import app.siphondsp.session.rootless.OnRootlessSessionChangeListener
import app.siphondsp.session.rootless.RootlessSessionDatabase
import app.siphondsp.session.rootless.RootlessSessionManager
import app.siphondsp.session.rootless.SessionRecordingPolicyManager
import app.siphondsp.utils.Constants
import app.siphondsp.utils.Constants.ACTION_NATIVE_BMW_DSP_UPDATED
import app.siphondsp.utils.Constants.ACTION_PREFERENCES_UPDATED
import app.siphondsp.utils.Constants.ACTION_PRESET_LOADED
import app.siphondsp.utils.Constants.ACTION_SAMPLE_RATE_UPDATED
import app.siphondsp.utils.Constants.ACTION_SERVICE_HARD_REBOOT_CORE
import app.siphondsp.utils.Constants.ACTION_SERVICE_RELOAD_LIVEPROG
import app.siphondsp.utils.Constants.ACTION_SERVICE_SOFT_REBOOT_CORE
import app.siphondsp.utils.extensions.CompatExtensions.getParcelableAs
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.extensions.PermissionExtensions.hasRecordPermission
import app.siphondsp.utils.notifications.Notifications
import app.siphondsp.utils.notifications.ServiceNotificationHelper
import app.siphondsp.utils.preferences.Preferences
import app.siphondsp.utils.sdkAbove
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException

@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : BaseAudioProcessorService() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    @Volatile private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null

    private val recorderLifecycleLock = Any()

    @Volatile
    private var recreateRecorderRequested = false

    // Set only when recovery/generator switching deliberately stops a blocking read.
    // Cleared when the worker consumes that interruption or retires that recorder.
    @Volatile
    private var expectingReadInterruption = false

    @Volatile
    private var recorderThread: Thread? = null

    @Volatile
    private var activeRecorder: AudioRecord? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    private lateinit var engine: JamesDspLocalEngine
    private val workerAlive: Boolean
        get() = recorderThread?.isAlive == true

    private val healthHandler = Handler(Looper.getMainLooper())
    // Lifecycle metadata is protected by recorderLifecycleLock. Per-buffer telemetry has one
    // writer (the recorder worker) and volatile readers; no lock or allocation on the hot path.
    private var pipelineStartedAt = 0L
    private var flowExpectedSince = 0L
    private var recreationSince = -1L
    private var recreationInProgress = false
    private var recreationReason = ""
    private var closeEngineOnWorkerExit = false
    private var lastHealth: AudioPipelineHealth.Result? = null
    private val recoveryTimes = ArrayDeque<Long>()
    @Volatile private var lastSuccessfulRead = -1L
    @Volatile private var lastSuccessfulProcess = -1L
    @Volatile private var lastSuccessfulWrite = -1L
    @Volatile private var consecutiveReadFailures = 0
    @Volatile private var consecutiveWriteFailures = 0
    @Volatile private var recorderSuspended = false
    @Volatile private var startupDiagnostics: StartupAudioDiagnostics? = null

    private val startupDiagnosticsFinisher = Runnable {
        startupDiagnostics?.finishIfDue()
    }

    private val healthWatchdog = object : Runnable {
        override fun run() {
            synchronized(recorderLifecycleLock) {
                if (isServiceDisposing || isProcessorDisposing) return
                checkPipelineHealthLocked()
                if (!isServiceDisposing && !isProcessorDisposing)
                    healthHandler.postDelayed(this, AudioPipelineHealth.WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private lateinit var sessionManager: RootlessSessionManager
    private var sessionLossRetryCount = 0

    @Volatile
    private var isProcessorIdle = false

    // Mirrors NativeBmwDspValues[INDEX_MEAS_GEN_TYPE] != 0, kept in step with every config push
    // (initial load, live broadcast, preset restore) below. Read from runRecorderLoop so it can
    // bypass the real AudioPlaybackCaptureConfiguration capture entirely while the generator is
    // active -- see the comment at its read site for why that's necessary, not just an
    // optimisation.
    @Volatile
    private var measGenActive = false

    @Volatile
    private var idleSinceMillis = 0L

    @Volatile
    private var suspendOnIdle = false

    @Volatile
    private var excludeRestrictedSessions = false

    @Volatile
    private var isProcessorDisposing = false

    @Volatile
    private var isServiceDisposing = false

    private val preferences: Preferences.App by inject()
    private val preferencesVar: Preferences.Var by inject()

    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> {
        Timber.d("blockedAppObserver: Database changed; ignored=${!workerAlive}")
        if(workerAlive)
            requestAudioRecordRecreation("blocklist changed")
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this

        audioManager = getSystemService<AudioManager>()!!
        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        // Called as early as onCreate() can manage -- right after the trivial getSystemService()
        // lookups above, before any of the slower setup below (session-manager/database wiring,
        // and especially JamesDspLocalEngine's construction, which does synchronous disk I/O to
        // load the persisted PEQ/DSP config). Android budgets a limited window for a foreground
        // service to actually call startForeground() after starting; on slow/contended storage,
        // that disk I/O finishing first could push this call past the window
        // (ForegroundServiceDidNotStartInTimeException on Android 12+) or just stall startup
        // visibly. This doesn't change this call's relationship to mediaProjection (still set up
        // later, in onStartCommand(), same as before this reorder) -- only its position relative
        // to the slower unrelated setup steps within this same onCreate().
        startForeground(
            Notifications.ID_SERVICE_STATUS,
            ServiceNotificationHelper.createServiceNotification(this, arrayOf()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        sessionManager = RootlessSessionManager(this)
        sessionManager.sessionDatabase.setOnSessionLossListener(onSessionLossListener)
        sessionManager.sessionDatabase.setOnAppProblemListener(onAppProblemListener)
        sessionManager.sessionDatabase.registerOnSessionChangeListener(onSessionChangeListener)
        sessionManager.sessionPolicyDatabase.registerOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)

        engine = JamesDspLocalEngine(this, ProcessorMessageHandler())
        engine.syncWithPreferences()
        measGenActive = isMeasGenActive(NativeBmwDspValues.load(this))

        val filter = IntentFilter()
        filter.addAction(ACTION_PREFERENCES_UPDATED)
        filter.addAction(ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(ACTION_SERVICE_SOFT_REBOOT_CORE)
        filter.addAction(ACTION_NATIVE_BMW_DSP_UPDATED)
        filter.addAction(ACTION_PRESET_LOADED)
        registerLocalReceiver(broadcastReceiver, filter)

        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        loadFromPreferences(getString(R.string.key_powersave_suspend))
        loadFromPreferences(getString(R.string.key_session_exclude_restricted))

        blockedApps.observeForever(blockedAppObserver)
        notificationManager.cancel(Notifications.ID_SERVICE_STARTUP)
        recreateRecorderRequested = false
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand")

        when (intent.action) {
            null -> Timber.wtf("onStartCommand: intent.action is null")
            ACTION_START -> Timber.d("Starting service")
            ACTION_STOP -> {
                Timber.d("Stopping service")
                stopServiceSafely()
                return START_NOT_STICKY
            }
        }

        synchronized(recorderLifecycleLock) {
            if (isServiceDisposing || isProcessorDisposing) return START_NOT_STICKY
            if (workerAlive) {
                checkPipelineHealthLocked()
                return START_NOT_STICKY
            }
        }

        notificationManager.cancel(Notifications.ID_SERVICE_SESSION_LOSS)
        notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)

        // Reuse a live projection after worker exit; Android projection authorization is not
        // a reusable token. A revoked projection still follows the existing stop/auth flow.
        if (mediaProjection == null) {
            mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)
            mediaProjection = try {
                mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionStartIntent!!)
            }
            catch (ex: Exception) {
                Timber.e("Failed to acquire media projection")
                sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
                Timber.e(ex)
                null
            }
            mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        }

        if (mediaProjection != null) {
            startRecording()
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        }
        else {
            StartupAudioDiagnostics.logImmediateFailure(
                "service start",
                StartupAudioDiagnostics.Result.MEDIA_PROJECTION,
                "MediaProjection unavailable",
            )
            Timber.w("Failed to capture audio")
            stopServiceSafely()
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        synchronized(recorderLifecycleLock) { isServiceDisposing = true }
        healthHandler.removeCallbacks(healthWatchdog)
        healthHandler.removeCallbacks(startupDiagnosticsFinisher)
        startupDiagnostics?.finishIfDue(force = true)
        stopRecording()
        val closeEngineNow = synchronized(recorderLifecycleLock) {
            // A stuck native process holds engine.nativeLock. Do not block the main thread
            // indefinitely or dispose its engine while it still owns the audio pipeline.
            if (workerAlive) {
                closeEngineOnWorkerExit = true
                false
            } else true
        }
        if (closeEngineNow) engine.close()

        stopForeground(STOP_FOREGROUND_REMOVE)
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        blockedApps.removeObserver(blockedAppObserver)
        unregisterLocalReceiver(broadcastReceiver)
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
        sessionManager.sessionDatabase.unregisterOnSessionChangeListener(onSessionChangeListener)
        sessionManager.destroy()

        preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

        applicationScope.cancel()

        super.onDestroy()
    }

    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        loadFromPreferences(key)
    }

    private val projectionCallback = object: MediaProjection.Callback() {
        override fun onStop() {
            if(isServiceDisposing)
                return

            Timber.w("Capture permission revoked. Stopping service.")
            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))

            if(!preferencesVar.get<Boolean>(R.string.key_is_activity_active))
                this@RootlessAudioProcessorService.toast(getString(R.string.capture_permission_revoked_toast))

            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            stopServiceSafely()
        }
    }

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SAMPLE_RATE_UPDATED -> engine.syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                ACTION_PREFERENCES_UPDATED -> engine.syncWithPreferences()
                ACTION_SERVICE_RELOAD_LIVEPROG -> engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                ACTION_SERVICE_HARD_REBOOT_CORE -> restartRecording()
                ACTION_SERVICE_SOFT_REBOOT_CORE -> requestAudioRecordRecreation()
                ACTION_NATIVE_BMW_DSP_UPDATED -> {
                    intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)?.let {
                        // measGenActive must only reflect state the native engine actually
                        // adopted -- flipping it from the raw array regardless of whether
                        // configureNativeBmwDsp() accepted it would desync the Kotlin mirror from
                        // native's real config (e.g. on a size/validation rejection), sending the
                        // recorder loop down the wrong branch (zero-fill vs real capture) for
                        // whatever native is actually doing.
                        if (engine.configureNativeBmwDsp(it)) {
                            applyMeasGenTransition(isMeasGenActive(it))
                        } else {
                            Timber.e("Failed to apply native BMW DSP configuration from broadcast")
                        }
                    }
                }
                ACTION_PRESET_LOADED -> resyncNativeBmwStateFromDisk()
            }
        }
    }

    private fun isMeasGenActive(values: FloatArray) =
        values.getOrElse(NativeBmwDspValues.INDEX_MEAS_GEN_TYPE) { 0f } != 0f

    // Backup restores broadcast ACTION_PREFERENCES_UPDATED/ACTION_PRESET_LOADED, neither of
    // which otherwise reaches the BMW DSP/PEQ engine state (syncWithPreferences() only knows
    // about the legacy dsp_*.xml namespaces). Re-read both stores from disk and push them to
    // the running engine so a restored backup actually takes effect.
    private fun resyncNativeBmwStateFromDisk() {
        val values = NativeBmwDspValues.load(this)
        // Same success-gating as the broadcast handler above: only mirror measGenActive (and act
        // on its transition) when native actually adopted these values.
        val dspApplied = engine.configureNativeBmwDsp(values)
        if (!dspApplied) {
            Timber.e("Failed to apply native BMW DSP configuration after preset/profile load")
        }
        if (!engine.configureNativeBmwPeq(BmwPeqState.loadPersisted(this), persistOnSuccess = false, source = "preset-restore")) {
            Timber.e("Failed to apply native BMW PEQ configuration after preset/profile load")
        }
        if (dspApplied) {
            applyMeasGenTransition(isMeasGenActive(values))
        }
    }

    /**
     * Applies a freshly-confirmed measGenActive value, handling both transition directions.
     * Rising (off -> on): the worker won't notice measGenActive until it next reaches the top of
     * runRecorderLoop, and with nothing else playing it's likely already parked in a blocking
     * AudioRecord.read() that may never return on its own -- force it out via
     * unblockRecorderForMeasurementGenerator(). Falling (on -> off): the AudioRecord sat unread
     * (see runRecorderLoop's zero-fill branch) for however long the generator was active, so
     * resuming real recorder.read() calls on it risks a stale/discontinuous first read -- request
     * a clean recreate instead of just letting reads resume on whatever was left sitting there.
     */
    private fun applyMeasGenTransition(newActive: Boolean) {
        val wasActive = synchronized(recorderLifecycleLock) {
            val previous = measGenActive
            measGenActive = newActive
            if (previous != newActive) flowExpectedSince = SystemClock.elapsedRealtime()
            previous
        }
        if (newActive && !wasActive) {
            unblockRecorderForMeasurementGenerator()
        } else if (!newActive && wasActive) {
            requestAudioRecordRecreation()
        }
    }

    // Enabling the generator only flips measGenActive; the worker won't notice until it next
    // reaches the top of runRecorderLoop. If no other app is playing, the worker is likely
    // already parked in a blocking AudioRecord.read() that may never return on its own (see the
    // comment at that read site), so the flag would go unobserved indefinitely and the generator
    // would stay silent. Force the worker out of that read the same way stopRecording() does --
    // stop() the live AudioRecord under the lifecycle lock -- and flag a recreate so it rebuilds
    // a fresh recorder next iteration instead of trying to keep using the now-stopped one.
    private fun unblockRecorderForMeasurementGenerator() {
        synchronized(recorderLifecycleLock) {
            requestRecreationLocked("measurement generator enabled", unblock = true)
        }
    }

    private val onSessionLossListener = object: RootlessSessionDatabase.OnSessionLossListener {
        override fun onSessionLost(sid: Int) {
            if(!preferences.get<Boolean>(R.string.key_session_loss_ignore)) {
                if(sessionLossRetryCount < SESSION_LOSS_MAX_RETRIES) {
                    sessionLossRetryCount++
                    Timber.d("Session lost. Retry count: $sessionLossRetryCount/$SESSION_LOSS_MAX_RETRIES")
                    sessionManager.pollOnce(false)
                    restartRecording()
                    return
                }
                else {
                    sessionLossRetryCount = 0
                    Timber.d("Giving up on saving session. User interaction required.")
                }

                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
                ServiceNotificationHelper.pushSessionLossNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent)
                this@RootlessAudioProcessorService.toast(getString(R.string.session_control_loss_toast), false)
                Timber.w("Terminating service due to session loss")
                stopServiceSafely()
            }
        }
    }

    private val onSessionChangeListener = object : OnRootlessSessionChangeListener {
        override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
            synchronized(recorderLifecycleLock) {
                val idle = sessionList.isEmpty()
                if (isProcessorIdle && !idle) flowExpectedSince = SystemClock.elapsedRealtime()
                isProcessorIdle = idle
                if (idle) startupDiagnostics?.markExpectedIdle()
            }
            if(!isProcessorIdle) {
                sessionLossRetryCount = 0
                idleSinceMillis = 0L
            }
            else if(idleSinceMillis == 0L) {
                idleSinceMillis = SystemClock.elapsedRealtime()
            }

            Timber.d("onSessionChanged: isProcessorIdle=$isProcessorIdle")
            ServiceNotificationHelper.pushServiceNotification(
                this@RootlessAudioProcessorService,
                sessionList.map { it.value }.toTypedArray()
            )
        }
    }

    private val onAppProblemListener = object : RootlessSessionDatabase.OnAppProblemListener {
        override fun onAppProblemDetected(uid: Int) {
            if(!preferences.get<Boolean>(R.string.key_session_app_problem_ignore)) {
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

                if(preferencesVar.get<Boolean>(R.string.key_is_activity_active) ||
                    preferencesVar.get<Boolean>(R.string.key_is_app_compat_activity_active)) {
                    startActivity(
                        ServiceNotificationHelper.createAppTroubleshootIntent(
                            this@RootlessAudioProcessorService,
                            mediaProjectionStartIntent,
                            uid,
                            directLaunch = true
                        )
                    )
                    notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
                }
                else {
                    ServiceNotificationHelper.pushAppIssueNotification(
                        this@RootlessAudioProcessorService,
                        mediaProjectionStartIntent,
                        uid
                    )
                }

                this@RootlessAudioProcessorService.toast(getString(R.string.session_app_compat_toast), false)
                Timber.w("Terminating service due to app incompatibility; redirect user to troubleshooting options")
                stopServiceSafely()
            }
        }
    }

    private val onSessionPolicyChangeListener = object : SessionRecordingPolicyManager.OnSessionRecordingPolicyChangeListener {
        override fun onSessionRecordingPolicyChanged(
            sessionList: HashMap<String, SessionRecordingPolicyEntry>,
            isMinorUpdate: Boolean
        ) {
            if(!excludeRestrictedSessions) {
                Timber.d("onRestrictedSessionChanged: blocked; excludeRestrictedSessions disabled")
                return
            }

            if(!isMinorUpdate) {
                Timber.d("onRestrictedSessionChanged: major update detected; requesting soft-reboot")
                requestAudioRecordRecreation()
            }
            else {
                Timber.d("onRestrictedSessionChanged: minor update detected")
            }
        }
    }

    private fun loadFromPreferences(key: String?) {
        when (key) {
            getString(R.string.key_powersave_suspend) -> {
                suspendOnIdle = preferences.get<Boolean>(R.string.key_powersave_suspend)
                Timber.d("Suspend on idle set to $suspendOnIdle")
            }
            getString(R.string.key_session_exclude_restricted) -> {
                excludeRestrictedSessions = preferences.get<Boolean>(R.string.key_session_exclude_restricted)
                Timber.d("Exclude restricted set to $excludeRestrictedSessions")
                requestAudioRecordRecreation()
            }
        }
    }

    fun requestAudioRecordRecreation(reason: String = "configuration/session policy changed") {
        // Even configuration changes must unblock an idle read; otherwise the pending request
        // can never reach the worker's recreation branch while no media is being captured.
        synchronized(recorderLifecycleLock) { requestRecreationLocked(reason, unblock = true) }
    }

    private fun requestRecreationLocked(reason: String, unblock: Boolean) {
        if (isProcessorDisposing || isServiceDisposing || !workerAlive) return
        if (!recreateRecorderRequested && !recreationInProgress) {
            recreationSince = SystemClock.elapsedRealtime()
            recreationReason = reason
            Timber.i("Audio pipeline recreation requested: %s", reason)
        }
        recreateRecorderRequested = true
        if (unblock) {
            expectingReadInterruption = true
            safeStop(activeRecorder)
            safeStop(activeTrack)
        }
    }

    private fun stopServiceSafely() {
        synchronized(recorderLifecycleLock) { isServiceDisposing = true }
        healthHandler.removeCallbacks(healthWatchdog)
        stopSelf()
    }

    private fun beginStartupDiagnostics(
        reason: String,
        sampleRate: Int,
        encoding: AudioEncoding,
        bufferSizeBytes: Int,
        bufferSamples: Int,
    ): StartupAudioDiagnostics {
        startupDiagnostics?.finishIfDue(force = true)
        healthHandler.removeCallbacks(startupDiagnosticsFinisher)
        val probe = StartupAudioDiagnostics.begin(
            reason = reason,
            sampleRate = sampleRate,
            encodingName = encoding.name,
            bufferSizeBytes = bufferSizeBytes,
            bufferSamples = bufferSamples,
            mediaProjectionReady = mediaProjection != null,
            nativeHandleReady = engine.isNativeHandleReady(),
            measurementGeneratorActive = measGenActive,
            processorIdle = isProcessorIdle,
        )
        startupDiagnostics = probe
        // This is intentionally Handler-driven rather than audio-loop-driven. If READ_BLOCKING or
        // WRITE_BLOCKING never returns, the diagnostic still emits at the one-second deadline.
        healthHandler.postDelayed(startupDiagnosticsFinisher, StartupAudioDiagnostics.WINDOW_MS)
        return probe
    }

    // Called under the existing lifecycle lock so state getters cannot race release().
    private fun checkPipelineHealthLocked() {
        val now = SystemClock.elapsedRealtime()
        val recorderState = activeRecorder?.state
        val recordingState = activeRecorder?.recordingState
        val trackState = activeTrack?.state
        val playState = activeTrack?.playState
        val snapshot = AudioPipelineHealth.Snapshot(
            now, workerAlive, isProcessorDisposing || isServiceDisposing,
            recorderState == AudioRecord.STATE_INITIALIZED,
            recordingState == AudioRecord.RECORDSTATE_RECORDING,
            trackState == AudioTrack.STATE_INITIALIZED, playState == AudioTrack.PLAYSTATE_PLAYING,
            pipelineStartedAt, flowExpectedSince,
            lastSuccessfulRead, lastSuccessfulProcess, lastSuccessfulWrite,
            consecutiveReadFailures, consecutiveWriteFailures,
            isProcessorIdle, recorderSuspended, measGenActive,
            recreateRecorderRequested, recreationInProgress, recreationSince,
        )
        val health = AudioPipelineHealth.evaluate(snapshot)
        if (health != lastHealth) {
            fun age(time: Long) = if (time < 0) -1 else now - time
            Timber.i(
                "Audio pipeline %s -> %s: %s; ages read/process/write=%s/%s/%sms; " +
                    "record=%s/%s track=%s/%s failures=%s/%s idle=%s suspended=%s generator=%s recreate=%s/%s",
                lastHealth?.state, health.state, health.reason,
                age(snapshot.lastRead), age(snapshot.lastProcess), age(snapshot.lastWrite),
                recorderState, recordingState, trackState, playState,
                snapshot.readFailures, snapshot.writeFailures, isProcessorIdle, recorderSuspended,
                measGenActive, recreateRecorderRequested, recreationInProgress,
            )
            if (health.state == AudioPipelineHealth.State.HEALTHY && recreationSince >= 0) {
                Timber.i("Audio pipeline recovery succeeded: %s", recreationReason)
                recreationSince = -1
            }
            lastHealth = health
        }
        when (health.state) {
            AudioPipelineHealth.State.STALLED -> {
                while (recoveryTimes.isNotEmpty() && now - recoveryTimes.first() >= AudioPipelineHealth.RECOVERY_WINDOW_MS)
                    recoveryTimes.removeFirst()
                if (recoveryTimes.size >= AudioPipelineHealth.MAX_RECOVERIES_PER_WINDOW) {
                    lastHealth = AudioPipelineHealth.Result(AudioPipelineHealth.State.FAILED, "recovery budget exhausted")
                    Timber.e("Audio pipeline recovery failed: retry budget exhausted (%s)", health.reason)
                    stopServiceSafely()
                } else {
                    recoveryTimes.addLast(now)
                    requestRecreationLocked(health.reason, unblock = true)
                }
            }
            AudioPipelineHealth.State.FAILED -> {
                Timber.e("Audio pipeline recovery failed: %s", health.reason)
                stopServiceSafely()
            }
            else -> Unit
        }
    }

    @SuppressLint("BinaryOperationInTimber")
    private fun startRecording() {
        synchronized(recorderLifecycleLock) {
            if (isServiceDisposing || isProcessorDisposing) return
            if(recorderThread?.isAlive == true) {
                Timber.w("startRecording: recorder thread already running")
                return
            }

            if (!hasRecordPermission()) {
                Timber.e("Record audio permission missing. Can't record")
                stopServiceSafely()
                return
            }

            isProcessorDisposing = false
            recreateRecorderRequested = false
            recreationInProgress = false
            recreationSince = -1
            expectingReadInterruption = false
            lastSuccessfulRead = -1
            lastSuccessfulProcess = -1
            lastSuccessfulWrite = -1
            consecutiveReadFailures = 0
            consecutiveWriteFailures = 0
            recorderSuspended = false
            pipelineStartedAt = SystemClock.elapsedRealtime()
            flowExpectedSince = pipelineStartedAt
            lastHealth = null

            val encoding = AudioEncoding.fromInt(
                preferences.get<String>(R.string.key_audioformat_encoding).toIntOrNull() ?: 1
            )
            val requestedSamples = preferences.get<Float>(R.string.key_audioformat_buffersize).toInt().coerceAtLeast(2)
            val bytesPerSample = if (encoding == AudioEncoding.PcmFloat) Float.SIZE_BYTES else Short.SIZE_BYTES
            val encodingFormat = if (encoding == AudioEncoding.PcmShort)
                AudioFormat.ENCODING_PCM_16BIT
            else
                AudioFormat.ENCODING_PCM_FLOAT
            val sampleRate = clamp(determineSamplingRate(), 44100, 48000)
            val frameSizeBytes = CHANNEL_COUNT * bytesPerSample
            val requestedBytes = requestedSamples * bytesPerSample
            val minRecordBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, encodingFormat)
                .takeIf { it > 0 } ?: requestedBytes
            val minTrackBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encodingFormat)
                .takeIf { it > 0 } ?: requestedBytes
            val bufferSizeBytes = alignUp(maxOf(requestedBytes, minRecordBytes, minTrackBytes), frameSizeBytes)
            val bufferSamples = bufferSizeBytes / bytesPerSample

            Timber.i(
                "Sample rate: $sampleRate; Encoding: ${encoding.name}; " +
                    "Requested samples: $requestedSamples; Buffer samples: $bufferSamples; " +
                    "Buffer bytes: $bufferSizeBytes; HAL frames: ${determineBufferSize()}"
            )

            if(engine.sampleRate.toInt() != sampleRate) {
                Timber.d("Sampling rate changed to ${sampleRate}Hz")
                engine.sampleRate = sampleRate.toFloat()
            }

            beginStartupDiagnostics("service start", sampleRate, encoding, bufferSizeBytes, bufferSamples)

            val worker = Thread({
                runRecorderLoop(encoding, encodingFormat, sampleRate, bufferSizeBytes, bufferSamples)
            }, "SiphonDSP-RootlessAudio")

            recorderThread = worker
            worker.start()
            healthHandler.removeCallbacks(healthWatchdog)
            healthHandler.postDelayed(healthWatchdog, AudioPipelineHealth.WATCHDOG_INTERVAL_MS)
        }
    }

    private fun runRecorderLoop(
        encoding: AudioEncoding,
        encodingFormat: Int,
        sampleRate: Int,
        bufferSizeBytes: Int,
        bufferSamples: Int
    ) {
        // This is the actual real-time buffer loop (read -> native process() -> write) and had
        // never asked for elevated scheduling -- it ran at Android's default thread priority
        // (Process.THREAD_PRIORITY_DEFAULT, nice value 0; not Java's unrelated
        // Thread.NORM_PRIORITY = 5, a different scale this loop never set or read), competing
        // with everything else on the device for CPU time same as any background
        // worker. THREAD_PRIORITY_URGENT_AUDIO is the same class the platform's own audio HAL
        // callback threads use; a foreground service (see startForeground() above) is entitled to
        // ask for it. Doesn't fix any specific slow stage on its own, but widens the scheduling
        // margin against exactly the kind of transient CPU contention (GC, UI, other threads) that
        // shows up as random, worsening audio skips on a loop with no priority protection.
        //
        // Best-effort: this runs on the BMW head unit's own (likely customized/locked-down) AOSP
        // fork, not stock Android, so a SecurityException here should degrade to "keep running at
        // default priority" rather than take the whole audio thread down.
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: SecurityException) {
            Timber.w(e, "runRecorderLoop: platform refused THREAD_PRIORITY_URGENT_AUDIO, continuing at default priority")
        }

        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        var diagnostics = startupDiagnostics

        try {
            recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
            diagnostics?.recordRecorderCreated(recorder.state, recorder.recordingState, recorder.sampleRate)
            track = buildAudioTrack(encodingFormat, sampleRate, bufferSizeBytes)
            diagnostics?.recordTrackCreated(track.state, track.playState, track.sampleRate)
            synchronized(recorderLifecycleLock) {
                activeRecorder = recorder
                activeTrack = track
            }

            ServiceNotificationHelper.pushServiceNotification(applicationContext, arrayOf())

            val floatBuffer = FloatArray(bufferSamples)
            val floatOutBuffer = FloatArray(bufferSamples)
            val shortBuffer = ShortArray(bufferSamples)
            val shortOutBuffer = ShortArray(bufferSamples)
            val spectrumScratchDry = FloatArray(bufferSamples)
            val spectrumScratch = FloatArray(bufferSamples)

            while (!isProcessorDisposing && !isServiceDisposing) {
                if(recreateRecorderRequested) {
                    // Stop and release under recorderLifecycleLock so a concurrent
                    // stopRecording() call on another thread can't call stop() on
                    // this AudioRecord while it's being release()'d here (concurrent
                    // stop+release is UB, same hazard as the final-stop path below).
                    synchronized(recorderLifecycleLock) {
                        if (isProcessorDisposing || isServiceDisposing) break
                        recreateRecorderRequested = false
                        recreationInProgress = true
                        Timber.i("Recreating audio pipeline on existing worker: %s", recreationReason)
                        safeStop(recorder)
                        safeStop(track)
                        track?.flush()
                        safeRelease(recorder)
                        safeRelease(track)
                        activeRecorder = null
                        activeTrack = null
                        recorder = null
                        track = null
                        expectingReadInterruption = false
                    }

                    if (mediaProjection == null || isProcessorDisposing || isServiceDisposing) {
                        diagnostics?.finishIfDue(force = true)
                        Timber.e("Media projection handle is null, stopping recorder worker")
                        break
                    }

                    diagnostics = beginStartupDiagnostics(
                        "recreate: $recreationReason",
                        sampleRate,
                        encoding,
                        bufferSizeBytes,
                        bufferSamples,
                    )
                    recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
                    diagnostics.recordRecorderCreated(recorder.state, recorder.recordingState, recorder.sampleRate)
                    track = buildAudioTrack(encodingFormat, sampleRate, bufferSizeBytes)
                    diagnostics.recordTrackCreated(track.state, track.playState, track.sampleRate)
                    synchronized(recorderLifecycleLock) {
                        activeRecorder = recorder
                        activeTrack = track
                        recreationInProgress = false
                        // Only this worker resets its telemetry; old buffers cannot certify a
                        // replacement pipeline as healthy. A request arriving during build is
                        // retained for the next iteration.
                        lastSuccessfulRead = -1
                        lastSuccessfulProcess = -1
                        lastSuccessfulWrite = -1
                        consecutiveReadFailures = 0
                        consecutiveWriteFailures = 0
                        pipelineStartedAt = SystemClock.elapsedRealtime()
                    }
                    Timber.i("Audio pipeline recreated; awaiting successful flow")
                    continue
                }

                val currentRecorder = recorder ?: break
                val currentTrack = track ?: break

                if(isProcessorIdle && suspendOnIdle && !measGenActive &&
                    idleSinceMillis != 0L && SystemClock.elapsedRealtime() - idleSinceMillis >= IDLE_SUSPEND_DEBOUNCE_MS) {
                    diagnostics?.markExpectedIdle()
                    // Locked for the same reason stopRecording() and
                    // unblockRecorderForMeasurementGenerator() lock their own stop() calls: so
                    // this can never race either of those stopping/releasing the same
                    // AudioRecord/AudioTrack concurrently from another thread.
                    synchronized(recorderLifecycleLock) {
                        recorderSuspended = true
                        safeStop(currentRecorder)
                        safeStop(currentTrack)
                    }
                    currentTrack.flush()
                    try {
                        Thread.sleep(50)
                    }
                    catch(e: InterruptedException) {
                        if(isProcessorDisposing)
                            break
                    }
                    continue
                }

                synchronized(recorderLifecycleLock) {
                    if (isProcessorDisposing || isServiceDisposing) break
                    if (recreateRecorderRequested) continue
                    if (recorderSuspended) {
                        recorderSuspended = false
                        flowExpectedSince = SystemClock.elapsedRealtime()
                    }
                    if(currentRecorder.recordingState == AudioRecord.RECORDSTATE_STOPPED)
                        currentRecorder.startRecording()
                    diagnostics?.recordRecorderStarted(currentRecorder.recordingState)
                    if(currentTrack.playState != AudioTrack.PLAYSTATE_PLAYING)
                        currentTrack.play()
                    diagnostics?.recordTrackStarted(currentTrack.playState)
                }

                // The measurement generator fully replaces whatever's captured (see
                // NativeBmwDspProcessor::applyMeasurementGenerator(), called before anything else
                // in the native process() path), so it doesn't need real captured audio -- and
                // when no other app is actively playing, which is the normal case for a
                // deliberate measurement run, it never gets any: AudioPlaybackCaptureConfiguration
                // taps another app's live mixer output, so with none playing there's no mix
                // stream to tap, and recorder.read() below would just block forever, silently
                // starving the generator of any chance to run at all. Feed zero-filled input
                // instead; writeFully()'s blocking AudioTrack.write() further down still provides
                // real-time pacing since the output device drains at its own clock regardless.
                val generatedInput = measGenActive
                val readCount = if (generatedInput) {
                    if (encoding == AudioEncoding.PcmShort) shortBuffer.fill(0) else floatBuffer.fill(0f)
                    bufferSamples
                } else if(encoding == AudioEncoding.PcmShort)
                    currentRecorder.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)
                else
                    currentRecorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)

                diagnostics?.recordRead(readCount, generatedInput)
                if(readCount < 0) {
                    if(isProcessorDisposing)
                        break
                    // Only a deliberate stop is exempt from failure accounting.
                    if(expectingReadInterruption) {
                        expectingReadInterruption = false
                        continue
                    }
                    consecutiveReadFailures = (consecutiveReadFailures + 1)
                        .coerceAtMost(AudioPipelineHealth.MAX_CONSECUTIVE_FAILURES)
                    if (consecutiveReadFailures == 1)
                        Timber.w("AudioRecord.read failed with error %s", readCount)
                    Thread.sleep(AudioPipelineHealth.IO_RETRY_DELAY_MS)
                    continue
                }
                if(readCount == 0) {
                    Thread.sleep(AudioPipelineHealth.IO_RETRY_DELAY_MS)
                    continue
                }

                val processCount = readCount - (readCount % CHANNEL_COUNT)
                if(processCount <= 0)
                    continue

                if (!generatedInput) {
                    lastSuccessfulRead = SystemClock.elapsedRealtime()
                    consecutiveReadFailures = 0
                }
                if (recreateRecorderRequested || isProcessorDisposing || isServiceDisposing) continue

                val nativeReady = engine.isNativeHandleReady()
                diagnostics?.recordDspStart(nativeReady, !engine.enabled || !nativeReady)

                if(encoding == AudioEncoding.PcmShort) {
                    diagnostics?.recordShortInput(shortBuffer, processCount)
                    try {
                        engine.processInt16(shortBuffer, shortOutBuffer, 0, processCount)
                    } catch (e: Throwable) {
                        diagnostics?.recordDspFailure(e)
                        throw e
                    }
                    lastSuccessfulProcess = SystemClock.elapsedRealtime()
                    diagnostics?.recordDspSuccess(processCount)
                    diagnostics?.recordShortOutput(shortOutBuffer, processCount)
                    if(SpectrumEngine.isActive) {
                        for(i in 0 until processCount) {
                            spectrumScratchDry[i] = shortBuffer[i] / 32768f
                            spectrumScratch[i] = shortOutBuffer[i] / 32768f
                        }
                        SpectrumEngine.publish(spectrumScratchDry, spectrumScratch, processCount, sampleRate)
                    }
                    writeFully(currentTrack, shortOutBuffer, processCount, diagnostics)
                }
                else {
                    diagnostics?.recordFloatInput(floatBuffer, processCount)
                    try {
                        engine.processFloat(floatBuffer, floatOutBuffer, 0, processCount)
                    } catch (e: Throwable) {
                        diagnostics?.recordDspFailure(e)
                        throw e
                    }
                    lastSuccessfulProcess = SystemClock.elapsedRealtime()
                    diagnostics?.recordDspSuccess(processCount)
                    diagnostics?.recordFloatOutput(floatOutBuffer, processCount)
                    if(SpectrumEngine.isActive) SpectrumEngine.publish(floatBuffer, floatOutBuffer, processCount, sampleRate)
                    writeFully(currentTrack, floatOutBuffer, processCount, diagnostics)
                }
            }
        }
        catch (e: IOException) {
            diagnostics?.finishIfDue(force = true)
            if(!isProcessorDisposing) {
                Timber.e(e, "Audio worker I/O failure")
                stopServiceSafely()
            }
        }
        catch (e: Exception) {
            diagnostics?.finishIfDue(force = true)
            if(!isProcessorDisposing) {
                Timber.e(e, "Exception in recorder worker")
                stopServiceSafely()
            }
        }
        finally {
            diagnostics?.finishIfDue(force = true)
            healthHandler.removeCallbacks(startupDiagnosticsFinisher)
            // Null the shared references under the lock before releasing so an
            // overlapping stopRecording() call on another thread either sees null
            // (and no-ops) or has already finished its stop() call before we
            // release() below — the two can never run concurrently on the same object.
            synchronized(recorderLifecycleLock) {
                activeRecorder = null
                activeTrack = null
                recreationInProgress = false
            }
            safeStop(recorder)
            safeStop(track)
            safeRelease(recorder)
            safeRelease(track)

            val closeEngine = synchronized(recorderLifecycleLock) {
                if(recorderThread === Thread.currentThread())
                    recorderThread = null
                closeEngineOnWorkerExit
            }
            if (closeEngine) engine.close()
        }
    }

    private fun writeFully(
        track: AudioTrack,
        buffer: ShortArray,
        length: Int,
        diagnostics: StartupAudioDiagnostics?,
    ) {
        var offset = 0
        while(offset < length && !isProcessorDisposing && !isServiceDisposing && !recreateRecorderRequested) {
            val written = track.write(buffer, offset, length - offset, AudioTrack.WRITE_BLOCKING)
            diagnostics?.recordWrite(written)
            if (!recordWriteResult(written))
                continue
            offset += written
        }
    }

    private fun writeFully(
        track: AudioTrack,
        buffer: FloatArray,
        length: Int,
        diagnostics: StartupAudioDiagnostics?,
    ) {
        var offset = 0
        while(offset < length && !isProcessorDisposing && !isServiceDisposing && !recreateRecorderRequested) {
            val written = track.write(buffer, offset, length - offset, AudioTrack.WRITE_BLOCKING)
            diagnostics?.recordWrite(written)
            if (!recordWriteResult(written))
                continue
            offset += written
        }
    }

    private fun recordWriteResult(written: Int): Boolean {
        if (written > 0) {
            lastSuccessfulWrite = SystemClock.elapsedRealtime()
            consecutiveWriteFailures = 0
            return true
        }
        if (isProcessorDisposing || isServiceDisposing || recreateRecorderRequested) return false
        consecutiveWriteFailures = (consecutiveWriteFailures + 1)
            .coerceAtMost(AudioPipelineHealth.MAX_CONSECUTIVE_FAILURES)
        if (consecutiveWriteFailures == 1)
            Timber.w("AudioTrack.write made no progress: %s", written)
        Thread.sleep(AudioPipelineHealth.IO_RETRY_DELAY_MS)
        return false
    }

    fun stopRecording() {
        val worker: Thread?
        synchronized(recorderLifecycleLock) {
            healthHandler.removeCallbacks(healthWatchdog)
            isProcessorDisposing = true
            checkPipelineHealthLocked()
            worker = recorderThread
            if(worker == null)
                return
            isProcessorDisposing = true

            // Only stop() here to unblock the worker's blocking read()/write() calls; release() is
            // deliberately left to the worker's own finally block so the objects are never released
            // while that thread may still be using them (concurrent release+read/write is UB).
            // Done under the same lock the recreate-recorder path uses to release(), so this
            // stop() can never race that release() on the same AudioRecord.
            safeStop(activeRecorder)
            safeStop(activeTrack)
        }
        worker.interrupt()

        if(worker !== Thread.currentThread()) {
            val deadline = System.currentTimeMillis() + STOP_JOIN_TIMEOUT_MS
            while(worker.isAlive && System.currentTimeMillis() < deadline) {
                try {
                    worker.join(250)
                }
                catch(e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if(worker.isAlive)
                Timber.e("stopRecording: recorder worker did not terminate within ${STOP_JOIN_TIMEOUT_MS}ms")
        }

        synchronized(recorderLifecycleLock) {
            if(recorderThread === worker && !worker.isAlive)
                recorderThread = null
        }
    }

    fun restartRecording() {
        // Session callbacks may originate off-main. Serialize stop/join/start with service
        // commands and destruction; never start a second worker from the old worker itself.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            healthHandler.post { restartRecording() }
            return
        }
        if(isServiceDisposing) {
            Timber.e("restartRecording: service already disposing")
            return
        }

        stopRecording()
        if(recorderThread?.isAlive == true) {
            Timber.e("restartRecording: previous recorder worker did not terminate")
            stopServiceSafely()
            return
        }

        synchronized(recorderLifecycleLock) {
            if (isServiceDisposing) return
            isProcessorDisposing = false
            recreateRecorderRequested = false
            startRecording()
        }
    }

    private fun buildAudioTrack(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_UNKNOWN)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setFlags(0)

        sdkAbove(Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }

        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .build()

        return AudioTrack.Builder()
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioAttributes(attributesBuilder.build())
            .setBufferSizeInBytes(bufferSizeBytes)
            .build()
            .also {
                check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
                check(it.sampleRate == sampleRate) {
                    "AudioTrack sample rate ${it.sampleRate} does not match requested $sampleRate"
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioRecord {
        if (!hasRecordPermission())
            throw SecurityException("RECORD_AUDIO not granted")

        val projection = mediaProjection ?: throw IllegalStateException("Media projection is unavailable")
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        val excluded = (if(excludeRestrictedSessions)
            sessionManager.sessionPolicyDatabase.getRestrictedUids().toList()
        else {
            sessionManager.pollOnce(false)
            emptyList()
        }).toMutableList()

        blockedApps.value?.map { it.uid }?.let { excluded += it }
        excluded += Process.myUid()

        excluded.distinct().forEach { configBuilder.excludeUid(it) }
        sessionManager.sessionDatabase.setExcludedUids(excluded.distinct().toTypedArray())
        sessionManager.pollOnce(false)

        Timber.d("buildAudioRecord: Excluded UIDs: ${excluded.distinct().joinToString("; ")}")

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(configBuilder.build())
            .build()
            .also {
                check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
                check(it.sampleRate == sampleRate) {
                    "AudioRecord sample rate ${it.sampleRate} does not match requested $sampleRate"
                }
            }
    }

    private fun safeStop(recorder: AudioRecord?) {
        if(recorder == null)
            return
        try {
            if(recorder.state == AudioRecord.STATE_INITIALIZED &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                recorder.stop()
        }
        catch(_: IllegalStateException) {
        }
    }

    private fun safeStop(track: AudioTrack?) {
        if(track == null)
            return
        try {
            if(track.state == AudioTrack.STATE_INITIALIZED &&
                track.playState != AudioTrack.PLAYSTATE_STOPPED)
                track.stop()
        }
        catch(_: IllegalStateException) {
        }
    }

    private fun safeRelease(recorder: AudioRecord?) {
        try {
            recorder?.release()
        }
        catch(_: Exception) {
        }
    }

    private fun safeRelease(track: AudioTrack?) {
        try {
            track?.release()
        }
        catch(_: Exception) {
        }
    }

    private fun alignUp(value: Int, alignment: Int): Int {
        if(alignment <= 1)
            return value
        return ((value + alignment - 1) / alignment) * alignment
    }

    private fun determineSamplingRate(): Int {
        val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val sampleRate = sampleRateStr?.toIntOrNull()?.takeUnless { it == 0 } ?: 48000
        Timber.i("Real HAL sampling rate is $sampleRate")
        return sampleRate
    }

    private fun determineBufferSize(): Int {
        val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        return framesPerBuffer?.toIntOrNull()?.takeUnless { it == 0 } ?: 256
    }

    companion object {
        @Volatile private var activeInstance: RootlessAudioProcessorService? = null
        private const val CHANNEL_COUNT = 2
        private const val STOP_JOIN_TIMEOUT_MS = 2000L
        private const val IDLE_SUSPEND_DEBOUNCE_MS = 400L
        const val SESSION_LOSS_MAX_RETRIES = 1

        const val ACTION_START = BuildConfig.APPLICATION_ID + ".rootless.service.START"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".rootless.service.STOP"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData"
        const val EXTRA_APP_UID = "uid"
        const val EXTRA_APP_COMPAT_INTERNAL_CALL = "appCompatInternalCall"

        fun applyNativeBmwPeq(state: BmwPeqState): Boolean {
            val service = activeInstance ?: return false
            return service.engine.configureNativeBmwPeq(state)
        }

        fun nativeBmwPeqSampleRate(): Float? = activeInstance?.engine?.sampleRate

        fun nativeBmwPeqHandleReady(): Boolean? = activeInstance?.engine?.isNativeHandleReady()

        fun nativeBmwCompressorMeter(): FloatArray? =
            activeInstance?.engine?.nativeBmwCompressorMeter()

        fun nativeBmwMbcMeter(): FloatArray? =
            activeInstance?.engine?.nativeBmwMbcMeter()

        fun nativeBmwBusLimiterMeter(): FloatArray? =
            activeInstance?.engine?.nativeBmwBusLimiterMeter()

        fun nativeBmwMasterLimiterMeter(): FloatArray? =
            activeInstance?.engine?.nativeBmwMasterLimiterMeter()

        fun startNativeBmwCapture(): Boolean {
            val service = activeInstance ?: return false
            service.engine.startNativeBmwCapture()
            return true
        }

        fun stopNativeBmwCapture() {
            activeInstance?.engine?.stopNativeBmwCapture()
        }

        fun nativeBmwCaptureFrameCount(): Long? = activeInstance?.engine?.nativeBmwCaptureFrameCount()

        fun exportNativeBmwCaptureWav(rawInPath: String, outPath: String): FloatArray? =
            activeInstance?.engine?.exportNativeBmwCaptureWav(rawInPath, outPath)

        fun start(context: Context, data: Intent?) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStartIntent(context, data))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }

        fun stop(context: Context) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStopIntent(context))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }
    }
}
