package desidev.rtc.media.camera

import android.annotation.SuppressLint
import android.content.Context
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
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import desidev.rtc.media.bitmappool.BitmapPool
import desidev.rtc.media.bitmappool.BitmapWrapper
import desidev.rtc.media.camera.CameraCapture.CameraState
import desidev.utility.yuv.YuvToRgbConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import online.desidev.kotlinutils.ReentrantMutex
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

    private val scope = CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Exception in CameraCaptureImpl", e)
    })

    private val mutex = ReentrantMutex()

    private val _state = MutableStateFlow(CameraState.INACTIVE)
    override val state: StateFlow<CameraState> = _state

    private val _isCaptureRunning = MutableStateFlow(false)
    override val isCaptureRunning: StateFlow<Boolean> = _isCaptureRunning.asStateFlow()

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val previewImageState: MutableStateFlow<BitmapWrapper?> = MutableStateFlow(null)
    private var previewImageReader: ImageReader? = null
    private var bitmapPool: BitmapPool? = null
    private var encoder: Encoder? = null

    private val _selectedCamera =
        MutableStateFlow(getAvailableCameras().run {
            find { it.lensFacing == CameraLensFacing.FRONT } ?: first()
        })
    override val selectedCamera: StateFlow<CameraDeviceInfo> = _selectedCamera
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val cameraQuality: CameraCapture.Quality = CameraCapture.Quality.Lowest
    private val displayRot: Int

    init {
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

    private fun getAvailableCameras() = cameraManager.cameraIdList.map { id ->
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


    private fun computeRelativeRotation(): Int {
        val cameraRot = getCurrentCameraOrientation()
        val sign = if (selectedCamera.value.lensFacing == CameraLensFacing.FRONT) -1 else 1
        return (cameraRot - displayRot * sign + 360) % 360
    }

    override suspend fun openCamera() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                cameraDevice = openCameraDevice()
                session = configSession(cameraDevice!!)
                session?.createCaptureRequest()
                _state.value = CameraState.ACTIVE
            }
        }
    }


    override suspend fun closeCamera() {
        mutex.withLock {
            if (state.value == CameraState.ACTIVE) {
                if (isCaptureRunning.value) stopCapture()
                session?.stopCaptureRequest()
                session = null
                cameraDevice?.close()
                cameraDevice = null
                previewImageReader?.close()
                previewImageReader = null
                previewImageState.apply {
                    value?.bitmap?.recycle()
                    value = null
                }
                bitmapPool?.clear()
                bitmapPool = null
                _state.value = CameraState.INACTIVE
            }
        }
    }


    override suspend fun startCapture() {
        mutex.withLock {
            if (isCaptureRunning.value) return@withLock
            if (state.value == CameraState.RELEASED) {
                throw IllegalStateException("Camera is released")
            }

            // if the camera is not opened, open it first
            if (cameraDevice == null) {
                cameraDevice = openCameraDevice()
            }

            encoder = Encoder()
            encoder?.start()

            cameraDevice?.let {
                session = configSession(it)
                session?.createCaptureRequest()
            }
            _state.value = CameraState.ACTIVE
            _isCaptureRunning.value = true
        }
    }

    override suspend fun stopCapture() {
        mutex.withLock {
            if (!isCaptureRunning.value) return@withLock
            encoder?.stop()
            encoder = null
            session?.close()
            cameraDevice?.let {
                session = configSession(it)
                session?.createCaptureRequest()
            }
            _isCaptureRunning.value = false
        }
    }


    override suspend fun release() {
        mutex.withLock {
            if (state.value == CameraState.ACTIVE) {
                closeCamera()
            }
            _state.value = CameraState.RELEASED
            handlerThread.quit()
            scope.cancel()
        }
    }


    @SuppressLint("MissingPermission")
    private suspend fun openCameraDevice(): CameraDevice = suspendCancellableCoroutine { cont ->
        val cameraId = selectedCamera.value.id
        val (width, height) = selectSupportedSize(cameraId)
        setupPreview(width, height)

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

    private fun selectSupportedSize(cameraId: String): Pair<Int, Int> {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return getSupportedSize(characteristics, cameraQuality)
    }

    private fun getSupportedSize(
        characteristics: CameraCharacteristics,
        quality: CameraCapture.Quality
    ): Pair<Int, Int> {
        return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.run {
            val supportedSizes = getOutputSizes(ImageFormat.YUV_420_888)
            supportedSizes.sortBy { it.width * it.height }
            supportedSizes.first { it.width * it.height >= quality.pixelElems }
                .let { Pair(it.width, it.height) }
        } ?: throw IllegalStateException("No supported sizes found")
    }

    private fun setupPreview(width: Int, height: Int) {
        bitmapPool = BitmapPool(dimen = Size(width, height), debug = false, tag = TAG)
        previewImageReader =
            ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ imageReader ->
                    imageReader.acquireNextImage()?.let { image ->
                        if (previewImageState.subscriptionCount.value > 0) {
                            val bitmapWrapper = bitmapPool!!.getBitmap()
                            yuvToRgbConverter.yuvToRgb(image, bitmapWrapper.bitmap)
                            val old = previewImageState.getAndUpdate { bitmapWrapper }
                            old?.release()
                        }
                        image.close()
                    }
                }, handler)
            }
    }

    private suspend fun configSession(cameraDevice: CameraDevice): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val outputSurface = buildList {
                add(previewImageReader!!.surface)
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
        val captureRequest = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)?.run {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3)
            addTarget(previewImageReader!!.surface)
            encoder?.let { addTarget(it.inputSurface) }
            build()
        }
        setRepeatingRequest(captureRequest!!, null, handler)
    }

    private fun CameraCaptureSession.stopCaptureRequest() {
        try {
            stopRepeating()
            abortCaptures()
            close()
        } catch (exc: Exception) {
            Log.e(TAG, exc.message, exc)
        }
    }

    private fun onCameraDisconnected(cameraDevice: CameraDevice) {
        scope.launch {
            mutex.withLock {
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

    override fun getCameras(): List<CameraDeviceInfo> = getAvailableCameras()

    override suspend fun selectCamera(info: CameraDeviceInfo) {
        mutex.withLock {
            val wasActive = state.value == CameraState.ACTIVE
            val wasCapturing = isCaptureRunning.value
            if (state.value == CameraState.ACTIVE) {
                closeCamera()
            }

            _selectedCamera.value = info

            if (wasCapturing) {
                // start capture also open the camera
                startCapture()
            } else if (wasActive) {
                openCamera()
            }
        }
    }

    private fun getCurrentCameraOrientation(): Int {
        return cameraManager.getCameraCharacteristics(selectedCamera.value.id)
            .get(CameraCharacteristics.SENSOR_ORIENTATION)!!
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
        val currentCamera by selectedCamera.collectAsState()
        val image = previewImageState.collectAsState().value?.bitmap

        if (cameraState == CameraState.ACTIVE && image != null) {
            val relativeRotation = remember(currentCamera) { computeRelativeRotation() }
            val xMirror =
                remember(currentCamera) { if (currentCamera.lensFacing == CameraLensFacing.FRONT) -1f else 1f }
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
                                image = image.asImageBitmap(),
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
            outputChannel.close()
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
                previewImageReader!!.width, previewImageReader!!.height
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

    companion object {
        private const val TAG = "CameraCaptureImpl"
    }
}