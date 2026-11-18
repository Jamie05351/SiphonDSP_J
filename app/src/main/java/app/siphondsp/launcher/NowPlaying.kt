package app.siphondsp.launcher

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import app.siphondsp.service.NotificationListenerService as SiphonNotificationListener

/** What is playing, for the home screen's music tile. */
@Immutable
data class Track(
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    val packageName: String,
)

/**
 * The media session the home screen shows: the one that is playing, else the most recent one.
 *
 * Android only lists other apps' media sessions to an enabled notification listener; J_DSP
 * already has one ([SiphonNotificationListener]). Until the user allows it, [allowed] is false and
 * there is no track. Listening only happens between [start] and [stop] (while the home screen is
 * up), on the main thread.
 */
class NowPlaying(context: Context) {
    private val app = context.applicationContext
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(app, SiphonNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var started = false

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()
    private val prefs = app.getSharedPreferences("now_playing", Context.MODE_PRIVATE)
    private val _lastPlayer = MutableStateFlow(prefs.getString(KEY_LAST_PLAYER, null))
    /**
     * The package of the last app seen playing, kept across restarts, so the music tile can still
     * name a player when no session is left.
     */
    val lastPlayer: StateFlow<String?> = _lastPlayer.asStateFlow()

    private val _allowed = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Another session may now be the one playing.
            if (state?.state != PlaybackState.STATE_PLAYING) refresh() else publish()
        }
        override fun onSessionDestroyed() = refresh()
    }

    fun start() {
        if (started) return
        started = true
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            _allowed.value = true
            refresh()
        } catch (e: SecurityException) {
            // Notification access not granted yet.
            _allowed.value = false
            _track.value = null
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (e: RuntimeException) {
            Timber.d(e, "Session listener already gone")
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    fun playPause() {
        val c = controller
        if (c == null) {
            // No session left: a media Play key goes to the app that last played, which resumes it.
            val audio = app.getSystemService(AudioManager::class.java) ?: return
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
            return
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    private fun refresh() {
        try {
            pick(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            _allowed.value = false
            _track.value = null
        }
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        _track.value = if (c == null || title.isNullOrBlank()) {
            null
        } else {
            val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            Track(
                title = title,
                artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                art = art?.asImageBitmap(),
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                packageName = c.packageName,
            )
        }
        val player = c?.packageName
        if (player != null && player != _lastPlayer.value) {
            _lastPlayer.value = player
            prefs.edit { putString(KEY_LAST_PLAYER, player) }
        }
    }

    private companion object {
        const val KEY_LAST_PLAYER = "last_player"
    }
}
