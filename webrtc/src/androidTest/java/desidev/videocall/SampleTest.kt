package desidev.videocall

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.gson.GsonBuilder
import desidev.rtc.media.camera.CameraCapture
import desidev.videocall.service.rtcmsg.RTCMessage
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Rule
import org.junit.Test

class SampleTest {

    @JvmField
    @Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA)


    @Test
    fun cameraSampleTest() {
        Log.d("cameraSampleTest", "start")
        runBlocking {
            val cameraCapture =
                CameraCapture.create(InstrumentationRegistry.getInstrumentation().targetContext)
            cameraCapture.start()

            Log.d("cameraSampleTest", "Camera capture started")
            val channel = cameraCapture.compressChannel()
            channel.consumeEach {
                val (buffer, info) = it
                val sample = RTCMessage.Sample(
                    ptsUs = info.presentationTimeUs,
                    buffer = buffer,
                    flags = info.flags
                )
                sampleEncodeDecodeTest(sample)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun sampleEncodeDecodeTest(sample: RTCMessage.Sample): RTCMessage.Sample {
        val encoded = ProtoBuf.encodeToByteArray(RTCMessage(videoSample = sample))
        val decoded = ProtoBuf.decodeFromByteArray<RTCMessage>(encoded)
        Log.d(
            "Sample",
            "sample msg wrapped size: ${encoded.size}, sample size: ${decoded.videoSample!!.buffer.size}"
        )
        return decoded.videoSample!!
    }

    @Test
    fun logCameraCharacteristics() {
        val TAG = "CameraCharacteristics"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        fun logCameraCharacteristics(characteristics: CameraCharacteristics) {
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

            logData["SensorOrientation"] =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)

            val outputFormats =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats

            logData["SupportedOutputFormats"] = buildList {
                outputFormats.forEach { formatInt ->
                    add(
                        when (formatInt) {
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
                            ImageFormat.NV21 -> "NV21"
                            ImageFormat.YV12 -> "YV12"
                            else -> "Unknown ($formatInt)"
                        }.let { formatName ->
                            val supportedSizes =
                                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                                    .getOutputSizes(formatInt)
                                    .map { size -> "${size.width}x${size.height}" }

                            StringBuilder().let {
                                it.append(formatName)
                                it.append(": ")
                                it.append(supportedSizes)
                                it.toString()
                            }
                        }
                    )
                }
            }

            logData["SensitivityRange"] =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

            val fpsRanges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

            logData["FpsRanges"] = fpsRanges.contentToString()

            val supportedStabilization =
                characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            logData["SupportedStabilization"] = supportedStabilization.contentToString()

            Log.d(TAG, gson.toJson(logData))
        }


        cameraManager.cameraIdList.forEach { id ->
            logCameraCharacteristics(cameraManager.getCameraCharacteristics(id))
        }
    }
}