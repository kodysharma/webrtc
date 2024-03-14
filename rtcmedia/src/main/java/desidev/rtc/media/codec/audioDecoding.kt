package desidev.rtc.media.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import desidev.rtc.media.AudioBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

fun CoroutineScope.audioDecoding(
    inputChannel: Flow<AudioBuffer>,
    mediaFormat: MediaFormat,
    onMediaFormat: (MediaFormat) -> Unit
): Channel<AudioBuffer> {
    val TAG = "audioDecoder"
    var decoder: MediaCodec? = null
    val timeout = 100_000L
    val outputChannel = Channel<AudioBuffer>()
    try {
        decoder = MediaCodec.createDecoderByType(mediaFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(mediaFormat, null, null, 0)
        decoder.start()

        Log.i(TAG, "input format = $mediaFormat")

        val job = launch(Dispatchers.Default) {
            inputChannel.collect {
                val inBuffIx = decoder.dequeueInputBuffer(timeout)
                if (inBuffIx >= 0) {
                    Log.d(TAG, "input data size = ${it.buffer.size}")
                    val inputBuffer = decoder.getInputBuffer(inBuffIx)!!
                    inputBuffer.clear()
                    inputBuffer.put(it.buffer)
                    inputBuffer.flip()

                    decoder.queueInputBuffer(
                        inBuffIx,
                        0,
                        it.buffer.size,
                        it.ptsUs,
                        0
                    )
                }

                val info = MediaCodec.BufferInfo()
                val outBuffIx = decoder.dequeueOutputBuffer(info, timeout)
                if (outBuffIx >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outBuffIx)!!
                    outBuffer.position(info.offset)
                    outBuffer.limit(info.size)

                    val byteArray = ByteArray(info.size)
                    outBuffer.get(byteArray)
                    outBuffer.clear()

//                    Log.d(TAG, "decoded data size = ${info.size}")
                    outputChannel.send(AudioBuffer(byteArray, info.presentationTimeUs, info.flags))

                    decoder.releaseOutputBuffer(outBuffIx, false)
                } else if (outBuffIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onMediaFormat(decoder.outputFormat)
                }
            }
        }
        job.invokeOnCompletion {
            decoder.run {
                stop()
                release()
                Log.d(TAG, "decoder is stopped")
            }
            outputChannel.close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return outputChannel
}
