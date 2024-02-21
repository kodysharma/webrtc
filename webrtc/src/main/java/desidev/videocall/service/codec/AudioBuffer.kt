package desidev.videocall.service.codec

import android.media.MediaCodec

class AudioBuffer(
    val buffer: ByteArray,
    val ptsUs: Long,
    val flags: Int
) {
    override fun toString(): String {
        val flagString = when (flags) {
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG -> "BUFFER_FLAG_CODEC_CONFIG ($flags)"
            MediaCodec.BUFFER_FLAG_END_OF_STREAM -> "BUFFER_FLAG_END_OF_STREAM ($flags)"
            MediaCodec.BUFFER_FLAG_KEY_FRAME -> "BUFFER_FLAG_KEY_FRAME ($flags)"
            MediaCodec.BUFFER_FLAG_PARTIAL_FRAME -> "BUFFER_FLAG_PARTIAL_FRAME ($flags)"
            MediaCodec.BUFFER_FLAG_DECODE_ONLY -> "BUFFER_FLAG_DECODE_ONLY ($flags)"
            else -> "BUFFER_FLAG_NONE ($flags)"
        }

        return "AudioBuffer(buffer.size=${buffer.size}, ptsUs=$ptsUs, flags=$flagString)"
    }
}