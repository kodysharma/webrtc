package desidev.rtc.rtcclient

import android.util.Log
import desidev.rtc.rtcmsg.RTCMessage
import desidev.rtc.rtcmsg.RTCMessage.Control.Acknowledge
import desidev.rtc.rtcmsg.RTCMessage.Control.StreamDisable
import desidev.rtc.rtcmsg.RTCMessage.Control.StreamEnable
import desidev.rtc.rtcmsg.RTCMessage.Control.StreamType
import desidev.turnclient.ChannelBinding
import desidev.turnclient.ICECandidate
import desidev.turnclient.ICECandidate.CandidateType
import desidev.turnclient.TurnClient
import desidev.turnclient.attribute.AddressValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import online.desidev.kotlinutils.Action
import online.desidev.kotlinutils.sendAction
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.measureTime

class RTC {
    companion object {
        val TAG = RTC::class.simpleName
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val turn = TurnClient(
        serverAddress = InetSocketAddress("64.23.160.217", 3478),
        username = "test",
        password = "test123",
    )

    private val acknowledgement = MessageAcknowledgement()

    private var localIce: List<ICECandidate>? = null
    private var remoteIce: List<ICECandidate>? = null
    private var dataChannel: ChannelBinding? = null

    private var audioStreamJob: Job? = null
    private var videoStreamJob: Job? = null
    private var trackListener: TrackListener? = null

    private var remoteVideoStreamFormat: RTCMessage.Format? = null
    private var remoteAudioStreamFormat: RTCMessage.Format? = null

    @OptIn(ObsoleteCoroutinesApi::class)
    val actor = scope.actor<Action<*>> {
        consumeEach { action ->
            action.execute()
        }
    }

    suspend fun createLocalIce() {
        actor.sendAction {
            val result = turn.createAllocation()
            if (result.isSuccess) {
                localIce = result.getOrThrow()
            } else {
                Log.e(TAG, "Failed to create allocation", result.exceptionOrNull())
            }
        }.await()
    }

    suspend fun addRemoteIce(iceCandidate: List<ICECandidate>) {
        actor.sendAction {
            remoteIce = iceCandidate
        }.await()
    }

    suspend fun createPeerConnection() {
        actor.sendAction {
            val relay = remoteIce?.find { it.type == CandidateType.RELAY }
                ?: throw IllegalStateException("No remote relay candidate")

            val addressValue = AddressValue.from(
                withContext(Dispatchers.IO) {
                    InetAddress.getByName(relay.ip)
                },
                relay.port
            )

            val result = turn.createChannel(addressValue)
            if (result.isSuccess) {
                dataChannel = result.getOrThrow()
                startListeningChannel()
            } else {
                throw result.exceptionOrNull()!!
            }
        }.await()
    }

    suspend fun closePeerConnection() {
        actor.sendAction {
            videoStreamJob?.let {
                it.cancel()
                videoStreamJob = null
            }
            audioStreamJob?.let {
                it.cancel()
                audioStreamJob = null
            }

            turn.deleteAllocation()
            dataChannel = null
        }.await()
    }

    suspend fun addVideoSource(format: RTCMessage.Format, samples: Flow<RTCMessage.Sample>) {
        actor.sendAction {
            addVideoStream(format, samples)
        }.await()
    }

    suspend fun addAudioSource(format: RTCMessage.Format, samples: Flow<RTCMessage.Sample>) {
        actor.sendAction {
            addAudioStream(format, samples)
        }.await()
    }


    suspend fun clearRemoteIce() {
        actor.sendAction {
            remoteIce = null
        }
    }

    fun setTrackListener(trackListener: TrackListener) {
        this.trackListener = trackListener
    }

    fun getLocalIce(): List<ICECandidate> =
        localIce ?: throw IllegalStateException("Local ICE not created")


    @OptIn(ExperimentalSerializationApi::class)
    private fun startListeningChannel() {
        dataChannel?.receiveMessage {
            val rtcMessage = try {
                ProtoBuf.decodeFromByteArray<RTCMessage>(it)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to decode RTCMessage, ${ex.message}")
                return@receiveMessage
            }

            when {
                rtcMessage.control != null -> {
                    Log.d(TAG, "Received RTCMessage: $rtcMessage")

                    val control = rtcMessage.control
                    if (control.streamEnable != null) {
                        val streamEnable = control.streamEnable
                        scope.launch {
                            actor.sendAction {
                                if (streamEnable.type == StreamType.Video) {
                                    if (remoteVideoStreamFormat == null) {
                                        trackListener?.onVideoStreamAvailable(streamEnable.format)
                                        remoteVideoStreamFormat = streamEnable.format
                                    }
                                } else if (streamEnable.type == StreamType.Audio) {
                                    if (remoteAudioStreamFormat == null) {
                                        trackListener?.onAudioStreamAvailable(streamEnable.format)
                                        remoteAudioStreamFormat = streamEnable.format
                                    }
                                }
                            }
                            sendAck(control.txId)
                        }
                    }

                    if (control.streamDisable != null) {
                        val streamDisable = control.streamDisable
                        scope.launch {
                            actor.sendAction<Unit> {
                                val type = streamDisable.streamType
                                if (type == StreamType.Video) {
                                    remoteVideoStreamFormat = null
                                    trackListener?.onVideoStreamDisable()
                                } else if (type == StreamType.Audio) {
                                    remoteAudioStreamFormat = null
                                    trackListener?.onAudioStreamDisable()
                                }
                            }
                            sendAck(control.txId)
                        }
                    }

                    if (control.ack != null) {
                        val ack = control.ack
                        acknowledgement.acknowledge(ack)
                    }
                }

                rtcMessage.audioSample != null -> {
                    scope.launch {
                        actor.sendAction {
                            trackListener?.onNextAudioSample(rtcMessage.audioSample)
                        }
                    }
                }

                rtcMessage.videoSample != null -> {
                    scope.launch {
                        actor.sendAction {
                            trackListener?.onNextVideoSample(rtcMessage.videoSample)
                        }
                    }
                }
            }
        }
    }

    private fun addVideoStream(format: RTCMessage.Format, samples: Flow<RTCMessage.Sample>) {
        videoStreamJob = scope.launch {
            sendControlMessage(
                RTCMessage.Control(
                    streamEnable = StreamEnable(format, StreamType.Video)
                )
            )

            samples.collect { sample: RTCMessage.Sample ->
                sendMessage(RTCMessage(videoSample = sample))
            }

            Log.i(TAG, "Video Stream Disabled")
            sendControlMessage(RTCMessage.Control(streamDisable = StreamDisable(StreamType.Video)))
        }
    }

    private fun addAudioStream(format: RTCMessage.Format, samples: Flow<RTCMessage.Sample>) {
        audioStreamJob = scope.launch {
            sendControlMessage(
                RTCMessage.Control(
                    streamEnable = StreamEnable(format, StreamType.Audio)
                )
            )

            samples.collect { sample ->
                sendMessage(RTCMessage(audioSample = sample))
            }

            sendControlMessage(
                RTCMessage.Control(
                    streamDisable = StreamDisable(StreamType.Audio)
                )
            )
        }
    }


    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun sendControlMessage(control: RTCMessage.Control) {
        val message = RTCMessage(control = control)
        val bytes = ProtoBuf.encodeToByteArray(message)

        val timeToSend = measureTime {
            do {
                dataChannel?.sendMessage(bytes)
            } while (!acknowledgement.isAck(message.control!!))
        }

        Log.d(
            TAG,
            "Sent control message in ${timeToSend.inWholeMilliseconds} ms, message = $message"
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun sendMessage(message: RTCMessage) {
        val bytes = ProtoBuf.encodeToByteArray(message)
        dataChannel?.sendMessage(bytes)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun sendAck(txId: Int) {
        val ackMsg = RTCMessage(
            control = RTCMessage.Control(
                txId = txId,
                ack = Acknowledge(txId)
            )
        ).let {
            ProtoBuf.encodeToByteArray(it)
        }

        dataChannel?.sendMessage(ackMsg)
    }
}
