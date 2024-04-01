package desidev.videocall

import desidev.videocall.service.rtcclient.MessageAcknowledgement
import desidev.videocall.service.rtcmsg.RTCMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test
import kotlin.coroutines.resume

class MessigeSerdeTest {
//    @OptIn(ExperimentalSerializationApi::class)
//    @Test
//    fun testSerde() {
//        val encoded = ProtoBuf.encodeToByteArray(
//            RTCMessage(
//                control = RTCMessage.Control(
//                    flags = RTCMessage.Control.STREAM_ENABLE,
//                    data = RTCMessage.Control.ControlData(
//                        format = RTCMessage.Format(
//                            map = mapOf(
//                                "mime" to RTCMessage.OneOfValue(string = "audio/mp4a-"),
//                                "max-bitrate" to RTCMessage.OneOfValue(integer = 10000),
//                                "channel-count" to RTCMessage.OneOfValue(integer = 2),
//                                "sample-rate" to RTCMessage.OneOfValue(integer = 44100)
//                            )
//                        ),
//                        streamId = 0
//                    )
//                )
//            )
//        )
//
//        println("Encoded Size: ${encoded.size}")
//
//        val decoded = ProtoBuf.decodeFromByteArray<RTCMessage>(encoded)
//        println(decoded)
//    }
//
//
//    @Test
//    fun acknowledgeTest() {
//        val channel = Channel<RTCMessage>(Channel.BUFFERED)
//        val messageAck = MessageAcknowledgement()
//        val controlMessage = RTCMessage(
//            control = RTCMessage.Control(
//                flags = RTCMessage.Control.STREAM_ENABLE,
//                data = RTCMessage.Control.ControlData(
//                    format = RTCMessage.Format(
//                        map = mapOf(
//                            "mime" to RTCMessage.OneOfValue(string = "audio/mp4a-"),
//                            "max-bitrate" to RTCMessage.OneOfValue(integer = 10000),
//                            "channel-count" to RTCMessage.OneOfValue(integer = 2),
//                            "sample-rate" to RTCMessage.OneOfValue(integer = 44100)
//                        )
//                    ),
//                    streamId = 0
//                )
//            )
//        )
//        runBlocking(Dispatchers.IO) {
//            launch {
//                var attempt = 0
//                val start = System.currentTimeMillis()
//                do {
//                    channel.send(controlMessage)
//                    attempt++
//                    println("Attempt: $attempt")
//                } while (!messageAck.isAck(controlMessage.control!!))
//                val end = System.currentTimeMillis()
//                println("Sent control message in ${end - start} ms. Attempt: $attempt")
//            }
//
//            launch {
//                for (message in channel) {
//                    val ackMsg = RTCMessage.Control(
//                        txId = message.control!!.txId,
//                        flags = RTCMessage.Control.ACKNOWLEDGE,
//                        data = null,
//                    )
//
//                    println("Received message with txId: ${message.control!!.txId}")
//                    delay(500)
//                    messageAck.acknowledge(ackMsg)
//                }
//            }
//        }
//    }


    @Test
    fun timeout() {
        runBlocking {
            withTimeout(500) {
                suspendCancellableCoroutine { cont  ->
                    Thread.sleep(10000)
                    cont.resume(true)
                }
            }
        }
    }
}






