package desidev.videocall.service.codec

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Default audio encoder implementation. This class uses
 * [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) to encode the raw audio buffer.
 * The encoded data will be available in [output] channel.
 */
class AudioEncoder : Codec<ReceivingPort<AudioBuffer>, ReceivingPort<AudioBuffer>> {
    companion object {
        private val TAG = AudioEncoder::class.simpleName
    }

    private var _state: Codec.State = Codec.State.UNINITIALIZED
        set(value) {
            if (_state == value) return
            Log.d(TAG, "State update: [$field] --> [$value]")
            field = value
        }

    override val state: Codec.State
        get() = _state

    private lateinit var _codec: MediaCodec
    private lateinit var _outputFormatFuture: CompletableFuture<MediaFormat>
    private val stateLock = ReentrantLock()
    private lateinit var _inputFormat: MediaFormat
    private var _inPort: ReceivingPort<AudioBuffer>? = null

    override fun configure(audioFormat: AudioFormat) {
        stateLock.withLock {
            // initialize the encoder if not initialized
            if (_state == Codec.State.UNINITIALIZED) {
                _inputFormat = audioFormat.toMediaFormat()
                _codec = MediaCodec.createEncoderByType(_inputFormat.getString(MediaFormat.KEY_MIME)!!)

                _codec.configure(_inputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                _codec.start()
                _outputFormatFuture = CompletableFuture()

                _state = Codec.State.STOPPED
            }

            // configure and start the encoder that Makes this MediaCodec to goes in Executing state
            // as described in https://developer.android.com/reference/android/media/MediaCodec#configure(android.media.MediaFormat,%20android.view.Surface,%20android.media.MediaCrypto,%20int)

            else if (_state == Codec.State.STOPPED) {
                _codec.reset()
                _codec.configure(_inputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                _codec.start()
                _outputFormatFuture = CompletableFuture()
            }
        }
    }

    private val _outPort = SendingPort<AudioBuffer>()

    override val outPort: ReceivingPort<AudioBuffer> = _outPort

    override fun startEncoder() {
        stateLock.withLock {
            if (_state == Codec.State.STOPPED) {
                _outPort.apply {
                    if (!isOpenForSend) reopen()
                }
                _state = Codec.State.RUNNING

                Thread {
                    while (isActive()) {
                        val index = _codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            var audioBuffer: AudioBuffer? = _inPort?.tryReceive()

                            while (audioBuffer == null && isActive()) {
                                Thread.sleep(100)
                                audioBuffer = _inPort?.tryReceive()
                                stateLock.withLock {
                                    if (isActive()) {
                                        _state = if(audioBuffer == null) Codec.State.IDLE else Codec.State.RUNNING
                                    }
                                }
                            }

                            if (!isActive()) {
                                break
                            }

                            if (audioBuffer != null) {
                                val inputBuffer = _codec.getInputBuffer(index)!!
                                inputBuffer.put(audioBuffer.buffer)
                                _codec.queueInputBuffer(
                                    index,
                                    0,
                                    audioBuffer.buffer.size,
                                    audioBuffer.ptsUs,
                                    audioBuffer.flags
                                )
                            }
                        }

                        val info = MediaCodec.BufferInfo()
                        val outIndex = _codec.dequeueOutputBuffer(info, 10000)
                        if (outIndex >= 0) {
                            val outputBuffer = _codec.getOutputBuffer(outIndex)
                            val audioBuffer = AudioBuffer(
                                ByteArray(info.size),
                                info.presentationTimeUs,
                                info.flags
                            )
                            outputBuffer?.get(audioBuffer.buffer)

                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                _outPort.close()
                            }

                            if (_outPort.isOpenForSend) {
                                _outPort.send(audioBuffer)
                            }

                            _codec.releaseOutputBuffer(outIndex, false)
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            _outputFormatFuture.complete(_codec.outputFormat)
                            Log.d(TAG, "OutputFormat changed: ${stringyFyMediaFormat(_codec.outputFormat)} ")
                        }
                    }
                }.start()
            }
        }
    }

    override fun stopEncoder() {
        stateLock.withLock {
            if (isActive()) {
                _codec.flush()
                _outPort.close()
                _state = Codec.State.STOPPED
            }
        }
    }


    private fun isActive() =
        stateLock.withLock { _state == Codec.State.RUNNING || _state == Codec.State.IDLE }

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

    override fun setInPort(inPort: ReceivingPort<AudioBuffer>) {
        _inPort = inPort
    }

    override fun release() {
        stateLock.withLock {
            if (_state == Codec.State.RUNNING) {
                stopEncoder()
            }
            _codec.release()
            _state = Codec.State.RELEASED
            if (!_outputFormatFuture.isDone) _outputFormatFuture.cancel(true)
        }
    }

    override fun mediaFormat(): Future<MediaFormat> {
        if (_state != Codec.State.RUNNING) {
            throw IllegalStateException("Encoder is not in RUNNING state")
        }
        return _outputFormatFuture
    }
}


