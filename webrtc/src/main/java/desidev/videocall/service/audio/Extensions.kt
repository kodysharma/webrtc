package desidev.videocall.service.audio

import kotlinx.coroutines.channels.ReceiveChannel

fun AudioEncoder.Companion.defaultAudioEncoder(): AudioEncoder<ReceiveChannel<AudioBuffer>> {
    return DefaultAudioEncoder()
}