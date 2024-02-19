package desidev.videocall.service

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import desidev.videocall.service.bitmap.BitmapPool
import desidev.videocall.service.bitmap.BitmapWrapper
import desidev.videocall.service.yuv.YuvToRgbConverter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


/**
 * This is the default implementation of [VideoCallService].
 *
 * ```
 * // Get the default implementation of [VideoCallService]
 * val videoCallService = VideoCallService.getDefault()
 * ```
 */
class DefaultVideoCallService internal constructor(
    context: Context
) : VideoCallService {
    private val handlerThread = HandlerThread("DefaultVideoCallService")
    private val handler = ServiceHandler(handlerThread.looper)
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val settings = Settings()
    private val yuvToRgbConverter = YuvToRgbConverter(context)
    private val bitmapPool = BitmapPool(OUTPUT_SIZE)

    private val cameraOutput = CameraOutput()

    override val isAudioMuted: StateFlow<Boolean> = settings.isAudioMuted
    override val isVideoMuted: StateFlow<Boolean> = settings.isVideoMuted
    override val cameraFace: StateFlow<CameraFace> = settings.cameraFace


    init {
        handlerThread.start()
    }

    @Composable
    override fun ViewContent(modifier: Modifier) {
        Box(modifier = modifier) {

        }
    }

    @Composable
    override fun PreviewContent(modifier: Modifier) {
        Box {

        }
    }

    override fun switchCamera(camera: CameraFace) {
        handler.send(Event.SwitchCamera(camera))
    }

    override fun muteAudio() {
        TODO("Not yet implemented")
    }

    override fun muteVideo() {
        TODO("Not yet implemented")
    }

    override fun unMuteAudio() {
        TODO("Not yet implemented")
    }

    override fun unMuteVideo() {
        TODO("Not yet implemented")
    }

    override fun setRingRingListener(callback: RingRingListener) {
        TODO("Not yet implemented")
    }

    override fun setCallOfferResultListener(callback: CallOfferResultListener) {
        TODO("Not yet implemented")
    }

    override fun call(offer: CallOffer) {
        TODO("Not yet implemented")
    }

    override fun hangUp() {
        TODO("Not yet implemented")
    }


    @Suppress("MissingPermission")
    private fun openCamera() {
        val selectedCameraLensFace = when (cameraFace.value) {
            CameraFace.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CameraFace.BACK -> CameraCharacteristics.LENS_FACING_BACK
        }

        val id = cameraManager.cameraIdList.find { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == selectedCameraLensFace
        } ?: throw IllegalStateException("No camera found")

        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
            }

            override fun onDisconnected(camera: CameraDevice) {
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                cameraDevice = null
            }
        }, handler)
    }


    private fun createCameraCaptureSession(cameraDevice: CameraDevice) {

        // check if the current camera device supports the required output size
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
        val containsRequiredOutputSize =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(OUTPUT_FORMAT)
                ?.contains(OUTPUT_SIZE) ?: false

        assert(containsRequiredOutputSize) { "Camera does not support the required output size" }

        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                Log.d(TAG, "onConfigured: capture session created")
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.d(TAG, "onConfigureFailed: could not create capture session")
            }
        }.run {
            cameraDevice.createCaptureSession(
                listOf(cameraOutput.cameraOutput.surface),
                this,
                handler
            )
        }

    }

    companion object {
        private const val TAG = "DefaultVideoCallService"
        private val OUTPUT_SIZE = Size(1280, 720)
        private val OUTPUT_FORMAT = ImageFormat.YUV_420_888
    }


    internal data class Settings(
        val isAudioMuted: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val isVideoMuted: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val cameraFace: MutableStateFlow<CameraFace> = MutableStateFlow(CameraFace.FRONT)
    )

    internal inner class CameraOutput {
        val cameraOutput: ImageReader = ImageReader.newInstance(

            OUTPUT_SIZE.width,
            OUTPUT_SIZE.height,
            ImageFormat.YUV_420_888,
            2
        )

        private val mutableBitmapState = mutableStateOf(null as BitmapWrapper?)
        val bitmapState: State<BitmapWrapper?> = mutableBitmapState

        init {
            cameraOutput.setOnImageAvailableListener(
                { reader ->
                    handler.post {
                        val image = reader.acquireLatestImage()
                        val outputBitmap = bitmapPool.getBitmap()
                        yuvToRgbConverter.yuvToRgb(image, outputBitmap.bitmap)

                        // Todo: output to the video encoder
                        bitmapPool.makeCopyOf(outputBitmap)

                        // output to the preview
                        mutableBitmapState.value = outputBitmap

                        image?.close()
                    }
                },
                handler
            )
        }
    }


    internal sealed interface Event {
        data object MuteAudio : Event
        data object UnMuteAudio : Event
        data object MuteVideo : Event
        data object UnMuteVideo : Event
        data class SwitchCamera(val cameraFace: CameraFace) : Event
    }

    internal inner class ServiceHandler(looper: Looper) : Handler(looper) {
        fun send(event: Event) {
            val message = obtainMessage(0, event)
            sendMessage(message)
        }

        override fun handleMessage(msg: Message): Unit = when (val event = msg.obj as Event) {
            is Event.MuteAudio -> {
                settings.isAudioMuted.value = true
            }

            is Event.UnMuteAudio -> {
                settings.isAudioMuted.value = false
            }

            is Event.MuteVideo -> {
                settings.isVideoMuted.value = true
            }

            is Event.UnMuteVideo -> {
                settings.isVideoMuted.value = false
            }

            is Event.SwitchCamera -> {

                settings.cameraFace.value = event.cameraFace
                openCamera()
            }
        }
    }
}


/**
 * Get the default implementation of [VideoCallService]
 * @returns a new instance of [DefaultVideoCallService]
 */

@RequiresPermission(android.Manifest.permission.CAMERA)
fun VideoCallService.Companion.getDefault(context: Context): VideoCallService {
    return DefaultVideoCallService(context)
}

