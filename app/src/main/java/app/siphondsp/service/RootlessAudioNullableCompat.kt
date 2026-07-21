package app.siphondsp.service

import android.media.AudioRecord
import android.media.AudioTrack
import java.io.IOException

/**
 * Nullable receiver overloads for the rootless worker. The recorder is mutable
 * because it can be replaced while the worker remains alive, and both audio
 * objects are cleared during shutdown.
 */
internal val AudioRecord?.recordingState: Int
    get() = this?.recordingState ?: AudioRecord.RECORDSTATE_STOPPED

internal val AudioTrack?.playState: Int
    get() = this?.playState ?: AudioTrack.PLAYSTATE_STOPPED

internal fun AudioRecord?.startRecording() {
    this?.startRecording()
}

internal fun AudioTrack?.play() {
    this?.play()
}

internal fun AudioRecord?.read(
    buffer: ShortArray,
    offset: Int,
    length: Int,
    mode: Int
): Int = this?.read(buffer, offset, length, mode) ?: AudioRecord.ERROR_INVALID_OPERATION

internal fun AudioRecord?.read(
    buffer: FloatArray,
    offset: Int,
    length: Int,
    mode: Int
): Int = this?.read(buffer, offset, length, mode) ?: AudioRecord.ERROR_INVALID_OPERATION

internal fun writeFully(track: AudioTrack?, buffer: ShortArray, length: Int) {
    val output = track ?: throw IOException("AudioTrack is unavailable")
    var offset = 0
    while(offset < length) {
        val written = output.write(buffer, offset, length - offset, AudioTrack.WRITE_BLOCKING)
        if(written < 0)
            throw IOException("AudioTrack.write failed with error $written")
        if(written == 0)
            continue
        offset += written
    }
}

internal fun writeFully(track: AudioTrack?, buffer: FloatArray, length: Int) {
    val output = track ?: throw IOException("AudioTrack is unavailable")
    var offset = 0
    while(offset < length) {
        val written = output.write(buffer, offset, length - offset, AudioTrack.WRITE_BLOCKING)
        if(written < 0)
            throw IOException("AudioTrack.write failed with error $written")
        if(written == 0)
            continue
        offset += written
    }
}
