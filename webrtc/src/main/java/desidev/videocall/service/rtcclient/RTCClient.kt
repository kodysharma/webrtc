package desidev.videocall.service.rtcclient

import desidev.turnclient.ICECandidate
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Format
import desidev.videocall.service.rtcmsg.RTCMessage.Sample
import kotlinx.coroutines.flow.Flow

interface TrackListener {
    fun onVideoStreamAvailable(videoFormat: RTCMessage.Format)
    fun onAudioStreamAvailable(audioFormat: RTCMessage.Format)
    fun onNextVideoSample(videoSample: Sample)
    fun onNextAudioSample(audioSample: Sample)
    fun onVideoStreamDisable()
    fun onAudioStreamDisable()
}


interface RTCClient {
    suspend fun createLocalCandidate()
    fun getLocalIce(): List<ICECandidate>
    fun setRemoteIce(candidates: List<ICECandidate>)
    suspend fun createPeerConnection()
    suspend fun closePeerConnection()
    fun addStream(format: Format, channel: Flow<Sample>)
    fun setTrackListener(listener: TrackListener)
    fun dispose()
}