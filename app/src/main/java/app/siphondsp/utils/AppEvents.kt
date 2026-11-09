package app.siphondsp.utils

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.Flow

/** Something one part of the app tells the others (the service, the screens, the tile). */
sealed interface AppEvent {
    /**
     * A DSP edit: every value, as now stored. [origin], when set, names the sender so it can
     * recognise its own echo.
     */
    class NativeBmwDspUpdated(val values: FloatArray, val origin: String? = null) : AppEvent

    /** The stored PEQ changed (an edit, an import, the engine closing). */
    data object ParametricEqChanged : AppEvent

    /** The whole stored config was replaced: a preset, a profile, a revert or a backup restore. */
    data object PresetLoaded : AppEvent

    /** A backup was restored (sent after [PresetLoaded]). */
    data object BackupRestored : AppEvent

    /** A player opened or closed an audio-effect session (the system's control broadcast). */
    data class AudioEffectSessionChanged(val sessionId: Int, val packageName: String?) : AppEvent

    /** The capture's buffer size or encoding changed: rebuild the capture pipeline. */
    data object RestartCapture : AppEvent

    data object ServiceStarted : AppEvent
    data object ServiceStopped : AppEvent
}

/**
 * The app's in-process event bus (in place of LocalBroadcastManager). Like the local broadcasts
 * it replaces, an event reaches only the collectors active when it is sent. Collect on
 * `Dispatchers.Main` (or in a composition), not `Main.immediate`: then, as with the broadcasts, a
 * collector gets the event after the sending call has returned, never inside it.
 */
object AppEvents {
    private val events = MutableSharedFlow<AppEvent>(
        extraBufferCapacity = 64,
        // A collector that falls behind loses the oldest events rather than blocking a sender
        // (the audio service sends from its threads).
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val flow: SharedFlow<AppEvent> = events.asSharedFlow()

    fun send(event: AppEvent) {
        events.tryEmit(event)
    }

    inline fun <reified T : AppEvent> of(): Flow<T> = flow.filterIsInstance<T>()
}
