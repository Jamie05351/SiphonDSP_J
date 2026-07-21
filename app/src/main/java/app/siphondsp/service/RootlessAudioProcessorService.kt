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
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import app.siphondsp.BuildConfig
import app.siphondsp.R
import app.siphondsp.flavor.CrashlyticsImpl
import app.siphondsp.interop.JamesDspLocalEngine
import app.siphondsp.interop.ProcessorMessageHandler
import app.siphondsp.model.IEffectSession
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
import app.siphondsp.utils.Constants.ACTION_PREFERENCES_UPDATED
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
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException

@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : BaseAudioProcessorService() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null

    private val recorderLifecycleLock = Any()

    @Volatile
    private var recreateRecorderRequested = false

    @Volatile
    private var recorderThread: Thread? = null

    @Volatile
    private var activeRecorder: AudioRecord? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    private lateinit var engine: JamesDspLocalEngine
    private val isRunning: Boolean
        get() = recorderThread?.isAlive == true

    private lateinit var sessionManager: RootlessSessionManager
    private var sessionLossRetryCount = 0

    @Volatile
    private var isProcessorIdle = false

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
        Timber.d("blockedAppObserver: Database changed; ignored=${!isRunning}")
        if(isRunning)
            recreateRecorderRequested = true
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService<AudioManager>()!!
        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        sessionManager = RootlessSessionManager(this)
        sessionManager.sessionDatabase.setOnSessionLossListener(onSessionLossListener)
        sessionManager.sessionDatabase.setOnAppProblemListener(onAppProblemListener)
        sessionManager.sessionDatabase.registerOnSessionChangeListener(onSessionChangeListener)
        sessionManager.sessionPolicyDatabase.registerOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)

        engine = JamesDspLocalEngine(this, ProcessorMessageHandler())
        engine.syncWithPreferences()

        val filter = IntentFilter()
        filter.addAction(ACTION_PREFERENCES_UPDATED)
        filter.addAction(ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(ACTION_SERVICE_SOFT_REBOOT_CORE)
        registerLocalReceiver(broadcastReceiver, filter)

        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        loadFromPreferences(getString(R.string.key_powersave_suspend))
        loadFromPreferences(getString(R.string.key_session_exclude_restricted))

        blockedApps.observeForever(blockedAppObserver)
        notificationManager.cancel(Notifications.ID_SERVICE_STARTUP)
        recreateRecorderRequested = false

        startForeground(
            Notifications.ID_SERVICE_STATUS,
            ServiceNotificationHelper.createServiceNotification(this, arrayOf()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand")

        when (intent.action) {
            null -> Timber.wtf("onStartCommand: intent.action is null")
            ACTION_START -> Timber.d("Starting service")
            ACTION_STOP -> {
                Timber.d("Stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning)
            return START_NOT_STICKY

        notificationManager.cancel(Notifications.ID_SERVICE_SESSION_LOSS)
        notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)

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

        if (mediaProjection != null) {
            startRecording()
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        }
        else {
            Timber.w("Failed to capture audio")
            stopSelf()
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        isServiceDisposing = true
        stopRecording()
        engine.close()

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
            stopSelf()
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
            }
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
                stopSelf()
            }
        }
    }

    private val onSessionChangeListener = object : OnRootlessSessionChangeListener {
        override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
            isProcessorIdle = sessionList.isEmpty()
            if(!isProcessorIdle)
                sessionLossRetryCount = 0

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
                stopSelf()
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

    fun requestAudioRecordRecreation() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("recreateAudioRecorder: service or processor already disposing")
            return
        }
        recreateRecorderRequested = true
    }

    @SuppressLint("BinaryOperationInTimber")
    private fun startRecording() {
        synchronized(recorderLifecycleLock) {
            if(recorderThread?.isAlive == true) {
                Timber.w("startRecording: recorder thread already running")
                return
            }

            if (!hasRecordPermission()) {
                Timber.e("Record audio permission missing. Can't record")
                stopSelf()
                return
            }

            isProcessorDisposing = false
            recreateRecorderRequested = false

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

            val worker = Thread({
                runRecorderLoop(encoding, encodingFormat, sampleRate, bufferSizeBytes, bufferSamples)
            }, "SiphonDSP-RootlessAudio")

            recorderThread = worker
            worker.start()
        }
    }

    private fun runRecorderLoop(
        encoding: AudioEncoding,
        encodingFormat: Int,
        sampleRate: Int,
        bufferSizeBytes: Int,
        bufferSamples: Int
    ) {
        var recorder: AudioRecord? = null
        var track: AudioTrack? = null

        try {
            recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
            track = buildAudioTrack(encodingFormat, sampleRate, bufferSizeBytes)
            activeRecorder = recorder
            activeTrack = track

            ServiceNotificationHelper.pushServiceNotification(applicationContext, arrayOf())

            val floatBuffer = FloatArray(bufferSamples)
            val floatOutBuffer = FloatArray(bufferSamples)
            val shortBuffer = ShortArray(bufferSamples)
            val shortOutBuffer = ShortArray(bufferSamples)

            while (!isProcessorDisposing) {
                if(recreateRecorderRequested) {
                    recreateRecorderRequested = false
                    Timber.d("Recreating recorder without replacing worker thread...")

                    safeStop(recorder)
                    safeStop(track)
                    safeRelease(recorder)
                    activeRecorder = null

                    if (mediaProjection == null || isProcessorDisposing) {
                        Timber.e("Media projection handle is null, stopping recorder worker")
                        break
                    }

                    recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
                    activeRecorder = recorder
                    Timber.d("Recorder recreated")
                }

                if(isProcessorIdle && suspendOnIdle) {
                    safeStop(recorder)
                    safeStop(track)
                    try {
                        Thread.sleep(50)
                    }
                    catch(e: InterruptedException) {
                        if(isProcessorDisposing)
                            break
                    }
                    continue
                }

                if(recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED)
                    recorder.startRecording()
                if(track.playState != AudioTrack.PLAYSTATE_PLAYING)
                    track.play()

                val readCount = if(encoding == AudioEncoding.PcmShort)
                    recorder.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)
                else
                    recorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)

                if(readCount < 0) {
                    if(isProcessorDisposing)
                        break
                    throw IOException("AudioRecord.read failed with error $readCount")
                }
                if(readCount == 0)
                    continue

                val processCount = readCount - (readCount % CHANNEL_COUNT)
                if(processCount <= 0)
                    continue

                if(encoding == AudioEncoding.PcmShort) {
                    engine.processInt16(shortBuffer, shortOutBuffer, 0, processCount)
                    writeFully(track, shortOutBuffer, processCount)
                }
                else {
                    engine.processFloat(floatBuffer, floatOutBuffer, 0, processCount)
                    writeFully(track, floatOutBuffer, processCount)
                }
            }
        }
        catch (e: IOException) {
            if(!isProcessorDisposing) {
                Timber.e(e, "Audio worker I/O failure")
                stopSelf()
            }
        }
        catch (e: Exception) {
            if(!isProcessorDisposing) {
                Timber.e(e, "Exception in recorder worker")
                stopSelf()
            }
        }
        finally {
            activeRecorder = null
            activeTrack = null
            safeStop(recorder)
            safeStop(track)
            safeRelease(recorder)
            safeRelease(track)

            synchronized(recorderLifecycleLock) {
                if(recorderThread === Thread.currentThread())
                    recorderThread = null
            }
        }
    }

    private fun writeFully(track: AudioTrack, buffer: ShortArray, length: Int) {
        var offset = 0
        while(offset < length && !isProcessorDisposing) {
            val written = track.write(buffer, offset, length - offset, AudioTrack.WRITE_BLOCKING)
            if(written < 0)
                throw IOException("AudioTrack.write failed with error $written")
            if(written == 0)
                continue
            offset += written
        }
    }

    private fun writeFully(track: AudioTrack, buffer: FloatArray, length: Int) {
        var offset = 0
        while(offset < length && !isProcessorDisposing) {
            val written = track.write(buffer, offset, length - offset, AudioTrack.WRITE_BLOCKING)
            if(written < 0)
                throw IOException("AudioTrack.write failed with error $written")
            if(written == 0)
                continue
            offset += written
        }
    }

    fun stopRecording() {
        val worker: Thread?
        synchronized(recorderLifecycleLock) {
            worker = recorderThread
            if(worker == null)
                return
            isProcessorDisposing = true
        }

        safeStop(activeRecorder)
        safeStop(activeTrack)
        safeRelease(activeRecorder)
        safeRelease(activeTrack)
        worker.interrupt()

        if(worker !== Thread.currentThread()) {
            while(worker.isAlive) {
                try {
                    worker.join(250)
                }
                catch(e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        synchronized(recorderLifecycleLock) {
            if(recorderThread === worker && !worker.isAlive)
                recorderThread = null
        }
    }

    fun restartRecording() {
        if(isServiceDisposing) {
            Timber.e("restartRecording: service already disposing")
            return
        }

        stopRecording()
        if(recorderThread?.isAlive == true) {
            Timber.e("restartRecording: previous recorder worker did not terminate")
            stopSelf()
            return
        }

        isProcessorDisposing = false
        recreateRecorderRequested = false
        startRecording()
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
        private const val CHANNEL_COUNT = 2
        const val SESSION_LOSS_MAX_RETRIES = 1

        const val ACTION_START = BuildConfig.APPLICATION_ID + ".rootless.service.START"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".rootless.service.STOP"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData"
        const val EXTRA_APP_UID = "uid"
        const val EXTRA_APP_COMPAT_INTERNAL_CALL = "appCompatInternalCall"

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
