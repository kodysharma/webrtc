package test.videocall

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Environment
import android.util.Log
import android.view.Surface
import desidev.utility.asMicroSec
import desidev.utility.asNanoSec
import desidev.utility.minus
import desidev.utility.plusAssign
import desidev.utility.toMicroSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class EncoderWrapper {
    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxerWrapper
    private var videoTrack: Int = -1
    private var isRunning = AtomicBoolean()
    private val scope = CoroutineScope(Dispatchers.IO)
    lateinit var inputSurface: Surface


    companion object {
        private const val TAG = "EncoderWrapper"
    }

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 640).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 125000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        muxer = MediaMuxerWrapper("${Environment.getExternalStorageDirectory()}/video.mp4")
        isRunning = AtomicBoolean(true)
        process()
    }


    private fun process() {
        val timeout = 1000000L
        scope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    val info = MediaCodec.BufferInfo()
                    val status = encoder.dequeueOutputBuffer(info, timeout)
                    if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "videoEncoder: output format changed!")
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()

                    } else if (status >= 0 && info.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                        val buffer = encoder.getOutputBuffer(status)!!
                        buffer.position(info.offset)
                        buffer.limit(info.size)

                        val array = ByteArray(info.size)
                        buffer.get(array)
                        encoder.releaseOutputBuffer(status, false)

                        if (videoTrack != -1) {
                            muxer.writeSampleData(videoTrack, buffer, info)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.invokeOnCompletion {
            encoder.stop()
            encoder.release()
            Log.d(TAG, "process: Encoder stopped")
            muxer.stop()
        }
    }


    private var presentationTimeUs = 0.asMicroSec
    private var prev = 0.asMicroSec

    private fun computePresentationTimeStamp() {
        val curr = System.nanoTime().asNanoSec.toMicroSec()
        val delta = curr - prev
        prev = curr
        presentationTimeUs += delta
    }

    fun stop() {
        scope.cancel()
    }
}