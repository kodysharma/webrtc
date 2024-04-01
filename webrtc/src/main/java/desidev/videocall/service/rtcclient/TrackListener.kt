package desidev.videocall.service.rtcclient

import desidev.videocall.service.rtcmsg.RTCMessage

interface TrackListener {
    fun onVideoStreamAvailable(videoFormat: RTCMessage.Format)
    fun onAudioStreamAvailable(audioFormat: RTCMessage.Format)
    fun onNextVideoSample(videoSample: RTCMessage.Sample)
    fun onNextAudioSample(audioSample: RTCMessage.Sample)
    fun onVideoStreamDisable()
    fun onAudioStreamDisable()
}