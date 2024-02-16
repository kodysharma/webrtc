package desidev.videocall.service.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class AudioEncoder: Observable<AudioBuffer> {
    val TAG = AudioEncoder::class.simpleName

    private val codecScope = CoroutineScope(Dispatchers.Default)
    private val encoderOutputObservers = mutableListOf<Observer<AudioBuffer>>()
    private lateinit var codec: MediaCodec
    private lateinit var observable: Observable<AudioBuffer>
    private val audioFrames = Channel<AudioBuffer>(10)

    private val rawAudioFrameObserver = object : Observer<AudioBuffer> {
        override fun next(subject: AudioBuffer) {
            codecScope.launch { audioFrames.trySend(subject) }
        }
    }

    fun startEncoder(format: MediaFormat, observable: Observable<AudioBuffer>, onOutputFormat: (MediaFormat) -> Unit) {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createEncoderByType(mime)

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                codecScope.launch {
                    val audioBuffer = audioFrames.receive()
                    val byteBuffer = codec.getInputBuffer(index)!!
                    byteBuffer.put(audioBuffer.buffer)
                    codec.queueInputBuffer(
                        index,
                        0,
                        audioBuffer.buffer.size,
                        audioBuffer.ptsUs,
                        audioBuffer.flags
                    )
                }
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                codecScope.launch {
                    val byteBuffer = codec.getOutputBuffer(index)!!
                    val audioBuffer =
                        AudioBuffer(ByteArray(info.size), info.presentationTimeUs, info.flags)
                    byteBuffer.get(audioBuffer.buffer)
                    encoderOutputObservers.forEach { it.next(audioBuffer) }

                    // release the output buffer
                    codec.releaseOutputBuffer(index, false)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { e.printStackTrace() }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                onOutputFormat(format)
            }
        })
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        this.observable = observable

        observable.addObserver(rawAudioFrameObserver)
    }

    fun stopEncoder() {
        try {
            observable.removeObserver(rawAudioFrameObserver)
            codec.stop()
            codec.release()
            Log.d(TAG, "AudioEncoder Stopped")
            audioFrames.cancel()
            codecScope.cancel()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        encoderOutputObservers.clear()
    }


    override fun addObserver(observer: Observer<AudioBuffer>) {
        this.encoderOutputObservers.add(observer)
    }

    override fun removeObserver(observer: Observer<AudioBuffer>) {
        this.encoderOutputObservers.remove(observer)
    }
}