package desidev.videocall.service.codec

fun Codec.Companion.createAudioEncoder(): Codec<ReceivingPort<AudioBuffer>, ReceivingPort<AudioBuffer>> {
    return AudioEncoder()
}