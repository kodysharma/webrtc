package desidev.videocall

import android.media.MediaFormat
import android.util.Log
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Control.Companion
import desidev.videocall.service.rtcmsg.RTCMessage.Control.ControlData
import desidev.videocall.service.rtcmsg.toRTCFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test
import java.nio.ByteBuffer

class SampleTest {

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
}