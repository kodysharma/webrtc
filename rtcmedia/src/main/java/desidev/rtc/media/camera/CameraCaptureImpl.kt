package desidev.rtc.media.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
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
import com.theeasiestway.yuv.YuvUtils
import com.theeasiestway.yuv.entities.ArgbFrame
import desidev.rtc.media.bitmappool.BitmapPool
import desidev.rtc.media.bitmappool.BitmapWrapper
import desidev.rtc.media.camera.CameraCapture.CameraState
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
import online.desidev.kotlinutils.ConditionLock
import online.desidev.kotlinutils.ReentrantMutex
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * There is a Surface between the camera and encoder.
 * And a ImageReader to provide the preview frames as Image objects
 */
class CameraCaptureImpl(context: Context) : CameraCapture {
    private val handlerThread = HandlerThread("CameraHandler").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val scope = CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Exception in CameraCaptureImpl", e)
    })

    private val mutex = ReentrantMutex(enableLogging = true, tag = TAG)

    private val _state = MutableStateFlow(CameraState.INACTIVE)
    override val state: StateFlow<CameraState> = _state

    private val _isCaptureRunning = MutableStateFlow(false)
    override val isCaptureRunning: StateFlow<Boolean> = _isCaptureRunning.asStateFlow()

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val previewImageState: MutableStateFlow<BitmapWrapper?> = MutableStateFlow(null)

    @Volatile
    private var previewImageReader: ImageReader? = null

    @Volatile
    private var bitmapPool: BitmapPool? = null

    @Volatile
    private var encoder: Encoder? = null

    private val _selectedCamera = MutableStateFlow(getAvailableCameras().run {
        find { it.lensFacing == CameraLensFacing.FRONT } ?: first()
    })
    override val selectedCamera: StateFlow<CameraDeviceInfo> = _selectedCamera
    private val isCamOpenCond = ConditionLock(false)
    private val capSesOpenCond = ConditionLock(false)

    @Volatile
    private var cameraDevice: CameraDevice? = null

    @Volatile
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

    private fun getAvailableCameras(): List<CameraDeviceInfo> {
        val cameraList = mutableListOf<CameraDeviceInfo>()
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val cameraLensFacing = when (lensFacing) {
                    CameraCharacteristics.LENS_FACING_BACK -> CameraLensFacing.BACK
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraLensFacing.FRONT
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraLensFacing.EXTERNAL
                    else -> {
                        Log.e(TAG, "Unknown lens facing for camera id: $id")
                        null
                    }
                }
                cameraLensFacing?.let {
                    cameraList.add(CameraDeviceInfo(id, it))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission not granted", e)
            // Handle the exception (e.g., request permissions, show a message to the user)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to get camera characteristics", e)
            // Handle the exception (e.g., show a message to the user)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to access camera", e)
            when (e.reason) {
                CameraAccessException.CAMERA_DISABLED -> Log.e(TAG, "Camera disabled", e)
                CameraAccessException.CAMERA_ERROR -> Log.e(TAG, "Camera error", e)
                CameraAccessException.CAMERA_IN_USE -> Log.e(TAG, "Camera in use", e)
                CameraAccessException.MAX_CAMERAS_IN_USE -> Log.e(TAG, "Max cameras in use", e)
                CameraAccessException.CAMERA_DISCONNECTED -> Log.e(TAG, "Camera disconnected", e)
            }
        }
        return cameraList
    }

    private fun computeRelativeRotation(): Int {
        val cameraRot = getCurrentCameraOrientation()
        val sign = if (selectedCamera.value.lensFacing == CameraLensFacing.FRONT) -1 else 1
        return (cameraRot - displayRot * sign + 360) % 360
    }

    override suspend fun openCamera() {
        mutex.withLock("openCamera()") {
            withContext(Dispatchers.Default) {
                cameraDevice = openCameraDevice()
                session = configSession(cameraDevice!!)
                session?.createCaptureRequest()
                _state.value = CameraState.ACTIVE
            }
        }
    }

    override suspend fun closeCamera() {
        mutex.withLock("closeCamera") {
            if (state.value == CameraState.ACTIVE) {
                cameraDevice?.close()
                isCamOpenCond.awaitFalse()
                session = null
                cameraDevice = null

                _state.value = CameraState.INACTIVE

                previewImageReader?.close()
                previewImageReader = null

                val temp = previewImageState.value
                previewImageState.value = null
                temp?.release()
                bitmapPool?.clear()
                bitmapPool = null

                if (isCaptureRunning.value) stopCapture()
            }
        }
    }

    override suspend fun startCapture() {
        mutex.withLock("startCapture()") {
            if (isCaptureRunning.value) {
                Log.i(TAG, "startCapture(): Already capturing.")
                return@withLock
            }
            if (state.value == CameraState.RELEASED) {
                throw IllegalStateException("Camera is released")
            }
            if (state.value == CameraState.INACTIVE) {
                openCamera()
            }

            Log.i(TAG, "startCapture(): Starting Encoder.")
            encoder = Encoder()
            encoder?.start()

            session = configSession(cameraDevice!!)
            session?.createCaptureRequest()

            _isCaptureRunning.value = true
        }
    }

    override suspend fun stopCapture() {
        mutex.withLock("stopCapture()") {
            if (!isCaptureRunning.value) return@withLock

            if (state.value == CameraState.ACTIVE) {
                session?.stopCaptureRequest()
                Log.d(TAG, "Stopped previous CaptureSession.")
            }

            encoder?.stop()
            encoder = null

            if (state.value == CameraState.ACTIVE) {
                session = cameraDevice?.let { configSession(it) }
                session?.createCaptureRequest()

                Log.d(TAG, "Recreated CaptureSession")
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
            withContext(Dispatchers.IO) {
                handlerThread.quitSafely()
                handlerThread.join()
            }
            scope.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraDevice(): CameraDevice {
        val cameraId = selectedCamera.value.id
        val (width, height) = selectSupportedSize(cameraId)
        setupPreview(width, height)
        var resumed = false
        return suspendCoroutine { cont ->

            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    isCamOpenCond.set(true)
                    cont.resume(camera)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    isCamOpenCond.set(false)

                    val msg = when (error) {
                        ERROR_CAMERA_DEVICE -> {
                            if (resumed) {
                                scope.launch {
                                    Log.d(TAG, "Retry to open camera")
                                    cameraDevice = openCameraDevice()
                                }
                            }
                            "Fatal (device)"
                        }

                        ERROR_CAMERA_DISABLED -> "Device policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }

                    val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                    Log.e(TAG, exc.message, exc)

                    if (!resumed) {
                        cont.resumeWithException(exc)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "camera device ${camera.id} got disconnected")
                    isCamOpenCond.set(false)
                    onCameraDisconnect(camera)
                }

                override fun onClosed(camera: CameraDevice) {
                    isCamOpenCond.set(false)
                    Log.d(TAG, "camera device ${camera.id} is now closed")
                }
            }

            cameraManager.openCamera(cameraId, callback, handler)
        }.also {
            resumed = true
        }
    }

    private fun selectSupportedSize(cameraId: String): Pair<Int, Int> {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return getSupportedSize(characteristics, cameraQuality)
    }

    private fun getSupportedSize(
        characteristics: CameraCharacteristics, quality: CameraCapture.Quality
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
        val yuvUtils = YuvUtils()
        previewImageReader =
            ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ imageReader ->
                    try {
                        imageReader.acquireLatestImage()?.let { image: Image ->
                            image.use {
                                if (previewImageState.subscriptionCount.value > 0) {
                                    val yuvFrame = yuvUtils.convertToI420(image)
                                    val argbFrame: ArgbFrame = yuvUtils.yuv420ToArgb(yuvFrame)
                                    val bitmapWrapper = bitmapPool!!.getBitmap().apply {
                                        bitmap.apply { copyPixelsFromBuffer(argbFrame.data) }
                                    }
                                    val old = previewImageState.getAndUpdate { bitmapWrapper }
                                    old?.release()
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        ex.printStackTrace()
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
                outputSurface, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        capSesOpenCond.set(true)
                        cont.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cont.resumeWithException(RuntimeException("Could not configured session"))
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        capSesOpenCond.set(false)
                        Log.d(TAG, "CameraCaptureSession is now closed $session")
                    }
                }, handler
            )
        }

    private fun CameraCaptureSession.createCaptureRequest() {
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
        val aeCompensationRange =
            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val faceDetectsMode =
            characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)

        val captureRequest = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)?.run {

            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompensationRange!!.upper)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            isoRange?.let {
                set(CaptureRequest.SENSOR_SENSITIVITY, it.upper)
            }

            faceDetectsMode?.let {
                if (it.isNotEmpty()) {
                    set(
                        CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                        CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL
                    )
                }
            }



            addTarget(previewImageReader!!.surface)
            encoder?.let { addTarget(it.inputSurface) }
            build()
        }

        captureRequest?.run {
            val aeLock = get(CaptureRequest.CONTROL_AE_LOCK)
            Log.d(TAG, "aeLock: $aeLock")

            val iso = get(CaptureRequest.SENSOR_SENSITIVITY)
            Log.d(TAG, "iso: $iso")

            val controlAeMode = get(CaptureRequest.CONTROL_AE_MODE)
            val modeToString = when (controlAeMode) {
                CaptureRequest.CONTROL_AE_MODE_ON -> "ON"
                else -> "OFF"
            }
            Log.d(TAG, "controlAeMode: $modeToString")

            val controlAfMode = get(CaptureRequest.CONTROL_AF_MODE)
            val afModeToString = when (controlAfMode) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
                CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
                CaptureRequest.CONTROL_AF_MODE_MACRO -> "MACRO"
                CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
                else -> "OFF"
            }

            Log.d(TAG, "controlAfMode: $afModeToString")

            val aeCompensation = get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
            Log.d(TAG, "aeCompensation: $aeCompensation")

        }
        Log.d(TAG, "aeCompensationRange: $aeCompensationRange")
        Log.d(TAG, "isoRange: $isoRange")
        Log.d(TAG, "faceDetectsMode: $faceDetectsMode")


        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {

            }
        }


        setRepeatingRequest(captureRequest!!, captureCallback, handler)
    }

    private suspend fun CameraCaptureSession.stopCaptureRequest() {
        try {
            stopRepeating()
            close()
            capSesOpenCond.awaitFalse()
        } catch (exc: Exception) {
            Log.e(TAG, "Could not stop capture session correctly", exc)
        }
    }

    private fun onCameraDisconnect(cameraDevice: CameraDevice) {
        scope.launch {
            mutex.withLock {
                _state.value = CameraState.INACTIVE
                session?.close()
                session = null
                cameraDevice.close()
                if (isCaptureRunning.value) {
                    _isCaptureRunning.value = false
                    encoder?.stop()
                    encoder = null
                }
            }
        }
    }

    override fun getCameras(): List<CameraDeviceInfo> = getAvailableCameras()

    override suspend fun switchCamera(info: CameraDeviceInfo) {
        mutex.withLock("switchCamera()") {
            val wasActive = state.value == CameraState.ACTIVE
            val wasCapturing = isCaptureRunning.value

            closeCamera()
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
        return encoder?.formatDeferred ?: throw IllegalStateException("Encoder is not started yet")
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
                    (image.width * scale).roundToInt(), (image.height * scale).roundToInt()
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
        val outputChannel: Channel<Pair<ByteArray, BufferInfo>> = Channel()
        val formatDeferred = CompletableDeferred<MediaFormat>()
        private val encoderScope = CoroutineScope(Dispatchers.Default)
        private lateinit var mediaCodec: MediaCodec

        lateinit var inputSurface: Surface
        fun start() {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = createFormat()

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec.createInputSurface()
            mediaCodec.start()

            encoderScope.launch {
                var index: Int
                while (isActive) {
                    try {
                        val info = BufferInfo()
                        index = mediaCodec.dequeueOutputBuffer(info, 0)
                        if (index >= 0) {
                            processOutputBuffer(mediaCodec, index, info)
                        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            formatDeferred.complete(mediaCodec.outputFormat.apply {
                                setInteger(MediaFormat.KEY_ROTATION, computeRelativeRotation())
                            })
                        }
                    } catch (_: IllegalStateException) {
                        // ignore
                    }
                }
            }
        }

        /*private suspend fun processInput(codec: MediaCodec) {
            try {
                if (inputBufferIndex == -1) {
                    inputBufferIndex = codec.dequeueInputBuffer(0)
                }
                if (inputBufferIndex >= 0) {
                    val yuv = inputChannel.receive()
                    val dstImg = codec.getInputImage(inputBufferIndex)
                    if (dstImg != null) {
                        assert(dstImg.width == yuv.width && dstImg.height == yuv.height) { "size mismatch." }

                        val copyTime = measureTime {
                            coroutineScope {
                                val dstY = dstImg.planes[0]
                                for (i in 0 until dstImg.height) {
                                    launch {
                                        for (j in 0 until dstImg.width) {
                                            val srcYIndex =
                                                i * yuv.y.rowStride + j * yuv.y.pixelStride
                                            val y = yuv.y.buffer.get(srcYIndex)

                                            val dstYIndex =
                                                i * dstY.rowStride + j * dstY.pixelStride
                                            dstY.buffer.put(dstYIndex, y)
                                        }
                                    }
                                }
                                val dstU = dstImg.planes[1]
                                val dstV = dstImg.planes[2]

                                for (i in 0 until dstImg.height / 2) {
                                    launch {
                                        for (j in 0 until dstImg.width / 2) {
                                            val srcIndex =
                                                i * yuv.u.rowStride + j * yuv.u.pixelStride
                                            val u = yuv.u.buffer.get(srcIndex)
                                            val v = yuv.v.buffer.get(srcIndex)

                                            val dstIndex = i * dstU.rowStride + j * dstU.pixelStride
                                            dstU.buffer.put(dstIndex, u)
                                            dstV.buffer.put(dstIndex, v)
                                        }
                                    }
                                }
                            }
                        }

                        Log.d(TAG, "copy time: $copyTime")

                        val imageSize = dstImg.width * dstImg.height * 3 / 2
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            imageSize,
                            yuv.timestampUs,
                            0
                        )
                        inputBufferIndex = codec.dequeueInputBuffer(0)
                    }
                }

            } catch (_: IllegalStateException) {
            } catch (_: ClosedReceiveChannelException) {
            }
        }*/

        private suspend fun processOutputBuffer(
            codec: MediaCodec,
            index: Int,
            bufferInfo: BufferInfo
        ) {
            try {
                val compressedArray = codec.getOutputBuffer(index)!!.run {
                    val array = ByteArray(remaining())
                    get(array)
                    array
                }

                if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
                    Log.i(
                        TAG,
                        "processOutputBuffer: produced keyframe size: ${compressedArray.size}"
                    )
                }

                outputChannel.send(Pair(compressedArray, bufferInfo))
            } catch (ex: ClosedSendChannelException) {
                // ignore
            } catch (ex: IllegalStateException) {
                // ignore
            }

            codec.releaseOutputBuffer(index, false)
        }

        suspend fun stop() {
            // Cancel all children coroutines and wait for them to complete
            encoderScope.coroutineContext[Job]?.apply {
                cancelChildren()
                children.toList().joinAll()
            }

            // Cancel the scope itself
            outputChannel.close()
            encoderScope.cancel()

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
                previewImageReader!!.width,
                previewImageReader!!.height
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

/*
class Yuv420Wrapper(
    val y: PlaneWrapper,
    val u: PlaneWrapper,
    val v: PlaneWrapper,
    val width: Int,
    val height: Int,
    val timestampUs: Long,
)

class PlaneWrapper(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
)
*/
