package test.videocall

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class MediaMuxerWrapper(outputPath: String) {
    private val mediaMuxer: MediaMuxer =
        MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var trackIndex = -1
    private var isStarted = AtomicBoolean(false)

    fun addTrack(format: MediaFormat): Int {
        if (isStarted.get()) {
            throw IllegalStateException("Muxer already started")
        }
        trackIndex = mediaMuxer.addTrack(format)
        return trackIndex
    }

    fun start() {
        mediaMuxer.start()
        isStarted.set(true)
    }

    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!isStarted.get()) {
            throw IllegalStateException("Muxer hasn't started")
        }
        mediaMuxer.writeSampleData(trackIndex, byteBuf, bufferInfo)
    }

    fun stop() {
        if (!isStarted.get()) {
            return
        }
        mediaMuxer.stop()
        mediaMuxer.release()
        isStarted.set(false)
    }
}