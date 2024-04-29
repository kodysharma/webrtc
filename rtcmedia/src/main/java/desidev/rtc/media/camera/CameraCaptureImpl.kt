package desidev.rtc.media.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import desidev.rtc.media.camera.CameraCapture.CameraState
import desidev.utility.yuv.YuvToRgbConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import online.desidev.kotlinutils.Action
import online.desidev.kotlinutils.sendAction
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * There is a Surface between the camera and encoder.
 * And a ImageReader to provide the preview frames as Image objects
 */
class CameraCaptureImpl(context: Context) : CameraCapture {
    private val handlerThread = HandlerThread("CameraHandler").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val yuvToRgbConverter = YuvToRgbConverter(context)

    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Action runner run the operations in sequence that updates the state of the camera
     */
    @OptIn(ObsoleteCoroutinesApi::class)
    private val actionRunner = scope.actor<Action<*>> {
        consumeEach { it.execute() }
    }

    private val _state = MutableStateFlow<CameraState>(CameraState.INACTIVE)
    override val state: StateFlow<CameraState> = _state

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var previewFrameListener: ((Bitmap) -> Unit)? = null
    private val previewImageReader: ImageReader
    private var encoder: Encoder? = null

    private val cameras: List<CameraDeviceInfo> = cameraManager.cameraIdList.map { id ->
        val characteristics = cameraManager.getCameraCharacteristics(id)
        characteristics.get(CameraCharacteristics.LENS_FACING).run {
            val lensFacing = when (this) {
                CameraCharacteristics.LENS_FACING_BACK -> CameraLensFacing.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> CameraLensFacing.FRONT
                CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraLensFacing.EXTERNAL
                else -> throw IllegalArgumentException("Unknown lens facing")
            }
            CameraDeviceInfo(lensFacing, id)
        }
    }

    private val _selectedCamera =
        MutableStateFlow(cameras.find { it.lensFacing == CameraLensFacing.FRONT }
            ?: cameras.first())

    override val selectedCamera: StateFlow<CameraDeviceInfo> = _selectedCamera

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val cameraQuality: CameraCapture.Quality = CameraCapture.Quality.Lowest
    private val displayRot: Int

    init {
        val currentCameraCharacteristics =
            cameraManager.getCameraCharacteristics(selectedCamera.value.id)
        val (width, height) = getSupportedSize(currentCameraCharacteristics, cameraQuality)
        Log.d(TAG, "OutputSize: $width x $height")

        previewImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        previewImageReader.setOnImageAvailableListener({ imageReader ->
            imageReader.acquireNextImage()?.let { image ->
                if (previewFrameListener != null) {
                    val frame =
                        Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    yuvToRgbConverter.yuvToRgb(image, frame)
                    previewFrameListener?.invoke(frame)
                }

                image.close()
            }
        }, handler)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.rotation

        // Get the current display rotation
        displayRot = ContextCompat.getDisplayOrDefault(context.applicationContext).rotation.let {
            when (it) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> throw IllegalArgumentException("Unknown rotation: $it")
            }
        }
    }


    private fun computeRelativeRotation(): Int {
        val cameraRot = getCurrentCameraOrientation()
        val sign = if (selectedCamera.value.lensFacing == CameraLensFacing.FRONT) -1 else 1
        return (cameraRot - displayRot * sign + 360) % 360
    }

    override suspend fun openCamera() {
        actionRunner.sendAction {
            cameraDevice = openCameraDevice()
            session = configSession(cameraDevice!!)
            session?.createCaptureRequest()
            _state.value = CameraState.ACTIVE
        }.await()
    }


    override suspend fun closeCamera() {
        actionRunner.sendAction {
            _state.value = CameraState.INACTIVE
            session?.close()
            session = null
            cameraDevice?.close()
            cameraDevice = null

            encoder?.stop()
            encoder = null
        }.await()
    }

    override suspend fun startCapture() {
        val currentState = actionRunner.sendAction { state.value }.await()
        if (currentState == CameraState.RELEASED) {
            throw IllegalStateException("Camera is released")
        }

        actionRunner.sendAction {
            // close the previous session if any
            session?.close()

            encoder = Encoder()
            encoder?.start()

            // if the camera is not active, open the camera
            if (currentState == CameraState.INACTIVE) {
                cameraDevice = openCameraDevice()
            }

            cameraDevice?.let {
                session = configSession(it)
                session?.createCaptureRequest()
            }
            _state.value = CameraState.ACTIVE
        }.await()
    }

    override suspend fun stopCapture() {
        actionRunner.sendAction {
            if (state.value != CameraState.ACTIVE) throw IllegalStateException("Camera is not active")
            encoder?.stop()
            encoder = null
            session?.close()
            cameraDevice?.let {
                session = configSession(it)
                session?.createCaptureRequest()
            }
        }.await()
    }


    override suspend fun release() {
        val isActive = actionRunner.sendAction { (state.value == CameraState.ACTIVE) }.await()
        if (isActive) {
            actionRunner.sendAction {
                session?.close()
                cameraDevice?.close()
                _state.value = CameraState.INACTIVE
                encoder?.stop()

                _state.value = CameraState.RELEASED
            }.await()
        }
        scope.cancel()
        handlerThread.quitSafely()
        previewImageReader.close()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraDevice(): CameraDevice = suspendCancellableCoroutine { cont ->
        val cameraId = selectedCamera.value.id
        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cont.resume(camera)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val msg = when (error) {
                        ERROR_CAMERA_DEVICE -> "Fatal (device)"
                        ERROR_CAMERA_DISABLED -> "Device policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }
                    val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                    cont.resumeWithException(exc)
                    Log.e(TAG, exc.message, exc)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "camera device ${camera.id} got disconnected")
                    onCameraDisconnected(camera)
                }

                override fun onClosed(camera: CameraDevice) {
                    Log.d(TAG, "camera device ${camera.id} is now closed")
                }
            },
            handler
        )
    }

    private suspend fun configSession(cameraDevice: CameraDevice): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val outputSurface = buildList {
                add(previewImageReader.surface)
                encoder?.let { add(it.inputSurface) }
            }

            cameraDevice.createCaptureSession(
                outputSurface,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cont.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cont.resumeWithException(RuntimeException("Could not configured session"))
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        Log.d(TAG, "CameraCaptureSession is now closed")
                    }
                },
                handler
            )
        }

    private fun CameraCaptureSession.createCaptureRequest() {
        cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)?.run {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3)
            addTarget(previewImageReader.surface)
            encoder?.let { addTarget(it.inputSurface) }
            build()
        }?.let {
            setRepeatingRequest(it, null, handler)
        }
    }

    private fun onCameraDisconnected(cameraDevice: CameraDevice) {
        scope.launch {
            actionRunner.sendAction {
                _state.value = CameraState.INACTIVE
                try {
                    cameraDevice.close()
                } catch (exc: Exception) {
                    Log.e(TAG, exc.message, exc)
                }
                this@CameraCaptureImpl.cameraDevice = null
                encoder?.stop()
                encoder = null
                try {
                    session?.close()
                } catch (exc: Exception) {
                    Log.e(TAG, exc.message, exc)
                }
                session = null
            }
        }
    }

    override fun getCameras(): List<CameraDeviceInfo> {
        return cameras
    }

    override suspend fun selectCamera(info: CameraDeviceInfo) {
        actionRunner.sendAction {
            if (state.value == CameraState.ACTIVE) {
                session?.close()
                cameraDevice?.close()
            }
            _selectedCamera.value = info
            if (state.value == CameraState.ACTIVE) {
                cameraDevice = openCameraDevice()
                session = configSession(cameraDevice!!)
                session?.createCaptureRequest()
            }
        }.await()
    }

    private fun getCurrentCameraOrientation(): Int {
        return cameraManager.getCameraCharacteristics(selectedCamera.value.id)
            .get(CameraCharacteristics.SENSOR_ORIENTATION)!!
    }

    private fun setPreviewFrameListener(listener: ((Bitmap) -> Unit)?) {
        previewFrameListener = listener
    }

    /**
     * Compressed Output Channel of audio buffers
     */
    override fun compressChannel(): ReceiveChannel<Pair<ByteArray, BufferInfo>> =
        encoder?.outputChannel ?: throw IllegalStateException("Encoder is not initialized")

    override fun getMediaFormat(): Deferred<MediaFormat> {
        return encoder?.formatDeferred
            ?: throw IllegalStateException("Encoder is not started yet")
    }

    @Composable
    override fun PreviewView(modifier: Modifier) {
        val cameraState by state.collectAsState()
        if (cameraState == CameraState.ACTIVE) {
            val currentPreviewFrame = remember { mutableStateOf<ImageBitmap?>(null) }
            val relativeRotation = remember {
                computeRelativeRotation()
            }
            val currentCamera by selectedCamera.collectAsState()
            val xMirror =
                remember { if (currentCamera.lensFacing == CameraLensFacing.FRONT) -1f else 1f }

            LaunchedEffect(Unit) {
                setPreviewFrameListener { image ->
                    currentPreviewFrame.value = image.asImageBitmap()
                }
            }

            val image = currentPreviewFrame.value
            if (image != null) {
                Canvas(modifier = modifier) {
                    val scale: Float = let {
                        val imageDimen = if (relativeRotation % 90 == 0) {
                            with(image) { IntSize(height, width) }
                        } else {
                            with(image) { IntSize(width, height) }
                        }

                        val hScale = size.height / imageDimen.height
                        val wScale = size.width / imageDimen.width
                        max(wScale, hScale)
                    }

                    val dstSize = IntSize(
                        (image.width * scale).roundToInt(),
                        (image.height * scale).roundToInt()
                    )

                    val imageOffset = let {
                        val x = (size.width - dstSize.width) * 0.5f
                        val y = (size.height - dstSize.height) * 0.5f
                        IntOffset(x.toInt(), y.toInt())
                    }

                    clipRect {
                        scale(xMirror, 1f) {
                            rotate(relativeRotation.toFloat(), center) {
                                drawImage(
                                    image = image,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(image.width, image.height),
                                    dstOffset = imageOffset,
                                    dstSize = dstSize
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    private fun getSupportedSize(
        characteristics: CameraCharacteristics,
        quality: CameraCapture.Quality
    ): Pair<Int, Int> {
        return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.run {
            val supportedSizes = getOutputSizes(ImageFormat.YUV_420_888)
            supportedSizes.find { (it.width * it.height) <= quality.pixelElems }
                ?.let { it.width to it.height }
        } ?: throw RuntimeException("Could not find supported size for $quality")
    }

    private fun ByteBuffer.makeCopy(): ByteBuffer {
        val buffer =
            if (isDirect) ByteBuffer.allocateDirect(capacity()) else ByteBuffer.allocate(capacity())

        val position = position()
        val limit = limit()
        rewind()

        buffer.put(this)
        buffer.rewind()

        position(position)
        limit(limit)

        buffer.position(position)
        buffer.limit(limit)

        return buffer
    }

    private inner class Encoder {
        lateinit var inputSurface: Surface
        val outputChannel: Channel<Pair<ByteArray, BufferInfo>> = Channel()
        val formatDeferred = CompletableDeferred<MediaFormat>()
        private val scope = CoroutineScope(Dispatchers.Default)
        private lateinit var mediaCodec: MediaCodec

        fun start() {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = createFormat()

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec.createInputSurface()
            mediaCodec.start()

            scope.launch {
                var index: Int
                while (isActive) {
                    val info = BufferInfo()
                    index = mediaCodec.dequeueOutputBuffer(info, 0)
                    if (index >= 0) {
                        processOutputBuffer(mediaCodec, index, info)
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        formatDeferred.complete(mediaCodec.outputFormat.apply {
                            setInteger(MediaFormat.KEY_ROTATION, computeRelativeRotation())
                        })
                    }
                }
            }
        }


        /*  private suspend fun queueInputBuffer(codec: MediaCodec, index: Int) {
              val data: YUV420 = inputChannel.receive()

              codec.getInputImage(index)?.let { codecInputImage: Image ->
                  val y = codecInputImage.planes[0].buffer
                  val u = codecInputImage.planes[1].buffer
                  val v = codecInputImage.planes[2].buffer

                  y.put(data.y)
                  u.put(data.u)
                  v.put(data.v)
              }

              val size = data.width * data.height * 3 / 2
              codec.queueInputBuffer(index, 0, size, data.timestampUs, 0)
          }*/


        private suspend fun processOutputBuffer(codec: MediaCodec, index: Int, info: BufferInfo) {
            val compressedArray = codec.getOutputBuffer(index)!!.run {
                val array = ByteArray(remaining())
                get(array)
                array
            }

            try {
                outputChannel.send(Pair(compressedArray, info))
            } catch (ex: ClosedSendChannelException) {
                // ignore
            }

            codec.releaseOutputBuffer(index, false)
        }

        suspend fun stop() {
            // Cancel all children coroutines and wait for them to complete
            scope.coroutineContext[Job]?.apply {
                cancelChildren()
                children.toList().joinAll()
            }

            // Cancel the scope itself
            outputChannel?.close()
            scope.cancel()

            if (!formatDeferred.isCompleted) {
                formatDeferred.completeExceptionally(IllegalStateException("encoder is stopped"))
            }

            // Safely stop and release the media codec
            try {
                mediaCodec.stop()
                mediaCodec.release()
                inputSurface.release()
                Log.i(TAG, "MediaCodec stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Could not stop MediaCodec: ${e.message}")
            }
        }

        private fun createFormat(): MediaFormat {
            return MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                previewImageReader.width, previewImageReader.height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 250_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        }
    }

    sealed interface CodecEvent {
        data class OnInputBufferAvailable(val codec: MediaCodec, val index: Int) : CodecEvent
        data class OnOutputBufferAvailable(
            val codec: MediaCodec,
            val index: Int,
            val info: BufferInfo
        ) : CodecEvent

        data class OnOutputFormatChanged(val format: MediaFormat) : CodecEvent
    }


    class YUV420(
        val width: Int,
        val height: Int,
        val y: ByteBuffer,
        val u: ByteBuffer,
        val v: ByteBuffer,
        val timestampUs: Long
    )

    companion object {
        private const val TAG = "CameraCaptureImpl"
    }
}