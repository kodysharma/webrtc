package desidev.videocall.service.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DefaultAudioEncoder : AudioEncoder<ReceiveChannel<AudioBuffer>> {
    private val tag = DefaultAudioEncoder::class.simpleName

    private var _state: AudioEncoder.EncoderState =
        AudioEncoder.EncoderState.STOPPED(AudioEncoder.ConfigState.UNCONFIG)


    private lateinit var _outputChannel: Channel<AudioBuffer>

    /**
     * Raw audio buffer will be received from the user and will be enqueued to the encoder.
     */
    private lateinit var _inputChannel: Channel<AudioBuffer>

    private lateinit var _codec: MediaCodec

    private val _handlerThread = HandlerThread("AudioEncoder").apply { start() }
    private val _handler = android.os.Handler(_handlerThread.looper)

    private lateinit var _outputFormatFuture: CompletableFuture<MediaFormat>
    private val lock = ReentrantLock()

    private lateinit var _inputFormat: MediaFormat

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            try {
                val audioBuffer = dequeRawData()
                lock.withLock {
                    if (_state is AudioEncoder.EncoderState.RUNNING) {
                        codec.getInputBuffer(index)?.let { codecBuffer ->
                            codecBuffer.put(audioBuffer.buffer)
                            codec.queueInputBuffer(
                                index,
                                0,
                                audioBuffer.buffer.size,
                                audioBuffer.ptsUs,
                                audioBuffer.flags
                            )
                        }
                    }
                }
            } catch (ex: ClosedReceiveChannelException) {
                Log.d(tag, "onInputBufferAvailable: Channel is closed")
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { byteBuffer ->
                val audioBuffer = AudioBuffer(
                    ByteArray(info.size),
                    info.presentationTimeUs,
                    info.flags
                )
                byteBuffer.get(audioBuffer.buffer)
                byteBuffer.clear()

                enqueEncodedData(audioBuffer)
                codec.releaseOutputBuffer(index, false)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.d(tag, "onError: ${e.message}")
            e.printStackTrace()

            _codec.release()
            _state = AudioEncoder.EncoderState.STOPPED(AudioEncoder.ConfigState.UNCONFIG)
            if (!_outputFormatFuture.isDone) _outputFormatFuture.cancel(true)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            _outputFormatFuture.complete(format)
            Log.d(tag, "onOutputFormatChanged: $format")
        }
    }

    override val state: AudioEncoder.EncoderState
        get() = _state

    override val encodedData: ReceiveChannel<AudioBuffer>
        get() = _outputChannel


    private fun dequeRawData(): AudioBuffer {
        return runBlocking {
            _inputChannel.receive()
        }
    }

    private fun enqueEncodedData(audioBuffer: AudioBuffer) {
        _outputChannel.trySend(audioBuffer).let {
            if (!it.isSuccess) Log.d(tag, "enqueEncodedData: Dropped encoded data: $audioBuffer")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun waitForInputToProcessed() {
        while (!_inputChannel.isEmpty) {
            Log.d(tag, "waitForChannelToBecomeEmpty: Waiting for channel to become empty")
            Thread.sleep(100)
        }
    }


    private fun AudioFormat.toMediaFormat(): MediaFormat {
        return MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 36000)
            setInteger(MediaFormat.KEY_PCM_ENCODING, encoding)
        }
    }

    override fun configure(audioFormat: AudioFormat) {
        lock.withLock {
            if (_state is AudioEncoder.EncoderState.STOPPED) {
                val stopped = _state as AudioEncoder.EncoderState.STOPPED
                if (stopped.configState is AudioEncoder.ConfigState.CONFIGURED) {
                    _codec.release()
                }

                _codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                _inputFormat = audioFormat.toMediaFormat()

                _codec.setCallback(callback, _handler)

                _state = AudioEncoder.EncoderState.STOPPED(AudioEncoder.ConfigState.CONFIGURED)
                _outputFormatFuture = CompletableFuture()

                Log.d(tag, "configure: Encoder configured")
            } else {
                Log.d(
                    tag,
                    "configure: Encoder is not in STOPPED state, first stop the encoder and then configure"
                )
            }
        }
    }

    /**
     * Start the encoder. Before starting the encoder, it should be configured with [configure] method.
     * The encoder will start encoding the raw audio buffer and the encoded data will be available in [encodedData] channel.
     */

    override fun startEncoder() {
        lock.withLock {
            if (_state is AudioEncoder.EncoderState.STOPPED) {
                val configState = (_state as AudioEncoder.EncoderState.STOPPED).configState
                if (configState is AudioEncoder.ConfigState.CONFIGURED) {
                    _codec.configure(_inputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    _codec.start()
                    _inputChannel = Channel(capacity = Channel.BUFFERED)
                    _outputChannel = Channel(capacity = Channel.BUFFERED)
                    _state = AudioEncoder.EncoderState.RUNNING
                }

                Log.d(tag, "startEncoder: Encoder started")
            } else {
                Log.d(tag, "startEncoder: Encoder is not in STOPPED state")
            }
        }
    }

    /**
     * Stop the encoder. The encoder will be in [AudioEncoder.EncoderState.STOPPED] state after stopping.
     * This closes the [encodedData] channel. when you start the encoder again, a new encoded data channel [encodedData] will be created.
     */
    override fun stopEncoder() {
        lock.withLock {
            if (_state is AudioEncoder.EncoderState.RUNNING) {
                waitForInputToProcessed()
                _codec.flush()
                _codec.stop()
                _inputChannel.close()
                _outputChannel.close()
                _state = AudioEncoder.EncoderState.STOPPED(AudioEncoder.ConfigState.CONFIGURED)

                Log.d(tag, "stopEncoder: Encoder stopped")

            } else {
                Log.d(tag, "stopEncoder: Encoder is not in RUNNING state")
            }
        }
    }


    override fun enqueRawBuffer(audioBuffer: AudioBuffer) {
        lock.withLock<Unit> {
            if (_state is AudioEncoder.EncoderState.RUNNING) {
                val result = _inputChannel.trySend(audioBuffer)
                if (!result.isSuccess) Log.d(tag, "enqueRawBuffer: Dropped raw data: $audioBuffer")
            } else {
                Log.d(tag, "enqueRawBuffer: Encoder is not in RUNNING state")
            }
        }
    }

    /**
     * Release the encoder. The encoder will be in [AudioEncoder.EncoderState.RELEASED] state after releasing.
     * The encoder can not be used after releasing.
     */
    override fun release() {
        lock.withLock {
            if (_state is AudioEncoder.EncoderState.RUNNING) {
                stopEncoder()
            }
            _codec.release()
            _handlerThread.quitSafely()
            _state = AudioEncoder.EncoderState.RELEASED
            Log.d(tag, "release: Encoder released")
            if (!_outputFormatFuture.isDone) _outputFormatFuture.cancel(true)
        }
    }

    /**
     * If you want to get the media format with the codec specific data `CSD` call this method after starting the encoder.
     * else you can call this method after configuring the encoder.
     */
    override fun mediaFormat(): Future<MediaFormat> {
        assert(_state is AudioEncoder.EncoderState.RUNNING || (_state is AudioEncoder.EncoderState.STOPPED && (_state as AudioEncoder.EncoderState.STOPPED).configState is AudioEncoder.ConfigState.CONFIGURED))
        return if (_state is AudioEncoder.EncoderState.RUNNING) {
            _outputFormatFuture
        } else {
            CompletableFuture.completedFuture(_codec.outputFormat)
        }
    }
}