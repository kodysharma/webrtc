package desidev.rtc.rtcclient

import android.util.Log
import desidev.rtc.rtcmsg.RTCMessage
import desidev.rtc.rtcmsg.RTCMessage.Control.StreamDisable
import desidev.rtc.rtcmsg.RTCMessage.Control.StreamEnable
import desidev.rtc.rtcmsg.RTCMessage.Control.StreamType
import desidev.turnclient.ChannelBinding
import desidev.turnclient.DataCallback
import desidev.turnclient.ICECandidate
import desidev.turnclient.ICECandidate.CandidateType
import desidev.turnclient.TurnClient
import desidev.turnclient.attribute.AddressValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import online.desidev.kotlinutils.ReentrantMutex
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.measureTime

class RTC {
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
    private var trackListener: TrackListener? = null

    private var remoteVideoStreamFormat: RTCMessage.Format? = null
    private var remoteAudioStreamFormat: RTCMessage.Format? = null

    private val conMutex = ReentrantMutex()
    private val videoStmMutex = ReentrantMutex()
    private val audioStmMutex = ReentrantMutex()

    private var videoStmEnable: Boolean = false
    private var audioStmEnable: Boolean = false

    suspend fun createLocalIce(): List<ICECandidate> {
        return conMutex.withLock {
            val result = turn.createAllocation()
            if (result.isSuccess) {
                result.getOrThrow().also { localIce = it }
            } else {
                throw result.exceptionOrNull()!!
            }
        }
    }

    fun addRemoteIce(iceCandidate: List<ICECandidate>) {
        remoteIce = iceCandidate
    }

    suspend fun reset() {
        turn.reset()
        localIce = null
        remoteIce = null
        dataChannel = null

        if (remoteAudioStreamFormat != null) {
            remoteAudioStreamFormat = null
            trackListener?.onAudioStreamDisable()
        }
        if (remoteVideoStreamFormat != null) {
            remoteVideoStreamFormat = null
            trackListener?.onVideoStreamDisable()
        }

        videoStmMutex.withLock { videoStmEnable = false }
        audioStmMutex.withLock { audioStmEnable = false }
    }


    suspend fun createPeerConnection() {
        conMutex.withLock {
            if (remoteIce == null || localIce == null) {
                throw IllegalStateException("Remote or local ICE not set")
            }

            val relay = remoteIce?.find { it.type == CandidateType.RELAY }
                ?: throw IllegalStateException("No remote relay candidate")

            val addressValue = AddressValue.from(
                withContext(Dispatchers.IO) {
                    InetAddress.getByName(relay.ip)
                }, relay.port
            )

            val result = turn.bindChannel(addressValue)
            if (result.isSuccess) {
                dataChannel = result.getOrThrow()
                startListeningChannel()
            } else {
                throw result.exceptionOrNull()!!
            }
        }
    }


    @OptIn(ExperimentalSerializationApi::class)
    private fun startListeningChannel() {
        dataChannel?.setDataCallback(object : DataCallback {
            override fun onReceived(data: ByteArray) {
                val rtcMessage = try {
                    ProtoBuf.decodeFromByteArray<RTCMessage>(data)
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to decode RTCMessage, ${ex.message}")
                    return
                }
                when {
                    rtcMessage.control != null -> {
                        val control = rtcMessage.control
                        scope.launch { sendAck(control.txId) }
                        when {
                            control.streamEnable != null -> {
                                val streamEnable = control.streamEnable
                                if (streamEnable.type == StreamType.Video) {

                                    if (remoteVideoStreamFormat != null) {
                                        if (remoteVideoStreamFormat != streamEnable.format) {
                                            // remote video stream format has updated
                                            trackListener?.onVideoStreamDisable()
                                            trackListener?.onVideoStreamAvailable(streamEnable.format)
                                        }
                                    } else {
                                        trackListener?.onVideoStreamAvailable(streamEnable.format)
                                        remoteVideoStreamFormat = streamEnable.format
                                    }

                                } else if (streamEnable.type == StreamType.Audio) {

                                    if (remoteAudioStreamFormat != null) {
                                        if (remoteAudioStreamFormat != streamEnable.format) {
                                            // remote audio stream format has updated
                                            trackListener?.onAudioStreamDisable()
                                            trackListener?.onAudioStreamAvailable(streamEnable.format)
                                        }
                                    } else {
                                        trackListener?.onAudioStreamAvailable(streamEnable.format)
                                        remoteAudioStreamFormat = streamEnable.format
                                    }

                                }
                            }
                            control.streamDisable != null -> {
                                val streamDisable = control.streamDisable
                                val type = streamDisable.streamType
                                if (type == StreamType.Video) {
                                    remoteVideoStreamFormat = null
                                    trackListener?.onVideoStreamDisable()
                                } else if (type == StreamType.Audio) {
                                    remoteAudioStreamFormat = null
                                    trackListener?.onAudioStreamDisable()
                                }
                            }
                        }
                    }

                    rtcMessage.acknowledge != null -> {
                        acknowledgement.acknowledge(rtcMessage.acknowledge)
                    }

                    rtcMessage.audioSample != null -> {
                        trackListener?.onNextAudioSample(rtcMessage.audioSample)
                    }

                    rtcMessage.videoSample != null -> {
                        trackListener?.onNextVideoSample(rtcMessage.videoSample)
                    }
                }
            }
        })
    }

    suspend fun closePeerConnection() {
        withContext(NonCancellable) {
            conMutex.withLock {
                remoteIce = null
                dataChannel?.close()
                dataChannel = null

                if (remoteAudioStreamFormat != null) {
                    remoteAudioStreamFormat = null
                    trackListener?.onAudioStreamDisable()
                }
                if (remoteVideoStreamFormat != null) {
                    remoteVideoStreamFormat = null
                    trackListener?.onVideoStreamDisable()
                }

                videoStmMutex.withLock { videoStmEnable = false }
                audioStmMutex.withLock { audioStmEnable = false }
            }
        }
    }

    suspend fun isPeerConnectionExist(): Boolean = conMutex.withLock { dataChannel != null }

    fun setTrackListener(trackListener: TrackListener) {
        this.trackListener = trackListener
    }

    fun getLocalIce(): List<ICECandidate> =
        localIce ?: throw IllegalStateException("Local ICE not created")


    suspend fun enableVideoStream(format: RTCMessage.Format) {
        videoStmMutex.withLock {
            if (!isPeerConnectionExist()) {
                throw IllegalStateException("Peer connection is closed!")
            }
            sendControlMessage(
                RTCMessage.Control(
                    streamEnable = StreamEnable(format, StreamType.Video)
                )
            )
            videoStmEnable = true
        }
    }


    suspend fun disableVideoStream() {
        videoStmMutex.withLock {
            if (!isPeerConnectionExist()) throw IllegalStateException("Peer connection is closed!")
            sendControlMessage(RTCMessage.Control(streamDisable = StreamDisable(StreamType.Video)))
            videoStmEnable = false
        }
    }

    suspend fun sendVideoSample(sample: RTCMessage.Sample) {
        videoStmMutex.withLock {
            if (videoStmEnable) {
                sendMessage(RTCMessage(videoSample = sample))
            }
        }
    }

    suspend fun enableAudioStream(format: RTCMessage.Format) {
        audioStmMutex.withLock {
            if (!isPeerConnectionExist()) {
                throw IllegalStateException("Peer connection is closed!")
            }

            sendControlMessage(
                RTCMessage.Control(
                    streamEnable = StreamEnable(format, StreamType.Audio)
                )
            )
            audioStmEnable = true
        }
    }


    suspend fun disableAudioStream() {
        audioStmMutex.withLock {
            val peerConnectionExist = conMutex.withLock { dataChannel != null }
            if (!peerConnectionExist) {
                throw IllegalStateException("Peer connection is closed!")
            }
            sendControlMessage(
                RTCMessage.Control(
                    streamDisable = StreamDisable(StreamType.Audio)
                )
            )
            audioStmEnable = false
        }
    }

    suspend fun sendAudioSample(sample: RTCMessage.Sample) {
        audioStmMutex.withLock {
            if (audioStmEnable) {
                sendMessage(RTCMessage(audioSample = sample))
            }
        }
    }


    private suspend fun sendControlMessage(control: RTCMessage.Control) {
        val message = RTCMessage(control = control)
        val timeToSend = measureTime {
            do {
                sendMessage(message)
            } while (!acknowledgement.isAck(message.control!!))
        }
        Log.d(
            TAG,
            "Sent control message in ${timeToSend.inWholeMilliseconds} ms, message = $message"
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun sendMessage(message: RTCMessage) {
        withContext(Dispatchers.IO) {
            val bytes = ProtoBuf.encodeToByteArray(message)
            dataChannel?.sendData(bytes)
        }
    }

    private suspend fun sendAck(txId: Int) {
        withContext(Dispatchers.IO) {
            val ackMsg = RTCMessage(acknowledge = RTCMessage.Acknowledge(txId = txId))
            sendMessage(ackMsg)
        }
    }

    fun close() {
        scope.coroutineContext.cancelChildren()
        scope.launch {
            withContext(NonCancellable) {
                if (isPeerConnectionExist()) {
                    try {
                        trackListener = null
                        closePeerConnection()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to close peer connection, ${ex.message}")
                    }
                }

                turn.deleteAllocation()
            }
        }
        scope.cancel()
    }

    companion object {
        val TAG = RTC::class.simpleName
    }
}
