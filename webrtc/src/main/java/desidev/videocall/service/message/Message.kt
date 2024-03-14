package desidev.videocall.service.message

import android.media.MediaFormat
import java.nio.ByteBuffer
import desidev.rtc.media.AudioBuffer

interface Message

fun MediaFormat.convertToMessage(): Message =
    if (getString(MediaFormat.KEY_MIME)!!.contains("audio/")) AudioFormat(
        mime = getString(MediaFormat.KEY_MIME)!!,
        bitrate = getInteger(MediaFormat.KEY_BIT_RATE),
//        maxBitrate = getInteger("max-bitrate"),
        channelCount = getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        sampleRate = getInteger(MediaFormat.KEY_SAMPLE_RATE),
        csd0 = getByteBuffer("csd-0")?.run {
            val array = ByteArray(limit())
            get(array)
            array
        },
        csd1 = getByteBuffer("csd-1")?.run {
            val array = ByteArray(limit())
            get(array)
            array
        },
        csd2 = getByteBuffer("csd-2")?.run {
            val array = ByteArray(limit())
            get(array)
            array
        }

    ) else VideoFormat(
        mime = getString(MediaFormat.KEY_MIME)!!,
        framerate = getInteger(MediaFormat.KEY_FRAME_RATE),
        rotation = if (containsKey(MediaFormat.KEY_ROTATION)) getInteger(MediaFormat.KEY_ROTATION) else null,
        width = getInteger(MediaFormat.KEY_WIDTH),
        height = getInteger(MediaFormat.KEY_HEIGHT),
        maxWidth = if (containsKey(MediaFormat.KEY_MAX_WIDTH)) getInteger(MediaFormat.KEY_MAX_WIDTH) else null,
        maxHeight = if (containsKey(MediaFormat.KEY_MAX_HEIGHT)) getInteger(MediaFormat.KEY_MAX_HEIGHT) else null,
        colorFormat = if (containsKey(MediaFormat.KEY_COLOR_FORMAT)) getInteger(MediaFormat.KEY_COLOR_FORMAT) else null,
        colorRange = if (containsKey(MediaFormat.KEY_COLOR_RANGE)) getInteger(MediaFormat.KEY_COLOR_RANGE) else null,
        bitrate = getInteger(MediaFormat.KEY_BIT_RATE),
        maxBitrate = getInteger("max-bitrate"),
        colorStandard = if (containsKey(MediaFormat.KEY_COLOR_STANDARD)) getInteger(MediaFormat.KEY_COLOR_STANDARD) else null,
        colorTransfer = if (containsKey(MediaFormat.KEY_COLOR_TRANSFER)) getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null,

        csd0 = getByteBuffer("csd-0")?.run {
            val array = ByteArray(limit())
            get(array)
            array
        },
        csd1 = getByteBuffer("csd-1")?.run {
            val array = ByteArray(limit())
            get(array)
            array
        },
        csd2 = getByteBuffer("csd-2")?.run {
            val array = ByteArray(limit())
            get(array)
            array
        }
    )

fun VideoFormat.toMediaFormat() =
    MediaFormat.createVideoFormat(mime, width, height).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, framerate)
        rotation?.let { setInteger(MediaFormat.KEY_ROTATION, it) }
        colorFormat?.let { setInteger(MediaFormat.KEY_COLOR_FORMAT, it) }
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger("max-bitrate", maxBitrate)

        csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        csd2?.let { setByteBuffer("csd-2", ByteBuffer.wrap(it)) }

        colorStandard?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
        colorRange?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
        colorTransfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
        maxWidth?.let { setInteger(MediaFormat.KEY_MAX_WIDTH, it) }
        maxHeight?.let { setInteger(MediaFormat.KEY_MAX_HEIGHT, it) }
    }

fun AudioBuffer.audioSample() = AudioSample(this.ptsUs, this.flags, this.buffer)

fun AudioFormat.toMediaFormat() =
    MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
        setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)

        csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        csd2?.let { setByteBuffer("csd-2", ByteBuffer.wrap(it)) }
    }