package desidev.videocall

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaFormat
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.gson.GsonBuilder
import desidev.rtc.media.camera.CameraCapture
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Control.ControlData
import desidev.videocall.service.rtcmsg.toRTCFormat
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer

class SampleTest {

    @JvmField
    @Rule
    val cameraPermission = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun sample() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)))
        }
        val encoded = ProtoBuf.encodeToByteArray(
            RTCMessage(
                control = RTCMessage.Control(
                    flags = RTCMessage.Control.STREAM_ENABLE,
                    data = ControlData(format.toRTCFormat(), 0)
                )
            )
        )
        val decoded = ProtoBuf.decodeFromByteArray<RTCMessage>(encoded)
        Log.d("decoded", decoded.toString())
    }

    @Test
    fun cameraSampleTest() {
        Log.d("cameraSampleTest", "start")
        runBlocking {
            val cameraCapture =
                CameraCapture.create(InstrumentationRegistry.getInstrumentation().targetContext)
            cameraCapture.start()

            Log.d("cameraSampleTest", "Camera capture started")
            val port = cameraCapture.compressedDataChannel()

            while (port.isOpenForReceive) {
                val sample = port.receive().run {
                    RTCMessage.Sample(
                        ptsUs = second.presentationTimeUs,
                        buffer = first,
                        flags = second.flags
                    )
                }
                assertEquals(sample, sampleEncodeDecodeTest(sample))
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
                            StringBuilder().let {
                                it.append(formatName)
                                it.append(": ")
                                it.append(supportedSizes.map { "${it.width}x${it.height}" })
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