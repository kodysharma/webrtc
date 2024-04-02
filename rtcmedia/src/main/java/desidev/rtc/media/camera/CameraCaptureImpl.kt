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
import android.view.Surface
import desidev.utility.yuv.YuvToRgbConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
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
    private val handlerThread = HandlerThread("CameraHandler").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val yuvToRgbConverter = YuvToRgbConverter(context)

    private val stateLock = Mutex()
    private var _state = CameraCapture.State.INACTIVE
        set(value) {
            Log.d(TAG, "State: [$field] -> [$value]")
            field = value
        }

    override val state: CameraCapture.State get() = _state

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

    private lateinit var _sessionCloseDeferred: CompletableDeferred<Unit>


    private lateinit var _cameraCloseDeferred: CompletableDeferred<Unit>
    private lateinit var _session: CameraCaptureSession

    private val cameraQuality: CameraCapture.Quality = CameraCapture.Quality.Lowest

    private var currentCamera: CameraDeviceInfo =
        cameras.find { it.lensFacing == CameraLensFacing.FRONT }!!
    override val selectedCamera: CameraDeviceInfo
        get() = currentCamera

    init {
        val currentCameraCharacteristics = cameraManager.getCameraCharacteristics(currentCamera.id)
        val (width, height) = getSupportedSize(currentCameraCharacteristics, cameraQuality)

        Log.d(TAG, "OutputSize: $width x $height")

        previewImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        previewImageReader.setOnImageAvailableListener({ imageReader ->
            imageReader.acquireNextImage()?.let { image ->
               /* val yuvImage = YUV420(
                    width = image.width,
                    height = image.height,
                    y = image.planes[0].buffer.makeCopy(),
                    u = image.planes[1].buffer.makeCopy(),
                    v = image.planes[2].buffer.makeCopy(),
                    timestampUs = image.timestamp / 1000
                )

                val sendResult = encoder?.inputChannel?.trySend(yuvImage)
                if (sendResult?.isSuccess != true) {
                    Log.e(TAG, "Failed to send image to encoder")
                }*/

                // convert this image to bitmap
                if (previewFrameListener != null) {
                    val frame = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    yuvToRgbConverter.yuvToRgb(image, frame)
                    previewFrameListener?.invoke(frame)
                }

                image.close()
            }
        }, handler)
    }


    override suspend fun start() {
        stateLock.withLock {
            encoder = Encoder()
            encoder!!.start()
            startCapturing()
        }
    }

    private suspend fun startCapturing() {
        if (_state == CameraCapture.State.INACTIVE) {
            val camDevice = openCamera()

            _session = configSession(camDevice)

            val recordRequest = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                .run {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3)
                    addTarget(previewImageReader.surface)
                    addTarget(encoder!!.surface)
                    build()
                }

            withContext(Dispatchers.Main) {
                _session.setRepeatingRequest(recordRequest, null, handler)
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
            encoder?.apply {
                stop()
                outputChannel?.close()
            }
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
            handlerThread.quitSafely()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    override suspend fun selectCamera(cameraFace: CameraLensFacing) {
        currentCamera = cameras.first { it.lensFacing == cameraFace }
        stateLock.withLock {
            val wasActive = isActive()
            stopCamera()
            if (wasActive) {
                startCapturing()
            }
        }
    }


    override fun setPreviewFrameListener(listener: ((Bitmap) -> Unit)?) {
        previewFrameListener = listener
    }


    /**
     * Compressed Output Channel of audio buffers
     */
    @OptIn(DelicateCoroutinesApi::class)
    override fun compressChannel(): Channel<Pair<ByteArray, BufferInfo>> {
        val channel = encoder?.outputChannel
        val createNew = channel == null || channel.isClosedForSend
        if (createNew) {
            encoder?.outputChannel = Channel()
        }
        return encoder?.outputChannel as? Channel
            ?: throw IllegalStateException("Maybe you forgot to call start()?")
    }

    override fun getMediaFormat(): Deferred<MediaFormat> {
        return encoder?.formatDeferred
            ?: throw IllegalStateException("Encoder is not started yet")
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCoroutine { cont ->
        val cameraId = currentCamera.id
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
        }, handler)
    }


    private suspend fun configSession(cameraDevice: CameraDevice): CameraCaptureSession =
        suspendCoroutine { cont ->
            val outputSurface = buildList {
                add(previewImageReader.surface)
                add(encoder!!.surface)
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
                handler
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


    private inner class Encoder {
        var outputChannel: SendChannel<Pair<ByteArray, BufferInfo>>? = null
        lateinit var surface: Surface

        val formatDeferred = CompletableDeferred<MediaFormat>()

        private val looperThread = HandlerThread("EncoderLooper").apply { start() }
        private val scope = CoroutineScope(Dispatchers.Default)
        private lateinit var mediaCodec: MediaCodec

        fun start() {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = createFormat()

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = mediaCodec.createInputSurface()
            mediaCodec.start()

            scope.launch {
                var index: Int
                while (isActive) {
//                    var index = mediaCodec.dequeueInputBuffer(0)
//                    if (index >= 0) {
//                        queueInputBuffer(mediaCodec, index)
//                    }
                    val info = BufferInfo()
                    index = mediaCodec.dequeueOutputBuffer(info, 0)
                    if (index >= 0) {
                        processOutputBuffer(mediaCodec, index, info)
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        formatDeferred.complete(mediaCodec.outputFormat)
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
                outputChannel?.send(Pair(compressedArray, info))
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
            scope.cancel()

            // Safely stop and release the media codec
            try {
                mediaCodec.stop()
                mediaCodec.release()
                surface.release()
                Log.i(TAG, "MediaCodec stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Could not stop MediaCodec: ${e.message}")
            }

            // Close the encoder input image channel and quit the looper thread
//            inputChannel.close()
            looperThread.quitSafely()
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