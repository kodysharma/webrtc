package desidev.rtc.media

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking

class AudioEncoderActor(scope: CoroutineScope) : Actor<AudioEncoderActor.EncoderAction>(scope) {

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
        data class OnOutputBufferAvailable(val index: Int, val info: BufferInfo): CodecEvent
        data class OnOutputFormatChanged(val format: MediaFormat): CodecEvent
    }


    val outputChannel = Channel<AudioBuffer>(Channel.BUFFERED)
    val outputFormat = CompletableDeferred<MediaFormat>()

    private lateinit var mediaCodec: MediaCodec
    private val codecEventChannel = Channel<CodecEvent>()


    @OptIn(ObsoleteCoroutinesApi::class)
    private val processInputBuffActor = scope.actor<CodecEvent.OnInputBufferAvailable> {
        consumeEach {
            val buffer = mediaCodec.getInputBuffer(it.index)
        }
    }

    override suspend fun onNextAction(action: EncoderAction) {
        when (action) {
            is EncoderAction.Start -> {
                mediaCodec.start()
            }

            EncoderAction.Stop -> {
                mediaCodec.stop()
                mediaCodec.release()
            }

            is EncoderAction.Configure -> {
                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                listenCodecEvent()
                mediaCodec.configure(action.format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
    }


    private fun listenCodecEvent() {
        mediaCodec.setCallback(object : MediaCodec.Callback () {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                codecEventChannel.trySendBlocking(CodecEvent.OnInputBufferAvailable(index, codec))
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: BufferInfo) {
                codecEventChannel.trySendBlocking(CodecEvent.OnOutputBufferAvailable(index, info))
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Exception in codec: $e")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                codecEventChannel.trySendBlocking(CodecEvent.OnOutputFormatChanged(format))
            }
        })
    }
}