package desidev.rtc.media

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import java.nio.ByteBuffer

class AudioEncoderActor(
    private val rawAudioBufferChannel: ReceiveChannel<AudioBuffer>
) : Actor<AudioEncoderActor.EncoderAction>(Dispatchers.Default) {

    companion object {
        val TAG = AudioEncoderActor::class.simpleName
    }

    sealed interface EncoderAction {
        data object Start : EncoderAction
        data object Stop : EncoderAction
        data class Configure(val format: MediaFormat) : EncoderAction
    }


    sealed interface CodecEvent {
        data class OnInputBufferAvailable(val index: Int, val codec: MediaCodec) : CodecEvent
        data class OnOutputBufferAvailable(
            val index: Int,
            val info: BufferInfo,
            val codec: MediaCodec
        ) : CodecEvent

        data class OnOutputFormatChanged(val format: MediaFormat) : CodecEvent
    }


    val outputChannel = Channel<AudioBuffer>(Channel.BUFFERED)
    val outputFormat = CompletableDeferred<MediaFormat>()
    private val handlerThread = HandlerThread("AudioEncoderActor").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val handlerDispatcher = handler.asCoroutineDispatcher()
    private val handlerScope = CoroutineScope(handlerDispatcher)

    private lateinit var mediaCodec: MediaCodec


    @OptIn(ObsoleteCoroutinesApi::class)
    private val processInputBuffActor = handlerScope.actor<CodecEvent.OnInputBufferAvailable>(capacity = 4) {
        consumeEach {
            try {
                val (index, codec) = it
                val buffer = codec.getInputBuffer(index)!!

                val audioBuffer = rawAudioBufferChannel.receive()
                buffer.put(audioBuffer.buffer)

                codec.queueInputBuffer(
                    index,
                    0,
                    audioBuffer.buffer.size,
                    audioBuffer.ptsUs,
                    audioBuffer.flags
                )
            } catch (e: ClosedReceiveChannelException) {
                // ignore
            }
        }
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private val processOutputBuffers = handlerScope.actor<CodecEvent.OnOutputBufferAvailable>(capacity = 4) {
        consumeEach {
            val (index, info, codec) = it
            val buffer = codec.getOutputBuffer(index)!!

            val audioBuffer = AudioBuffer(
                buffer.readArray(),
                info.presentationTimeUs,
                info.flags
            )

            codec.releaseOutputBuffer(index, false)

            outputChannel.send(audioBuffer)
        }
    }

    override suspend fun onNextAction(action: EncoderAction) {
        when (action) {
            is EncoderAction.Start -> {
                mediaCodec.start()
            }

            EncoderAction.Stop -> {
                with(handlerScope.coroutineContext.job) {
                    cancelChildren()
                    children.toList().joinAll()
                }

                outputChannel.close()
                handlerThread.quitSafely()
                handlerScope.cancel()

                try {
                    mediaCodec.stop()
                    mediaCodec.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not stop media codec")
                }
            }

            is EncoderAction.Configure -> {
                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                listenCodecEvent()
                mediaCodec.configure(action.format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
    }


    private fun listenCodecEvent() {
        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                processInputBuffActor.trySendBlocking(
                    CodecEvent.OnInputBufferAvailable(
                        index,
                        codec
                    )
                )
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: BufferInfo) {
                processOutputBuffers.trySendBlocking(
                    CodecEvent.OnOutputBufferAvailable(
                        index,
                        info,
                        codec
                    )
                )
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Exception in codec: $e")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                outputFormat.complete(format)
            }
        }, handler)
    }


    private fun ByteBuffer.readArray(): ByteArray {
        val array = ByteArray(remaining())
        get(array)
        return array
    }
}