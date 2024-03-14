package desidev.videocall

import android.media.MediaFormat
import desidev.videocall.service.rtcmsg.RTCMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

class MessigeSerdeTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun testSerde() {
        val encoded = ProtoBuf.encodeToByteArray(
            RTCMessage(
            format = RTCMessage.Format(
                map = mapOf(
                    "mime" to RTCMessage.OneOfValue(string = "audio/mp4a-"),
                    "max-bitrate" to RTCMessage.OneOfValue(integer = 10000),
                    "channel-count" to RTCMessage.OneOfValue(integer = 2),
                    "sample-rate" to RTCMessage.OneOfValue(integer = 44100)
                )
            )
        )
        )

        println("Encoded Size: ${encoded.size}")

        val decoded = ProtoBuf.decodeFromByteArray<RTCMessage>(encoded)
        println(decoded)
    }


}






