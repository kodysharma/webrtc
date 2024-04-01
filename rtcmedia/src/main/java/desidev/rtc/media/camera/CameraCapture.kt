package desidev.rtc.media.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

interface CameraCapture {
    val state: State
    val selectedCamera: CameraDeviceInfo
    suspend fun start()
    suspend fun stop()
    suspend fun release()
    suspend fun selectCamera(cameraFace: CameraLensFacing)
    fun setPreviewFrameListener(listener: ((Bitmap) -> Unit)?)
    fun compressChannel(): ReceiveChannel<Pair<ByteArray, BufferInfo>>
    fun getMediaFormat(): Deferred<MediaFormat>

    companion object {
        fun create(context: Context): CameraCapture {
            return CameraCaptureImpl(context)
        }
    }

    sealed class Quality(val pixelElems: Int) {
        data object Lowest : Quality(480 *  360)
        data object Low : Quality(640 * 480)
        data object Medium : Quality(1280 * 720)
        data object High : Quality(1920 * 1080)
    }

    enum class State {
        ACTIVE, INACTIVE, RELEASED
    }
}


