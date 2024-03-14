package desidev.videocall.service.rtcclient

import android.media.MediaFormat
import android.util.Log
import desidev.turnclient.ChannelBinding
import desidev.turnclient.ICECandidate
import desidev.turnclient.ICECandidate.CandidateType
import desidev.turnclient.TurnClient
import desidev.turnclient.attribute.AddressValue
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Control
import desidev.videocall.service.rtcmsg.RTCMessage.Control.ControlData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DefaultRtcClient(
    turnServerIp: String,
    turnServerPort: Int,
    turnServerUsername: String,
    turnServerPassword: String
) : RTCClient {

    companion object {
        val TAG = DefaultRtcClient::class.java.simpleName
        const val AUDIO_STREAM_ID = 1
        const val VIDEO_STREAM_ID = 2
    }

    // outgoing stream formats
    private var outAudioStreamFormat: RTCMessage.Format? = null
    private var outVideoStreamFormat: RTCMessage.Format? = null

    // incoming track listeners
    private var trackListener: TrackListener? = null

    // remote peer media stream formats
    private var inAudioFormat: RTCMessage.Format? = null
    private var inVideoFormat: RTCMessage.Format? = null

    private var messageAck = MessageAcknowledgement()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val turnClient = TurnClient(
        serverAddress = InetSocketAddress(turnServerIp, turnServerPort),
        username = turnServerUsername,
        password = turnServerPassword,
    )

    private var localCandidate: List<ICECandidate>? = null
    private var remoteCandidate: List<ICECandidate>? = null
    private var dataChannel: ChannelBinding? = null

    override suspend fun createLocalCandidate() {
        tryDeleteAllocation()
        turnClient.createAllocation().let {
            if (it.isSuccess) {
                localCandidate = it.getOrThrow()
            } else {
                throw IOException("Allocation failed: ${it.exceptionOrNull()}")
            }
        }
    }


    override fun getLocalIce(): List<ICECandidate> {
        if (localCandidate == null) {
            throw IllegalStateException("Local ICE candidate not found")
        }
        return localCandidate!!
    }

    override fun setRemoteIce(candidates: List<ICECandidate>) {
        remoteCandidate = candidates
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun createPeerConnection() {

        val relay = remoteCandidate!!.find { it.type == CandidateType.RELAY }!!
        dataChannel = turnClient.createChannel(
            AddressValue.from(
                withContext(Dispatchers.IO) {
                    InetAddress.getByName(relay.ip)
                },
                relay.port
            )
        ).getOrThrow()

        // callback function to receive messages from the remote peer
        dataChannel!!.receiveMessage { bytes ->
            val message = ProtoBuf.decodeFromByteArray<RTCMessage>(bytes)
            when {
                message.audioSample != null -> {
                    Log.d(TAG, "Received audio sample: $message")
                    trackListener?.onNextAudioSample(message.audioSample)
                }

                message.videoSample != null -> {
                    Log.d(TAG, "Received video sample: $message")
                    trackListener?.onNextVideoSample(message.videoSample)
                }

                message.control != null -> {
                    val control = message.control
                    Log.d(TAG, "Received control message: $message")
                    when {
                        control.flags and Control.STREAM_ENABLE != 0 -> {
                            check(control.data?.format != null) { "STREAM_ENABLE_FLAG message does not contain format data" }
                            with(control.data!!) { onStreamEnable(format, streamId) }
                        }

                        control.flags and Control.STREAM_DISABLE != 0 -> {
                            with(control.data!!) { onStreamDisable(streamId) }
                        }

                        control.flags and Control.ACKNOWLEDGE != 0 -> {
                            messageAck.acknoledge(control)
                        }
                    }
                }
            }
        }
    }


    private fun onStreamEnable(format: RTCMessage.Format, id: Int) {
        check(format.map.containsKey(MediaFormat.KEY_MIME)) { "Format does not have mimetype" }
        when (id) {
            AUDIO_STREAM_ID -> {
                inAudioFormat = format
                trackListener?.onAudioStreamAvailable(format)
            }

            VIDEO_STREAM_ID -> {
                inVideoFormat = format
                trackListener?.onVideoStreamAvailable(format)
            }
        }
    }

    private fun onStreamDisable(id: Int) {
        when (id) {
            AUDIO_STREAM_ID -> {
                inAudioFormat = null
                trackListener?.onAudioStreamDisable()
            }

            VIDEO_STREAM_ID -> {
                inVideoFormat = null
                trackListener?.onVideoStreamDisable()
            }
        }
    }

    override suspend fun closePeerConnection() {
        tryDeleteAllocation()
    }

    override fun addStream(format: RTCMessage.Format, channel: ReceiveChannel<RTCMessage.Sample>) {
        val mimeType = format.map[MediaFormat.KEY_MIME]?.string
            ?: throw IllegalArgumentException("Unknown media type")

        when {
            mimeType.startsWith("video/") -> {
                addVideoStream(format, channel)
            }

            mimeType.startsWith("audio/") -> {
                addAudioStream(format, channel)
            }
        }

    }

    @OptIn(ExperimentalSerializationApi::class)
    fun addVideoStream(
        format: RTCMessage.Format,
        channel: ReceiveChannel<RTCMessage.Sample>
    ) {
        this.outAudioStreamFormat = format
        scope.launch {
            sendControlMessage(
                Control(
                    flags = Control.STREAM_ENABLE,
                    data = ControlData(
                        format = format,
                        streamId = 1
                    )
                )
            )

            for (sample in channel) {
                dataChannel?.sendMessage(
                    ProtoBuf.encodeToByteArray(
                        RTCMessage(videoSample = sample)
                    )
                )
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun addAudioStream(format: RTCMessage.Format, channel: ReceiveChannel<RTCMessage.Sample>) {
        this.outVideoStreamFormat = format
        scope.launch {
            sendControlMessage(
                Control(
                    flags = Control.STREAM_ENABLE,
                    data = ControlData(
                        format = format,
                        streamId = 2
                    )
                )
            )

            for (audioSample in channel) {
                dataChannel?.sendMessage(
                    ProtoBuf.encodeToByteArray(RTCMessage(audioSample = audioSample))
                )
            }

            // send disable message
            val disableMessage = RTCMessage(
                control = Control(
                    flags = Control.STREAM_DISABLE,
                )
            )
        }
    }

    override fun setTrackListener(listener: TrackListener) {
        this.trackListener = listener
    }

    override fun dispose() {
        scope.launch { tryDeleteAllocation() }
            .invokeOnCompletion {
                scope.cancel()
            }
    }


    private suspend fun tryDeleteAllocation() {
        if (localCandidate != null) {
            dataChannel = null
            try {
                turnClient.deleteAllocation()
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                ex.printStackTrace()
            }
            localCandidate = null
        }
    }


    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun sendControlMessage(control: Control) {
        val encoded = ProtoBuf.encodeToByteArray(RTCMessage(control = control))
        do {
            dataChannel?.sendMessage(encoded)
        } while (!messageAck.isAck(control))
    }
}

class MessageAcknowledgement {
    interface AckCallback {
        fun onAcknowledge()
    }

    private val timeout: Long = 300
    private val callbacks = mutableMapOf<Int, AckCallback>()
    suspend fun isAck(message: Control): Boolean {
        return withAckTimeout(message.id)
    }

    private suspend fun withAckTimeout(key: Int) = try {
        withTimeout(timeout) {
            return@withTimeout suspendCoroutine { continuation ->
                callbacks[key] = object : AckCallback {
                    override fun onAcknowledge() {
                        continuation.resume(true)
                    }
                }
            }
        }
    } catch (ex: TimeoutCancellationException) {
        false
    }

    fun acknoledge(message: Control) {
        check(message.flags and RTCMessage.Control.ACKNOWLEDGE == RTCMessage.Control.ACKNOWLEDGE) {
            "Message is not an acknowledgement: $message"
        }
        callbacks[message.id]?.onAcknowledge()
    }
}