package desidev.rtc.media.camera

import android.content.Context
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

interface CameraCapture {
    val selectedCamera: StateFlow<CameraDeviceInfo>
    val state: StateFlow<CameraState>
    val isCaptureRunning: StateFlow<Boolean>

    /**
     * Opening the camera don't start encoding frames but you can see the preview.
     * Use [PreviewView] composable to show the preview after calling this method.
     */
    suspend fun openCamera()

    /**
     * Close the camera and stop encoding frames if encoder is running.
     * PreviewView will not show the preview after calling this method.
     * Camera state will be [CameraState.INACTIVE].
     */
    suspend fun closeCamera()

    /**
     * Start encoding frames to the output channel.
     * You can receive the compressed frames, call the method [compressChannel].
     * You would also need the format of the compressed frames, call the method [getMediaFormat].
     *
     * if the camera is not opened, this method will open the camera and start encoding frames.
     */
    suspend fun startCapture()
    suspend fun stopCapture()

    /**
     * Release resources once called don't use the camera anymore.'
     */
    suspend fun release()

    /**
     * Get all the available cameras device information.
     */
    fun getCameras(): List<CameraDeviceInfo>
    suspend fun switchCamera(info: CameraDeviceInfo)
    fun compressChannel(): ReceiveChannel<Pair<ByteArray, BufferInfo>>
    fun getMediaFormat(): Deferred<MediaFormat>

    @Composable
    fun PreviewView(modifier: Modifier)

    companion object {
        fun create(context: Context): CameraCapture {
            return CameraCaptureImpl(context)
        }
    }

    sealed class Quality(val pixelElems: Int) {
        data object Lowest : Quality(240 * 320)
        data object Low : Quality(640 * 480)
        data object Medium : Quality(1280 * 720)
        data object High : Quality(1920 * 1080)
    }

    enum class CameraState {
        ACTIVE, INACTIVE, RELEASED
    }
}


