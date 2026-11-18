package app.siphondsp.session.shared

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioPlaybackConfigurationHidden
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.CallSuper
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.rikka.tools.refine.Refine
import app.siphondsp.R
import app.siphondsp.model.preference.SessionUpdateMode
import app.siphondsp.service.NotificationListenerService
import app.siphondsp.session.dump.DumpManager
import app.siphondsp.session.dump.data.ISessionInfoDump
import app.siphondsp.session.rootless.SessionMuteLog
import app.siphondsp.utils.AppEvent
import app.siphondsp.utils.AppEvents
import app.siphondsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber


abstract class BaseSessionManager(protected val context: Context) : DumpManager.OnDumpMethodChangeListener,
    MediaSessionManager.OnActiveSessionsChangedListener, KoinComponent
{
    // System services
    private val audioManager = context.getSystemService<AudioManager>()!!
    private val sessionManager = context.getSystemService<MediaSessionManager>()!!

    // Session dump manager
    protected val dumpManager: DumpManager by inject()

    // Session polling settings
    private var sessionUpdateMode: SessionUpdateMode = SessionUpdateMode.Listener
        set(value) {
            field = value
            updatePollingMode()
        }
    private var pollingTimeout = 3000L

    // Polling job
    private val pollingMutex = Mutex()
    private val pollingScope = CoroutineScope(Dispatchers.Main)
    private var continuousPollingJob: Job? = null
    private val dumpRequests = CoalescingDumpRequests(pollingScope) { pollSessionDump() }

    // Each player's last state, by interface id, to tell a player starting from any other
    // playback change. Main thread (the callback's handler).
    private val playerStates = HashMap<Int, Int>()

    // Callbacks
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    // Preferences
    private val preferencesListener: SharedPreferences.OnSharedPreferenceChangeListener
    private val preferences: Preferences.App by inject()

    /** Applies a dump; main thread. */
    protected abstract fun handleSessionDump(sessionDump: ISessionInfoDump?)

    /**
     * Takes the session dump, off the main thread: a system dump blocks for hundreds of ms on
     * a head unit, and on the main thread it held up everything else waiting there -- the
     * playback callback and the session-open broadcast included. Anything else that blocks on a
     * dump goes here too, for [handleSessionDump] to use.
     */
    protected open fun dumpOnWorker(): ISessionInfoDump? = dumpManager.dumpSessions()

    /** A player in the playback callback that has just started. [sessionId]/[uid] are 0/-1 unless visible to us. */
    protected data class StartedPlayer(val sessionId: Int, val uid: Int, val attributes: AudioAttributes)

    /**
     * The playback callback reported players starting ([players]; empty if it couldn't say
     * which). Main thread, before any dump is taken: the place to mute them at once.
     */
    protected open fun onPlayersStarted(players: List<StartedPlayer>) {}

    /** [packageName] opened audio-effect session [sessionId] (its broadcast). Main thread, before any dump. */
    protected open fun onSessionOpened(sessionId: Int, packageName: String) {}

    init {
        Timber.d("Initializing SessionDumpManager")

        // Notify on playback changes
        audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                super.onPlaybackConfigChanged(configs)

                val now = SystemClock.elapsedRealtime()
                val started = startedPlayers(configs)
                Timber.d("Playback config changed (started: ${started?.size ?: "?"})")
                // Only a player starting can play unmuted: a pause or a stop doesn't start the
                // clock. A change that can't be read is taken as a start, to be safe.
                if (started == null || started.isNotEmpty()) {
                    SessionMuteLog.notePlaybackChange(now)
                    onPlayersStarted(started.orEmpty())
                }
                requestDump()
            }
        }
        audioManager.registerAudioPlaybackCallback(audioPlaybackCallback!!, Handler(Looper.getMainLooper()))


        // Register callbacks
        dumpManager.registerOnDumpMethodChangeListener(this)
        pollingScope.launch {
            AppEvents.of<AppEvent.AudioEffectSessionChanged>().collect { onAudioEffectSessionChanged(it) }
        }
        installNotificationListenerService()

        // Setup preferences
        preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
                _, key ->
            loadFromPreferences(key)
        }
        loadFromPreferences(context.getString(R.string.key_session_continuous_polling))
        loadFromPreferences(context.getString(R.string.key_session_continuous_polling_rate))
        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
    }

    @CallSuper
    open fun destroy()
    {
        Timber.d("Destroying SessionDumpManager")

        dumpManager.unregisterOnDumpMethodChangeListener(this)
        // Held strongly by the settings store: without this, every destroyed manager lived on.
        preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)

        audioPlaybackCallback?.let { audioManager.unregisterAudioPlaybackCallback(it) }

        sessionManager.removeOnActiveSessionsChangedListener(this)

        continuousPollingJob?.cancel()
        pollingScope.cancel()
    }

    private fun loadFromPreferences(key: String?){
        when (key) {
            context.getString(R.string.key_session_continuous_polling) -> {
                sessionUpdateMode =
                    if (preferences.get<Boolean>(R.string.key_session_continuous_polling))
                        SessionUpdateMode.ContinuousPolling
                    else
                        SessionUpdateMode.Listener
                Timber.d("Session update mode set to ${sessionUpdateMode.name}")
            }
            context.getString(R.string.key_session_continuous_polling_rate) -> {
                pollingTimeout = R.string.key_session_continuous_polling_rate.let {
                    preferences.get<String>(it).toLongOrNull()
                        ?: preferences.getDefault<String>(it).toLong()
                }.coerceAtLeast(MIN_POLLING_INTERVAL_MS) // 0 would poll in a tight loop on the main thread

                continuousPollingJob?.cancel()
                updatePollingMode()
                Timber.d("Session polling interval set to ${pollingTimeout}ms")
            }
        }
    }

    private fun installNotificationListenerService() {
        val hasPermission = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        Timber.d("Notification listener permission granted? $hasPermission")
        try {
            if (hasPermission) {
                sessionManager.addOnActiveSessionsChangedListener(
                    this,
                    ComponentName(context, NotificationListenerService::class.java)
                )
            } else {
                sessionManager.removeOnActiveSessionsChangedListener(this)
            }
        }
        catch (ex: SecurityException) {
            Timber.e("installNotificationListenerService: SecurityException; missing permission")
        }
    }

    private fun updatePollingMode()
    {
        when (sessionUpdateMode) {
            SessionUpdateMode.ContinuousPolling -> {
                // Cancel any prior loop first, and have the loop check its own job's isActive
                // rather than the shared continuousPollingJob field -- a duplicate call here (e.g.
                // a spurious repeat SharedPreferences change callback) previously left the old
                // loop believing it was still current and running forever alongside the new one.
                continuousPollingJob?.cancel()
                continuousPollingJob = pollingScope.launch {
                    while(isActive)
                    {
                        pollSessionDump()
                        delay(pollingTimeout)
                    }
                }
            }
            SessionUpdateMode.Listener -> {
                continuousPollingJob?.cancel()
            }
        }
    }

    private suspend fun pollSessionDump()
    {
        pollingMutex.withLock {
            val startedAtMs = SystemClock.elapsedRealtime()
            val dump = withContext(Dispatchers.IO) { dumpOnWorker() }
            handleSessionDump(dump)
            if (dump != null) SessionMuteLog.noteDumpApplied(startedAtMs)
        }
    }

    /** May be called by the recorder worker; the queue dispatches all bookkeeping to main. */
    private fun requestDump() = dumpRequests.request()

    /** [blocking] is kept for callers; every request is now coalesced, never dropped. */
    @Suppress("UNUSED_PARAMETER")
    fun pollOnce(blocking: Boolean) = requestDump()

    /**
     * The players in [configs] that have just started, with a recordable usage; null if the
     * states can't be read. Session ids and uids come anonymized (0/-1) to an app without
     * MODIFY_AUDIO_ROUTING, but the interface id and state don't.
     */
    private fun startedPlayers(configs: List<AudioPlaybackConfiguration>?): List<StartedPlayer>? {
        configs ?: return null
        return try {
            val states = HashMap<Int, Int>(configs.size)
            val started = mutableListOf<StartedPlayer>()
            configs.forEach { c ->
                val h = Refine.unsafeCast<AudioPlaybackConfigurationHidden>(c)
                val piid = h.playerInterfaceId
                val state = h.playerState
                states[piid] = state
                if (state == PLAYER_STATE_STARTED && playerStates[piid] != PLAYER_STATE_STARTED &&
                    c.audioAttributes.usage in RECORDABLE_USAGES) {
                    started += StartedPlayer(
                        sessionId = runCatching { h.sessionId }.getOrDefault(0),
                        uid = runCatching { h.clientUid }.getOrDefault(-1),
                        attributes = c.audioAttributes,
                    )
                }
            }
            playerStates.clear()
            playerStates.putAll(states)
            started
        } catch (t: Throwable) {
            Timber.w(t, "startedPlayers: playback states unreadable")
            null
        }
    }

    /** Current metadata only: never guess a session's usage from its previous owner. */
    protected fun findPlaybackSession(sessionId: Int): StartedPlayer? = runCatching {
        audioManager.activePlaybackConfigurations.firstNotNullOfOrNull { config ->
            val hidden = Refine.unsafeCast<AudioPlaybackConfigurationHidden>(config)
            if (hidden.sessionId == sessionId) {
                StartedPlayer(sessionId, hidden.clientUid, config.audioAttributes)
            } else null
        }
    }.getOrNull()

    @CallSuper
    override fun onDumpMethodChange(method: DumpManager.Method) {
        requestDump()
    }

    private fun onAudioEffectSessionChanged(event: AppEvent.AudioEffectSessionChanged) {
        // The broadcast identifies the session, but eligibility still needs playback metadata.
        if (event.sessionId < 0) return
        Timber.d("Audio effect control session ${if (event.opened) "open" else "close"} broadcast: ${event.sessionId} (${event.packageName})")
        val pkg = event.packageName
        if (event.opened && event.sessionId > 0 && !pkg.isNullOrEmpty()) onSessionOpened(event.sessionId, pkg)
        requestDump()
    }

    @SuppressLint("BinaryOperationInTimber")
    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        controllers ?: return
        controllers.forEach {
            Timber.d("active session changed: package ${it.packageName}; " +
                    // Refine is not working with nested classes anymore
                    // "uid ${Refine.unsafeCast<MediaSessionHidden.TokenHidden>(it.sessionToken).uid}; " +
                    "usage ${it.playbackInfo.audioAttributes?.usage}")
        }

        requestDump()
    }

    private companion object {
        const val MIN_POLLING_INTERVAL_MS = 100L
        // AudioPlaybackConfiguration.PLAYER_STATE_STARTED (hidden).
        const val PLAYER_STATE_STARTED = 2
        // What the dumps accept (AudioSessionDumpEntry.isUsageRecordable).
        val RECORDABLE_USAGES = setOf(
            AudioAttributes.USAGE_UNKNOWN, AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME,
        )
    }
}
