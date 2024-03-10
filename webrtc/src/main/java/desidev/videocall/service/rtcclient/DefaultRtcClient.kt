package desidev.videocall.service.rtcclient

import android.util.Log
import desidev.turnclient.ChannelBinding
import desidev.turnclient.ICECandidate
import desidev.turnclient.ICECandidate.CandidateType
import desidev.turnclient.TurnClient
import desidev.turnclient.attribute.AddressValue
import desidev.videocall.service.message.AudioFormat
import desidev.videocall.service.message.AudioSample
import desidev.videocall.service.message.VideoFormat
import desidev.videocall.service.message.VideoSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

class DefaultRtcClient(
    turnServerIp: String,
    turnServerPort: Int,
    turnServerUsername: String,
    turnServerPassword: String
) : RTCClient {
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

    override suspend fun createPeerConnection() {
        val relay = remoteCandidate!!.find { it.type == CandidateType.RELAY }!!
        val result =
            turnClient.createChannel(AddressValue.from(InetAddress.getByName(relay.ip), relay.port))
        if (result.isSuccess) {
            dataChannel = result.getOrThrow()
            // callback function to receive messages from the remote peer
            dataChannel!!.receiveMessage { bytes ->
                Log.d(TAG, "on message received: ${bytes.decodeToString()} ")
            }
        } else {
            Log.e(TAG, "Error: ${result.exceptionOrNull()?.message}")
            throw IOException("Error: ${result.exceptionOrNull()?.message}")
        }
    }

    override suspend fun closePeerConnection() {
        tryDeleteAllocation()
    }


    fun startSendingMessage() {
        if (dataChannel == null) throw IllegalStateException("Data channel not found")
        val dataChannel = dataChannel!!
        scope.launch {
            var packetCount = 0
            while (isActive) {
                dataChannel.sendMessage("${packetCount++}".encodeToByteArray())
                delay(1000)
            }
        }
    }

    override fun addVideoStream(format: VideoFormat, channel: ReceiveChannel<VideoSample>) {
        TODO("Not yet implemented")
    }

    override fun addAudioStream(format: AudioFormat, channel: ReceiveChannel<AudioSample>) {
        TODO("Not yet implemented")
    }

    override fun setTrackListener(listener: TrackListener) {
        TODO("Not yet implemented")
    }

    override fun dispose() {
        scope.launch { tryDeleteAllocation() }
            .invokeOnCompletion {
                scope.cancel()
            }
    }


    private suspend fun tryDeleteAllocation() {
        if (localCandidate != null) {
            try {
                turnClient.deleteAllocation()
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                ex.printStackTrace()
            }
            localCandidate = null
        }
    }

    companion object {
        val TAG = DefaultRtcClient::class.java.simpleName
    }
}