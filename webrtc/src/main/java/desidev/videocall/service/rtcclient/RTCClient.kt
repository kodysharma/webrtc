package desidev.videocall.service.rtcclient

import android.media.MediaFormat
import desidev.turnclient.ICECandidate
import desidev.videocall.service.message.AudioFormat
import desidev.videocall.service.message.AudioSample
import desidev.videocall.service.message.VideoFormat
import desidev.videocall.service.message.VideoSample
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Format
import desidev.videocall.service.rtcmsg.RTCMessage.Sample
import kotlinx.coroutines.channels.ReceiveChannel

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
    fun addStream(format : Format, channel: ReceiveChannel<Sample>)
    fun setTrackListener(listener: TrackListener)

    fun dispose()
}