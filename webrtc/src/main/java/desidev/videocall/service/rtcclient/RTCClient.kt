package desidev.videocall.service.rtcclient

import desidev.turnclient.ICECandidate
import desidev.videocall.service.message.AudioFormat
import desidev.videocall.service.message.AudioSample
import desidev.videocall.service.message.VideoFormat
import desidev.videocall.service.message.VideoSample
import kotlinx.coroutines.channels.ReceiveChannel

interface TrackListener {
    fun onVideoStreamAvailable(videoFormat: VideoFormat)
    fun onAudioStreamAvailable(audioFormat: AudioFormat)
    fun onNextVideoSample(videoSample: VideoSample)
    fun onNextAudioSample(audioSample: AudioSample)
    fun onVideoStreamDisable()
    fun onAudioStreamDisable()
}


interface RTCClient {
    suspend fun createLocalCandidate()
    fun getLocalIce(): List<ICECandidate>
    fun setRemoteIce(candidates: List<ICECandidate>)
    suspend fun createPeerConnection()
    suspend fun closePeerConnection()
    fun addVideoStream(format: VideoFormat, channel: ReceiveChannel<VideoSample>)
    fun addAudioStream(format: AudioFormat, channel: ReceiveChannel<AudioSample>)
    fun setTrackListener(listener: TrackListener)
    fun dispose()
}