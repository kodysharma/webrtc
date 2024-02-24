package test.videocall

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import com.google.gson.GsonBuilder


class CameraWrapper(
    context: Context
) {
    private val TAG = "CameraWrapper"
    private var cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var lensFacing = CameraCharacteristics.LENS_FACING_FRONT
    private var handlerThread = HandlerThread("CameraHandler").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val imageReader by lazy {
        val size = getMaxVideoSize(getCameraId())
        ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
    }
    private val surfaces = mutableListOf(imageReader.surface)
    private var cameraDevice: CameraDevice? = null

    private val encoderWrapper: EncoderWrapper = EncoderWrapper()

    private fun getCameraId(): String {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            Log.d(TAG, "Camera id: $cameraId")
            logCameraCharacteristics(characteristics)

            if (characteristics.get(CameraCharacteristics.LENS_FACING) == lensFacing) {
                return cameraId
            }
        }

        throw RuntimeException("No camera found")
    }

    @SuppressWarnings("MissingPermission")
    private fun openCamera(id: String) {
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                Log.d(TAG, "Camera with id $id opened")

                createSession()
            }

            override fun onClosed(camera: CameraDevice) {
                Log.d(TAG, "Camera with id $id closed")
            }

            override fun onDisconnected(camera: CameraDevice) {
                cameraDevice?.close()
                cameraDevice = null
                Log.d(TAG, "Camera with id $id disconnected")
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

                Log.e(TAG, "Camera error: $msg")
            }
        }, handler)
    }

    private fun createSession() {
        cameraDevice?.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Camera session configured")
                    requestContinuousImages(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera session configuration failed")
                }
            },
            handler
        )
    }


    private fun requestContinuousImages(session: CameraCaptureSession) {
        SENSOR_INFO_SENSITIVITY_RANGE
        val captureRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

        /* val captureRunnable = object : Runnable {
             override fun run() {
                 captureRequest.apply {
                     addTarget(imageReader.surface)
 //                    set(CaptureRequest.CONTROL_AE_LOCK, false)
 //                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
 //                    set(
 //                        CaptureRequest.CONTROL_AF_MODE,
 //                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
 //                    )
 //                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                 }
                 session.capture(captureRequest.build(), null, null)
                 handler.post(this)
             }
         }*/

        surfaces.forEach {
            captureRequest.addTarget(it)
        }
        session.setRepeatingRequest(captureRequest.build(), null, null)
    }

    fun startCamera() {
        val cameraId = getCameraId()
        openCamera(cameraId)
    }

    fun stopCamera() {
        cameraDevice?.close()
        handlerThread.quitSafely()
        imageReader.close()
    }

    private fun getMaxVideoSize(id: String): Size {
        val config = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (config != null) {
            return config.getOutputSizes(ImageFormat.YUV_420_888)
                .find { it.width == 640 && it.height == 640 }!!
        }
        return Size(0, 0)
    }

    private fun logCameraCharacteristics(characteristics: CameraCharacteristics) {
        val logData = mutableMapOf<String, Any?>()
        val gson = GsonBuilder().setPrettyPrinting()
            .serializeNulls()
            .create()

        logData["LensFacing"] = characteristics.get(CameraCharacteristics.LENS_FACING).let {
            when (it) {
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> "Unknown"
            }
        }

        logData["SensorOrientation"] = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)

        val outputFormats =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats
        logData["SupportedOutputFormats"] = let {
            val formats = mutableListOf<String>()
            outputFormats.forEach {
                formats.add(
                    when (it) {
                        ImageFormat.JPEG -> "JPEG"
                        ImageFormat.YUV_420_888 -> "YUV_420_888"
                        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                        ImageFormat.RAW10 -> "RAW10"
                        ImageFormat.RAW12 -> "RAW12"
                        ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
                        ImageFormat.PRIVATE -> "PRIVATE"
                        ImageFormat.YUV_422_888 -> "YUV_422_888"
                        ImageFormat.YUV_444_888 -> "YUV_444_888"
                        ImageFormat.FLEX_RGB_888 -> "FLEX_RGB_888"
                        ImageFormat.FLEX_RGBA_8888 -> "FLEX_RGBA_8888"
                        else -> "Unknown"
                    }
                )
            }
        }

        val supportedSizes =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(ImageFormat.YUV_420_888)
        logData["SupportedResolutionsForYuv_420_888"] =
            supportedSizes.map { "${it.width}x${it.height}" }

        logData["SensitivityRange"] = characteristics.get(SENSOR_INFO_SENSITIVITY_RANGE)

        val fpsRanges =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        logData["FpsRanges"] = fpsRanges.contentToString()

        val supportedStabilization =
            characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        logData["SupportedStabilization"] = supportedStabilization.contentToString()

        Log.d(TAG, gson.toJson(logData))
    }

    fun setFrameListener(listener: (Image) -> Unit) {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            listener(image)
        }, handler)
    }
}



