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
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import desidev.rtc.media.ReceivingPort
import desidev.rtc.media.camera.CameraCapture.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Future
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

interface CameraCapture {
    val state: State
    val selectedCamera: SelectedCamera
    suspend fun start()
    suspend fun stop()
    suspend fun release()
    suspend fun selectCamera(cameraFace: CameraLensFacing)
    fun setPreviewFrameListener(listener: ((Image) -> Unit)?)
    fun compressedDataChannel(): ReceivingPort<Pair<ByteArray, BufferInfo>>
    fun getMediaFormat(): Future<MediaFormat>

    companion object {
        fun create(context: Context): CameraCapture {
            return CameraCaptureImpl(context)
        }
    }

    sealed class Quality(val width: Int, val height: Int) {
        data object Low : Quality(640, 480)
        data object Medium : Quality(1280, 720)
        data object High : Quality(1920, 1080)
    }

    enum class State {
        ACTIVE, INACTIVE, RELEASED
    }
}


data class SelectedCamera(
    val lensFacing: CameraLensFacing,
    val id: String
)

class CameraCaptureImpl(context: Context) : CameraCapture {
    private val stateLock = Mutex()
    private var _state = State.INACTIVE
        set(value) {
            Log.d(TAG, "State: [$field] -> [$value]")
            field = value
        }

    override val state: State
        get() = _state
    private val resolution: CameraCapture.Quality = CameraCapture.Quality.Low
    private val _cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var previewImageListener: ((Image) -> Unit)? = null

    private val _previewImageReader: ImageReader =
        ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({
                val image = it.acquireLatestImage()
                if (image != null) {
                    if (previewImageListener != null) {
                        previewImageListener!!.invoke(image)
                    } else {
                        image.close()
                    }
                }
            }, null)
        }

    private val _encoderSurface: Surface

    private val _encoder: desidev.rtc.media.codec.VideoEncoder =
        desidev.rtc.media.codec.Codec.createVideoEncoder()

    private val _cameras: List<SelectedCamera> = _cameraManager.cameraIdList.map { id ->
        val characteristics = _cameraManager.getCameraCharacteristics(id)
        characteristics.get(CameraCharacteristics.LENS_FACING).run {
            val lensFacing = when (this) {
                CameraCharacteristics.LENS_FACING_BACK -> CameraLensFacing.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> CameraLensFacing.FRONT
                CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraLensFacing.EXTERNAL
                else -> throw IllegalArgumentException("Unknown lens facing")
            }
            SelectedCamera(lensFacing, id)
        }
    }


    //    private val _scope = CoroutineScope(Dispatchers.Default)
    private val _handlerThread = HandlerThread("CameraHandler").apply { start() }
    private val _handler = Handler(_handlerThread.looper)

    private lateinit var _sessionCloseDeferred: CompletableDeferred<Unit>
    private lateinit var _cameraCloseDeferred: CompletableDeferred<Unit>

    private lateinit var _session: CameraCaptureSession

    private var currentCamera: SelectedCamera = _cameras.first()
    override val selectedCamera: SelectedCamera
        get() = currentCamera

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            resolution.width,
            resolution.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        _encoder.configure(format)
        _encoderSurface = _encoder.getInputSurface()
    }


    override suspend fun start() {
        stateLock.withLock {
            startCapturing()
            _encoder.startEncoder()
        }
    }

    private suspend fun startCapturing() {
        if (_state == State.INACTIVE) {
            val camDevice = openCamera()
            _session = configSession(camDevice)

            val recordRequest = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).run {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3)
                addTarget(_previewImageReader.surface)
                addTarget(_encoderSurface)
                build()
            }

            withContext(Dispatchers.Main) {
                _session.setRepeatingRequest(recordRequest, null, _handler)
            }
            _state = State.ACTIVE
        }
    }

    override suspend fun stop() {
        stateLock.withLock {
            stopCamera()
            _encoder.stopEncoder()
        }
    }

    private fun isActive() = _state == State.ACTIVE

    private suspend fun stopCamera() {
        try {
            if (isActive()) {
                _session.abortCaptures()
                _session.close()
                _sessionCloseDeferred.await()
                _session.device.close()
                _cameraCloseDeferred.await()
                _state = State.INACTIVE
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
            _state = State.RELEASED
        }
    }

    private fun releaseInstances() {
        try {
            _previewImageReader.close()
            _encoderSurface.release()
            _handlerThread.quitSafely()
            _encoder.release()
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

    override fun compressedDataChannel(): ReceivingPort<Pair<ByteArray, BufferInfo>> {
        return _encoder.getCompressedDataStream()
    }

    override fun getMediaFormat(): Future<MediaFormat> {
        return _encoder.mediaFormat()
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
                add(_previewImageReader.surface)
                add(_encoderSurface)
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


    companion object {
        private const val TAG = "CameraCaptureImpl"
    }
}