package app.siphondsp.service

import android.media.AudioRecord
import android.media.AudioTrack
import java.io.IOException

/**
 * Nullable audio helpers used by the rootless worker while its recorder can be
 * replaced and while shutdown may clear/release the active objects.
 */
internal val AudioRecord?.recordingStateCompat: Int
    get() = this?.recordingState ?: AudioRecord.RECORDSTATE_STOPPED

internal val AudioTrack?.playStateCompat: Int
    get() = this?.playState ?: AudioTrack.PLAYSTATE_STOPPED

internal fun AudioRecord?.startRecordingCompat() {
    this?.startRecording()
}

internal fun AudioTrack?.playCompat() {
    this?.play()
}

internal fun AudioRecord?.readCompat(
    buffer: ShortArray,
    offset: Int,
    length: Int,
    mode: Int
): Int = this?.read(buffer, offset, length, mode) ?: AudioRecord.ERROR_INVALID_OPERATION

internal fun AudioRecord?.readCompat(
    buffer: FloatArray,
    offset: Int,
    length: Int,
    mode: Int
): Int = this?.read(buffer, offset, length, mode) ?: AudioRecord.ERROR_INVALID_OPERATION

internal fun AudioTrack?.writeFullyCompat(buffer: ShortArray, length: Int) {
    val output = this ?: throw IOException("AudioTrack is unavailable")
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

internal fun AudioTrack?.writeFullyCompat(buffer: FloatArray, length: Int) {
    val output = this ?: throw IOException("AudioTrack is unavailable")
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
