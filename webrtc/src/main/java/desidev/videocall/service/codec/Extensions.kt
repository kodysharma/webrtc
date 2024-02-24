package desidev.videocall.service.codec

import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat




fun Codec.configure(format: AudioFormat) {
    val mediaFormat = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        format.sampleRate,
        format.channelCount
    ).apply {
        setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        setInteger(MediaFormat.KEY_BIT_RATE, 36000)
        setInteger(MediaFormat.KEY_PCM_ENCODING, format.encoding)
    }

    configure(mediaFormat)
}
