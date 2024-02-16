package desidev.videocall.service.video

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

class VideoPlayer(
    val inputFormat: MediaFormat,
    val outputSurface: Surface,
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    companion object { val TAG = VideoPlayer::class.simpleName}
    private lateinit var videoDecoder: MediaCodec
    private val coroutineExceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { coroutineContext, throwable ->
            throwable.printStackTrace()
            inputChannel.close()
        }

    private val inputChannel = Channel<Pair<ByteArray, BufferInfo>>()

    fun play() {
        Log.d(TAG, "play: video decoder input format: $inputFormat")
        scope.launch(coroutineExceptionHandler) {
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            videoDecoder = MediaCodec.createDecoderByType(mime)
            videoDecoder.configure(inputFormat, outputSurface, null, 0)
            videoDecoder.start()

            val timeout = 1_000_000L
            var timeOffset = -1L

            for (input in inputChannel) {
                val status = videoDecoder.dequeueInputBuffer(timeout)
                if (status >= 0) {
                    val info = input.second
                    val array = input.first
                    val buffer = videoDecoder.getInputBuffer(status)!!
                    buffer.clear()
                    buffer.put(array)
                    buffer.limit(info.size)
                    buffer.position(info.offset)
                    videoDecoder.queueInputBuffer(
                        status, info.offset, info.size, info.presentationTimeUs, info.flags
                    )
                } else {
                    Log.d(TAG, "play: no input buffers!")
                }

                val info = BufferInfo()
                val output_buffer = videoDecoder.dequeueOutputBuffer(info, timeout)
                if (output_buffer == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "play: decoder output format changed: ${videoDecoder.outputFormat}")
                } else if (output_buffer >= 0) {
                    if (timeOffset < 0) {
                        timeOffset = (System.nanoTime() * 0.001 - info.presentationTimeUs).roundToLong()
                    }
                    val frameTimeStamp = info.presentationTimeUs + timeOffset
                    val delayTime = (frameTimeStamp - System.nanoTime() * 0.001) // microsecond
//                    Log.d(TAG, "frame timpestamp = $frameTimeStamp, delayTime: $delayTime, timeOffset: $timeOffset" )
                    delay((delayTime * 0.001).roundToLong())
                    videoDecoder.releaseOutputBuffer(output_buffer, frameTimeStamp)
                } else {
                    Log.d(TAG, "play: no output buffers = $output_buffer")
                }
            }

            videoDecoder.stop()
            videoDecoder.release()
        }
    }

    fun stop() { inputChannel.close() }

    suspend fun inputData(buffer: ByteArray, info: BufferInfo) {
        inputChannel.send(buffer to info)
    }
}