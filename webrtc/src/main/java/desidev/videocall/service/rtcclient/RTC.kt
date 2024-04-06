package desidev.videocall.service.rtcclient

import android.util.Log
import desidev.turnclient.ChannelBinding
import desidev.turnclient.ICECandidate
import desidev.turnclient.ICECandidate.CandidateType
import desidev.turnclient.TurnClient
import desidev.turnclient.attribute.AddressValue
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Control.Acknowledge
import desidev.videocall.service.rtcmsg.RTCMessage.Control.StreamDisable
import desidev.videocall.service.rtcmsg.RTCMessage.Control.StreamEnable
import desidev.videocall.service.rtcmsg.RTCMessage.Control.StreamType
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException
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

    sealed class Action<T> {
        val deferred = CompletableDeferred<T>()

        data class AddRemoteIce(val iceCandidate: List<ICECandidate>) : Action<Unit>()
        data object ClearRemoteIce : Action<Unit>()
        data object CreateLocalIce : Action<Unit>()

        data object CreatePeerConnection : Action<Unit>()

        data object ClosePeerConnection : Action<Unit>()

        data class AddVideoStream(
            val format: RTCMessage.Format,
            val samples: Flow<RTCMessage.Sample>
        ) : Action<Unit>()

        data class AddAudioStream(
            val format: RTCMessage.Format,
            val samples: Flow<RTCMessage.Sample>
        ) : Action<Unit>()

        data class OnStreamEnable(val type: StreamType, val format: RTCMessage.Format) :
            Action<Unit>()

        data class OnStreamDisable(val type: StreamType) : Action<Unit>()
        data class OnNextVideoSample(val sample: RTCMessage.Sample) : Action<Unit>()
        data class OnNextAudioSample(val sample: RTCMessage.Sample) : Action<Unit>()
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    val rtcActor = scope.actor<Action<*>> {
        consumeEach { action ->
            when (action) {
                is Action.AddRemoteIce -> {
                    remoteIce = action.iceCandidate
                    action.deferred.complete(Unit)
                }

                is Action.ClearRemoteIce -> {
                    remoteIce = null
                    action.deferred.complete(Unit)
                }

                is Action.CreateLocalIce -> {
                    val result = turn.createAllocation()
                    if (result.isSuccess) {
                        localIce = result.getOrThrow()
                        action.deferred.complete(Unit)
                    } else {
                        action.deferred.completeExceptionally(result.exceptionOrNull()!!)
                        Log.e(TAG, "Failed to create allocation", result.exceptionOrNull())
                    }
                }

                is Action.CreatePeerConnection -> {
                    try {
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

                        action.deferred.complete(Unit)
                    } catch (ex: Exception) {
                        action.deferred.completeExceptionally(ex)
                    }
                }

                is Action.ClosePeerConnection -> {
                    try {
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
                        action.deferred.complete(Unit)
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to close peer connection", e)
                        action.deferred.completeExceptionally(e)
                    }
                }

                is Action.AddVideoStream -> {
                    addVideoStream(action.format, action.samples)
                    action.deferred.complete(Unit)
                }

                is Action.AddAudioStream -> {
                    addAudioStream(action.format, action.samples)
                    action.deferred.complete(Unit)
                }

                is Action.OnStreamEnable -> {
                    if (action.type == StreamType.Video) {
                        if (remoteVideoStreamFormat == null) {
                            trackListener?.onVideoStreamAvailable(action.format)
                            remoteVideoStreamFormat = action.format
                        }
                    } else if (action.type == StreamType.Audio) {
                        if (remoteAudioStreamFormat == null) {
                            trackListener?.onAudioStreamAvailable(action.format)
                            remoteAudioStreamFormat = action.format
                        }
                    }
                    action.deferred.complete(Unit)
                }

                is Action.OnStreamDisable -> {
                    if (action.type == StreamType.Video) {
                        remoteVideoStreamFormat = null
                        trackListener?.onVideoStreamDisable()
                    } else if (action.type == StreamType.Audio) {
                        remoteAudioStreamFormat = null
                        trackListener?.onAudioStreamDisable()
                    }
                    action.deferred.complete(Unit)
                }

                is Action.OnNextVideoSample -> {
                    trackListener?.onNextVideoSample(action.sample)
                    action.deferred.complete(Unit)
                }

                is Action.OnNextAudioSample -> {
                    trackListener?.onNextAudioSample(action.sample)
                    action.deferred.complete(Unit)
                }
            }
        }
    }

    suspend fun createLocalIce() {
        rtcActor.send(Action.CreateLocalIce)
    }

    suspend fun addRemoteIce(iceCandidate: List<ICECandidate>) {
        rtcActor.send(Action.AddRemoteIce(iceCandidate))
    }

    suspend fun createPeerConnection() {
        val action = Action.CreatePeerConnection
        rtcActor.send(action)
        action.deferred.await()
    }

    suspend fun closePeerConnection() {
        val action = Action.ClosePeerConnection
        rtcActor.send(action)
        action.deferred.await()
    }

    suspend fun addVideoSource(format: RTCMessage.Format, samples: Flow<RTCMessage.Sample>) {
        val action = Action.AddVideoStream(format, samples)
        rtcActor.send(action)
        action.deferred.await()
    }

    suspend fun addAudioSource(format: RTCMessage.Format, samples: Flow<RTCMessage.Sample>) {
        val action = Action.AddAudioStream(format, samples)
        rtcActor.send(action)
        action.deferred.await()
    }


    suspend fun clearRemoteIce() {
        val action = Action.ClearRemoteIce
        rtcActor.send(action)
        action.deferred.await()
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
                            val action =
                                Action.OnStreamEnable(streamEnable.type, streamEnable.format)
                            rtcActor.send(action)
                            action.deferred.await()
                            sendAck(control.txId)
                        }
                    }

                    if (control.streamDisable != null) {
                        val streamDisable = control.streamDisable
                        scope.launch {
                            val action = Action.OnStreamDisable(streamDisable.streamType)
                            rtcActor.send(action)
                            action.deferred.await()
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
                        rtcActor.send(Action.OnNextAudioSample(rtcMessage.audioSample))
                    }
                }

                rtcMessage.videoSample != null -> {
                    scope.launch {
                        rtcActor.send(Action.OnNextVideoSample(rtcMessage.videoSample))
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

            sendControlMessage(
                RTCMessage.Control(
                    streamDisable = StreamDisable(StreamType.Video)
                )
            )
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
