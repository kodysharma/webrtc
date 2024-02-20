package desidev.videocall.service.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.HandlerThread
import android.util.Log
import com.google.gson.GsonBuilder
import desidev.videocall.service.SpeedMeter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible


interface AudioEncoder<Sink : Any> {
    val state: EncoderState

    /**
     * Implementation can decide how to provide the data.
     */
    val encodedData: Sink

    /**
     * Configure the encoder with the given [mediaFormat]
     * Audio format should contain the sample rate, channel count and encoding.
     * The encoder will be in [EncoderState.UNCONFIG] state after configuring.
     */
    fun configure(mediaFormat: AudioFormat)


    /**
     * Start the encoder. Before starting the encoder, it should be configured with [configure] method.
     * The encoder will start encoding the raw audio buffer and the encoded data will be available in [encodedData] queue.
     * The encoder will be in [EncoderState.RUNNING] state after starting.
     * The encoder can be stopped by calling [stopEncoder] method.
     */
    fun startEncoder()


    /**
     * Stop the encoder. The encoder will be in [EncoderState.STOPPED] state after stopping.
     * The encoder can be started again by calling [startEncoder] method.
     */
    fun stopEncoder()

    /**
     * Enqueue the raw audio buffer to the encoder.
     * The buffer will be encoded and the encoded data will be available in [encodedData] queue.
     */
    fun enqueRawBuffer(audioBuffer: AudioBuffer)

    /**
     * Release the encoder. The encoder will be in [EncoderState.RELEASED] state after releasing.
     * The encoder can not be used after releasing.
     */
    fun release()


    /**
     * Get the media format of the encoder.
     * The media format will be available after starting the encoder.
     */
    fun mediaFormat(): Deferred<MediaFormat>

    sealed interface ConfigState {
        object UNCONFIG : ConfigState
        object CONFIGURED : ConfigState
    }

    sealed interface EncoderState {
        data class STOPPED(val configState: ConfigState) : EncoderState
        data object RUNNING : EncoderState
        data object UNCONFIG : EncoderState
        data object RELEASED : EncoderState
    }

    companion object
}


/**
 * The default implementation of the [AudioEncoder] interface.
 */
fun AudioEncoder.Companion.defaultAudioEncoder(): DefaultAudioEncoder {
    return DefaultAudioEncoder()
}


class DefaultAudioEncoder : AudioEncoder<ReceiveChannel<AudioBuffer>> {
    private val tag = DefaultAudioEncoder::class.simpleName

    private var _state: AudioEncoder.EncoderState =
        AudioEncoder.EncoderState.STOPPED(AudioEncoder.ConfigState.UNCONFIG)


    private lateinit var _encodedData: Channel<AudioBuffer>

    /**
     * Raw audio buffer will be received from the user and will be enqueued to the encoder.
     */
    private val _rawData: Channel<AudioBuffer> = Channel(capacity = Channel.BUFFERED)

    private lateinit var _codec: MediaCodec

    private val _handlerThread = HandlerThread("AudioEncoder").apply { start() }
    private val _handler = android.os.Handler(_handlerThread.looper)

    private val _scope = CoroutineScope(Dispatchers.Unconfined)


    private lateinit var _outputFormatDeferred: CompletableDeferred<MediaFormat>

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            try {
                dequeRawData().let { audioBuffer ->
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
            } catch (ex: ClosedReceiveChannelException) {
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
            if (!_outputFormatDeferred.isCompleted) _outputFormatDeferred.cancel("Error: ${e.message}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            _outputFormatDeferred.complete(format)

            Log.d(tag, "onOutputFormatChanged: $format")
        }
    }

    override val state: AudioEncoder.EncoderState
        get() = _state

    override val encodedData: ReceiveChannel<AudioBuffer>
        get() = _encodedData


    private fun dequeRawData(): AudioBuffer {
        return runBlocking { _rawData.receive() }
    }

    private fun enqueEncodedData(audioBuffer: AudioBuffer) {
        _encodedData.trySend(audioBuffer).let {
            if (!it.isSuccess) Log.d(tag, "enqueEncodedData: Dropped encoded data: $audioBuffer")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun waitForChannelToBecomeEmpty() {
        while (!_rawData.isEmpty) {
            Log.d(tag, "waitForChannelToBecomeEmpty: Waiting for channel to become empty")
            delay(100)
        }
    }

    override fun configure(mediaFormat: AudioFormat) {
        _scope.launch {
            if (_state is AudioEncoder.EncoderState.STOPPED) {
                _codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    mediaFormat.sampleRate,
                    mediaFormat.channelCount
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 36000)
                    setInteger(MediaFormat.KEY_PCM_ENCODING, mediaFormat.encoding)
                }

                _codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                _codec.setCallback(callback, _handler)

                _state = AudioEncoder.EncoderState.STOPPED(AudioEncoder.ConfigState.CONFIGURED)
                _outputFormatDeferred = CompletableDeferred()

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
        _scope.launch {
            if (_state is AudioEncoder.EncoderState.STOPPED) {
                _codec.start()
                _encodedData = Channel(capacity = Channel.BUFFERED)
                _state = AudioEncoder.EncoderState.RUNNING

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
    override fun stopEncoder(): Unit {
        _scope.launch {
            if (_state is AudioEncoder.EncoderState.RUNNING) {
                waitForChannelToBecomeEmpty()
                _codec.flush()
                _codec.stop()
                _encodedData.close()
                _state = AudioEncoder.EncoderState.STOPPED(AudioEncoder.ConfigState.CONFIGURED)

                Log.d(tag, "stopEncoder: Encoder stopped")

            } else {
                Log.d(tag, "stopEncoder: Encoder is not in RUNNING state")
            }
        }
    }


    override fun enqueRawBuffer(audioBuffer: AudioBuffer) {
        _scope.launch {
            if (_state is AudioEncoder.EncoderState.RUNNING) {
                val result = _rawData.trySend(audioBuffer)
                if (result.isClosed || result.isFailure) {
                    Log.d(tag, "Dropped raw buffer: $audioBuffer")
                }
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
        _scope.launch {
            if (_state is AudioEncoder.EncoderState.RUNNING) {
                stopEncoder()
            }
            _codec.release()
            _handlerThread.quitSafely()
            _state = AudioEncoder.EncoderState.RELEASED
            if (!_outputFormatDeferred.isCompleted) _outputFormatDeferred.cancel("Encoder is released")
        }
    }

    /**
     * If you want to get the media format with the codec specific data `CSD` call this method after starting the encoder.
     * else you can call this method after configuring the encoder.
     */
    override fun mediaFormat(): Deferred<MediaFormat> {
        return _scope.async {
            assert(_state is AudioEncoder.EncoderState.RUNNING || (_state is AudioEncoder.EncoderState.STOPPED && (_state as AudioEncoder.EncoderState.STOPPED).configState is AudioEncoder.ConfigState.CONFIGURED))
            if (_state is AudioEncoder.EncoderState.RUNNING) {
                runBlocking { _outputFormatDeferred.await() }
            } else {
                _codec.outputFormat
            }
        }
    }
}


class AudioEncoderImpl {
    private val TAG = AudioEncoderImpl::class.simpleName
    private val javaExecutor = Executors.newSingleThreadExecutor()

    private lateinit var codec: MediaCodec
    private val rawAudioBuffer = LinkedBlockingQueue<AudioBuffer>()
    private var _encodedData = LinkedBlockingQueue<AudioBuffer>()

    val encodedData: BlockingQueue<AudioBuffer> get() = _encodedData
    val speedMeter = SpeedMeter("AudioEncoder")


    suspend fun startEncoder(format: AudioFormat): MediaFormat = suspendCoroutine { cont ->
        try {
            val mediaFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                format.sampleRate,
                format.channelCount
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 36000)
                setInteger(MediaFormat.KEY_PCM_ENCODING, format.encoding)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    codec.getInputBuffer(index)?.let { codecBuffer ->
                        javaExecutor.execute {
                            try {
                                rawAudioBuffer.take().let { audioChunk ->
                                    codecBuffer.put(audioChunk.buffer)

                                    Log.d(TAG, "enqueInputBuffer: $audioChunk")

                                    codec.queueInputBuffer(
                                        index,
                                        0,
                                        audioChunk.buffer.size,
                                        audioChunk.ptsUs,
                                        audioChunk.flags
                                    )
                                }
                            } catch (ex: Exception) {
                                ex.printStackTrace()
                            }
                        }
                    }
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    javaExecutor.execute {
                        val byteBuffer = codec.getOutputBuffer(index)!!
                        val audioBuffer =
                            AudioBuffer(
                                ByteArray(info.size),
                                info.presentationTimeUs,
                                info.flags
                            )
                        byteBuffer.get(audioBuffer.buffer)

                        try {
                            _encodedData.offer(audioBuffer, 100, TimeUnit.MILLISECONDS)
                                .let {
                                    if (!it) Log.d(
                                        TAG,
                                        "onOutputBufferAvailable: output buffer dropped"
                                    )
                                }
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }

                        Log.d(TAG, "releaseOutputBuffer: $index, $info")
                        codec.releaseOutputBuffer(index, false)
                        speedMeter.update()
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.d("encodeAudioBuffer", "onError: ${e.message}")
                    e.printStackTrace()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    stringyFyMediaFormat(format)
                        .also { Log.d(TAG, "onOutputFormatChanged: $it") }
                    cont.resume(format)
                }
            })

            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            codec.start()

        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    fun stopEncoder() {
        try {
            codec.flush()
            codec.stop()
            _encodedData.clear()
            _encodedData = LinkedBlockingQueue()
            Log.d(TAG, "AudioEncoder Stopped")
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }


    fun enqueRawBuffer(audioBuffer: AudioBuffer) {
        rawAudioBuffer.offer(audioBuffer, 100, TimeUnit.MILLISECONDS)
    }

    fun release() {
        javaExecutor.execute {
            rawAudioBuffer.clear()
            _encodedData.clear()
            stopEncoder()
            codec.release()
            speedMeter.stop()
        }
    }

    fun mediaFormat() = codec.outputFormat
}


fun stringyFyMediaFormat(mediaFormat: MediaFormat): String {
    return try {
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        MediaFormat::class.declaredFunctions.find {
            it.name == "getMap"
        }?.apply { isAccessible = true }?.call(mediaFormat)?.let { mediaData ->
            gson.toJson(mediaData)
        }
            ?: "MediaFormat: $mediaFormat"
    } catch (ex: Exception) {
        ex.printStackTrace()
        "Error: ${ex.message}"
    }
}


class CoroutineExecutor {
    private val queue = Channel<suspend CoroutineScope.() -> Unit>(capacity = Channel.BUFFERED)

    @OptIn(DelicateCoroutinesApi::class)
    private val scope = CoroutineScope(newSingleThreadContext("CoroutineExecutor"))
    private val speedMeter = SpeedMeter("CoroutineExecutor")

    fun execute(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { queue.send(block) }
    }

    fun cancel() {
        scope.cancel()
    }

    init {
        scope.launch {
            for (task in queue) {
                if (isActive) {
                    task()
                    speedMeter.update()
                }
            }
        }
    }
}
