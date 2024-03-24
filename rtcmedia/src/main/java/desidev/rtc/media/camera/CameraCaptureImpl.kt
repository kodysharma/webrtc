package desidev.rtc.media.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import desidev.utility.yuv.YuvToRgbConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * There is a Surface between the camera and encoder.
 * And a ImageReader to provide the preview frames as Image objects
 */
class CameraCaptureImpl(context: Context) : CameraCapture {
    private val _handlerThread = HandlerThread("CameraHandler").apply { start() }
    private val _handler = Handler(_handlerThread.looper)
    private val handlerDispatcher = _handler.asCoroutineDispatcher()

    private val stateLock = Mutex()
    private var _state = CameraCapture.State.INACTIVE
        set(value) {
            Log.d(TAG, "State: [$field] -> [$value]")
            field = value
        }

    override val state: CameraCapture.State get() = _state

    private val cameraQuality: CameraCapture.Quality = CameraCapture.Quality.Low

    private val _cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var previewImageListener: ((Image) -> Unit)? = null

    private val previewImageReader: ImageReader

    private var encoder: Encoder? = null
    private var encoderOutput = Channel<Pair<ByteArray, BufferInfo>>(Channel.BUFFERED)

    private val _cameras: List<CameraDeviceInfo> = _cameraManager.cameraIdList.map { id ->
        val characteristics = _cameraManager.getCameraCharacteristics(id)
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


    private lateinit var _sessionCloseDeferred: CompletableDeferred<Unit>
    private lateinit var _cameraCloseDeferred: CompletableDeferred<Unit>

    private lateinit var _session: CameraCaptureSession

    private var currentCamera: CameraDeviceInfo =
        _cameras.find { it.lensFacing == CameraLensFacing.FRONT }!!
    override val selectedCamera: CameraDeviceInfo
        get() = currentCamera
    init {
        val currentCameraCharacteristics = _cameraManager.getCameraCharacteristics(currentCamera.id)
        val (width, height) = getSupportedSize(currentCameraCharacteristics, cameraQuality)

        previewImageReader =
            ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({
                    val image = it.acquireNextImage()
                    if (image != null) {
                        if (previewImageListener != null) {
                            previewImageListener!!.invoke(image)
                        } else {
                            image.close()
                        }
                    }
                }, _handler)
            }
    }


    override suspend fun start() {
        stateLock.withLock {
            startCapturing()
            encoder = Encoder(encoderOutput).apply {
                start()
                setPreviewFrameListener { image ->
                    val yuvImage = YUV420(
                        width = image.width,
                        height = image.height,
                        y = image.planes[0].buffer.makeCopy(),
                        u = image.planes[1].buffer.makeCopy(),
                        v = image.planes[2].buffer.makeCopy(),
                        timestampUs = image.timestamp / 1000
                    )
                    encoderInputImageChannel.trySendBlocking(yuvImage)
                    image.close()
                }
            }
        }
    }

    private suspend fun startCapturing() {
        if (_state == CameraCapture.State.INACTIVE) {
            val camDevice = openCamera()
            _session = configSession(camDevice)

            val recordRequest = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).run {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3)
                addTarget(previewImageReader.surface)
                build()
            }

            withContext(Dispatchers.Main) {
                _session.setRepeatingRequest(recordRequest, null, _handler)
            }
            _state = CameraCapture.State.ACTIVE
        }
    }


    private fun ByteBuffer.makeCopy(): ByteBuffer {
        val buffer = if (isDirect) ByteBuffer.allocateDirect(capacity()) else ByteBuffer.allocate(capacity())

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

    override suspend fun stop() {
        stateLock.withLock {
            stopCamera()
            encoder?.stop()
            encoder = null
        }
    }

    private fun isActive() = _state == CameraCapture.State.ACTIVE

    private suspend fun stopCamera() {
        try {
            if (isActive()) {
                _session.abortCaptures()
                _session.close()
                _sessionCloseDeferred.await()
                _session.device.close()
                _cameraCloseDeferred.await()
                _state = CameraCapture.State.INACTIVE
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    override suspend fun release() {
        stateLock.withLock {
            if (isActive()) {
                stop()
            }
            releaseInstances()
            _state = CameraCapture.State.RELEASED
        }
    }

    private fun releaseInstances() {
        try {
            previewImageReader.close()
            _handlerThread.quitSafely()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    override suspend fun selectCamera(cameraFace: CameraLensFacing) {
        currentCamera = _cameras.first { it.lensFacing == cameraFace }
        stateLock.withLock {
            val wasActive = isActive()
            stopCamera()
            if (wasActive) {
                startCapturing()
            }
        }
    }


    override fun setPreviewFrameListener(listener: ((Image) -> Unit)?) {
        previewImageListener = listener
    }

    override fun compressedDataChannel(): Channel<Pair<ByteArray, BufferInfo>> {
        return encoderOutput
    }

    override fun getMediaFormat(): Deferred<MediaFormat> {
        return encoder?.formatDeferred
            ?: throw IllegalStateException("Encoder is not started yet")
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCoroutine { cont ->
        val cameraId = currentCamera.id
        _cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                _cameraCloseDeferred = CompletableDeferred()
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
                try {
                    cont.resumeWithException(exc)
                } catch (ex: IllegalStateException) {
                    ex.printStackTrace()
                }
                Log.e(TAG, exc.message, exc)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.d(TAG, "camera device ${camera.id} got disconnected")
            }

            override fun onClosed(camera: CameraDevice) {
                _cameraCloseDeferred.complete(Unit)
                Log.d(TAG, "camera device ${camera.id} is now closed")
            }
        }, _handler)
    }


    private suspend fun configSession(cameraDevice: CameraDevice): CameraCaptureSession =
        suspendCoroutine { cont ->
            val outputSurface = buildList {
                add(previewImageReader.surface)
            }

            cameraDevice.createCaptureSession(
                outputSurface,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        _sessionCloseDeferred = CompletableDeferred()
                        cont.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cont.resumeWithException(RuntimeException("Could not configured session"))
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        _sessionCloseDeferred.complete(Unit)
                        Log.d(TAG, "CameraCaptureSession is now closed")
                    }
                },
                _handler
            )
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





    private inner class Encoder(
        val outputChannel: SendChannel<Pair<ByteArray, BufferInfo>>
    ) {
        val encoderInputImageChannel = Channel<YUV420>(Channel.BUFFERED)
        val formatDeferred = CompletableDeferred<MediaFormat>()
        private val looperThread = HandlerThread("EncoderLooper").apply { start() }
        private val handler = Handler(looperThread.looper)
        private val codecEvent = Channel<CodecEvent>()
        private val scope = CoroutineScope(Dispatchers.Default)
        private lateinit var mediaCodec: MediaCodec

        @OptIn(ObsoleteCoroutinesApi::class)
        val inputBufferHandler = scope.actor<CodecEvent.OnInputBufferAvailable> {
            consumeEach { bufferEvent ->
                val (codec, index) = bufferEvent
                val data = encoderInputImageChannel.receive()

                codec.getInputImage(index)?.let { codecInputImage ->
                    val y = codecInputImage.planes[0].buffer
                    val u = codecInputImage.planes[1].buffer
                    val v = codecInputImage.planes[2].buffer

                    y.put(data.y)
                    u.put(data.u)
                    v.put(data.v)
                }

                val size = data.width * data.height * 3 / 2
                codec.queueInputBuffer(index, 0,size, data.timestampUs, 0)
            }
        }

        @OptIn(ObsoleteCoroutinesApi::class)
        val outputBufferHandler = scope.actor<CodecEvent.OnOutputBufferAvailable> {
            consumeEach { bufferEvent ->
                val (codec, index, info) = bufferEvent
                val compressedArray = codec.getOutputBuffer(index)!!.run {
                    val array = ByteArray(remaining())
                    get(array)
                    array
                }
                outputChannel.send(Pair(compressedArray, info))
                codec.releaseOutputBuffer(index, false)
            }
        }

        fun start() {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = createFormat()

            mediaCodec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    codecEvent.trySendBlocking(CodecEvent.OnInputBufferAvailable(codec, index))
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: BufferInfo
                ) {
                    codecEvent.trySendBlocking(
                        CodecEvent.OnOutputBufferAvailable(
                            codec,
                            index,
                            info
                        )
                    )
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "MediaCodec error: ${e.message}")
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    codecEvent.trySendBlocking(CodecEvent.OnOutputFormatChanged(format))
                }
            }, handler)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()

            scope.launch {
                codecEvent.consumeEach { event ->
                    when (event) {
                        is CodecEvent.OnInputBufferAvailable -> inputBufferHandler.send(event)
                        is CodecEvent.OnOutputBufferAvailable -> outputBufferHandler.send(event)
                        is CodecEvent.OnOutputFormatChanged -> formatDeferred.complete(event.format)
                    }
                }
            }
        }

        suspend fun stop() {
            // Cancel all children coroutines and wait for them to complete
            scope.coroutineContext[Job]?.apply {
                cancelChildren()
                children.toList().joinAll()
            }

            // Cancel the scope itself
            scope.cancel()

            // Close the codec event channel
            codecEvent.close()

            // Safely stop and release the media codec
            try {
                mediaCodec.stop()
                mediaCodec.release()
                Log.i(TAG, "MediaCodec stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Could not stop MediaCodec: ${e.message}")
            }

            // Close the encoder input image channel and quit the looper thread
            encoderInputImageChannel.close()
            looperThread.quitSafely()
        }

        private fun createFormat(): MediaFormat {
            return MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                previewImageReader.width, previewImageReader.height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 250_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
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