package desidev.videocall.service.camera

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.Float.max
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


/**
 * This is the old implementation of the Camera class. It is not used in the project anymore.
 * It is kept here for reference purposes.
 */

class Camera(var cameraId: String, private val cameraManager: CameraManager) {
    sealed class Output {
        class VideoFormat(val format: MediaFormat) : Output()
        class Sample(val array: ByteArray, val info: MediaCodec.BufferInfo) : Output()
    }

    private val TAG = Camera::class.simpleName
    private val mScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mOutputSurfaces = mutableListOf<Surface>()
    private var mCameraDevice: CameraDevice? = null
    private var mSession: CameraCaptureSession? = null

    private val mCameraThread = HandlerThread("CameraThread").apply { start() }
    private val mCamHandler = Handler(mCameraThread.looper)

    // Camera characteristics for the current camera id
    val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
    var encoderSurface: Surface? = null
    var previewSurface: Surface? = null
    val output: ReceiveChannel<Output> = Channel()


    @SuppressLint("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCoroutine { cont ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
        }, mCamHandler)
    }

    private suspend fun configSession(): CameraCaptureSession = suspendCoroutine { cont ->
        mOutputSurfaces.clear()
        previewSurface?.let { mOutputSurfaces.add(it) }
        encoderSurface?.let { mOutputSurfaces.add(it) }

        if (mOutputSurfaces.size > 0) {
            mCameraDevice!!.createCaptureSession(
                mOutputSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cont.resumeWithException(RuntimeException("Could not configured session"))
                    }
                },
                mCamHandler
            )
        }
    }

    private fun getMaxVideoSize(): Size {
        val config =
            cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (config != null) {
            return config.getOutputSizes(MediaRecorder::class.java)
                .find { it.width <= 480 }!!
        }
        return Size(0, 0)
    }

    fun startCapturing() {
        mScope.launch(CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
        }) {
            readyVideoEncoder()
            mCameraDevice = openCamera()
            mSession = configSession()

            val recordRequest =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).run {
                    encoderSurface?.let { addTarget(it) }
                    previewSurface?.let { addTarget(it) }
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    // Set exposure compensation
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3)

                    build()
                }

            withContext(Dispatchers.Main) {
                mSession!!.setRepeatingRequest(recordRequest, null, null)
            }
        }
    }

    fun stopRecording() {
        mScope.coroutineContext.cancelChildren()
        (output as Channel).close()
        encoderSurface?.release()
        mSession?.close()
        mCameraDevice?.close()
    }

    private fun CoroutineScope.readyVideoEncoder() {
        val timeout = 100_000L
        try {
            val size = getMaxVideoSize()
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                size.width,
                size.height
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 200000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = encoder.createInputSurface()
            encoder.start()

            launch {
                try {
                    while (isActive) {
                        val info = MediaCodec.BufferInfo()
                        val status = encoder.dequeueOutputBuffer(info, timeout)
                        if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            Log.d(TAG, "videoEncoder: output format changed!")
                            val format = encoder.outputFormat
                            format.setInteger(
                                MediaFormat.KEY_ROTATION,
                                cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                            )
                            (output as Channel).send(Output.VideoFormat(format))
                            println("VideoEncoder output format changed: ${encoder.outputFormat}")
                        } else if (status >= 0 && info.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                            val buffer = encoder.getOutputBuffer(status)!!
                            buffer.position(info.offset)
                            buffer.limit(info.size)

                            val array = ByteArray(info.size)
                            buffer.get(array)
                            (output as Channel).send(Output.Sample(array, info))
                            encoder.releaseOutputBuffer(status, false)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.invokeOnCompletion {
                encoder.stop()
                encoder.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        fun getCameraId(manager: CameraManager, lensFacing: Int): String {
            for (id in manager.cameraIdList) {
                val charact = manager.getCameraCharacteristics(id)
                if (charact.get(CameraCharacteristics.LENS_FACING) != lensFacing) continue
                return id
            }
            throw IllegalArgumentException("Could not find any camera on this device with lensFacing $lensFacing")
        }

        fun findBestPreviewSize(
            containerSize: Size,
            characteristics: CameraCharacteristics
        ): Size {
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(SurfaceTexture::class.java)
            return (sizes.find { containerSize.width * containerSize.height > it.width * it.height }
                ?: sizes.last())
        }

        fun computeRelativeRotation(
            characteristics: CameraCharacteristics,
            deviceRotationDeg: Int
        ): Int {
            val sensorOrientationDegrees =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

            /*val deviceOrientationDegrees = when (deviceRotationDeg) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }*/

            // Reverse device orientation for front-facing cameras
            val sign = if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            ) 1 else -1

            // Calculate desired JPEG orientation relative to camera orientation to make
            // the image upright relative to the device orientation
            return (sensorOrientationDegrees - (deviceRotationDeg * sign) + 360) % 360
        }

        fun buildTargetTexture(
            containerView: TextureView,
            characteristics: CameraCharacteristics,
            surfaceRotation: Int
        ): SurfaceTexture? {
            val windowSize = Size(containerView.width, containerView.height)
            val previewSize = findBestPreviewSize(windowSize, characteristics)
            val sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val isRotationRequired =
                computeRelativeRotation(characteristics, surfaceRotation) % 180 != 0

            /* Scale factor required to scale the preview to its original size on the x-axis */
            var scaleX = 1f
            /* Scale factor required to scale the preview to its original size on the y-axis */
            var scaleY = 1f

            if (sensorOrientation == 0) {
                scaleX =
                    if (!isRotationRequired) {
                        windowSize.width.toFloat() / previewSize.height
                    } else {
                        windowSize.width.toFloat() / previewSize.width
                    }

                scaleY =
                    if (!isRotationRequired) {
                        windowSize.height.toFloat() / previewSize.width
                    } else {
                        windowSize.height.toFloat() / previewSize.height
                    }
            } else {
                scaleX =
                    if (isRotationRequired) {
                        windowSize.width.toFloat() / previewSize.height
                    } else {
                        windowSize.width.toFloat() / previewSize.width
                    }

                scaleY =
                    if (isRotationRequired) {
                        windowSize.height.toFloat() / previewSize.width
                    } else {
                        windowSize.height.toFloat() / previewSize.height
                    }
            }

            /* Scale factor required to fit the preview to the TextureView size */
            val finalScale = max(scaleX, scaleY)
            val halfWidth = windowSize.width / 2f
            val halfHeight = windowSize.height / 2f

            val matrix = Matrix()

            if (isRotationRequired) {
                matrix.setScale(
                    1 / scaleX * finalScale,
                    1 / scaleY * finalScale,
                    halfWidth,
                    halfHeight
                )
            } else {
                matrix.setScale(
                    windowSize.height / windowSize.width.toFloat() / scaleY * finalScale,
                    windowSize.width / windowSize.height.toFloat() / scaleX * finalScale,
                    halfWidth,
                    halfHeight
                )
            }

            // Rotate to compensate display rotation
            matrix.postRotate(
                -surfaceRotation.toFloat(),
                halfWidth,
                halfHeight
            )

            containerView.setTransform(matrix)

            return containerView.surfaceTexture?.apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }
        }
    }
}