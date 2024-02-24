package desidev.videocall.service.codec

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import desidev.videocall.service.mediasrc.ReceivingPort
import desidev.videocall.service.mediasrc.SendingPort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class VideoEncoder : Codec {
    companion object {
        private val TAG = VideoEncoder::class.simpleName
    }

    private var _state = Codec.State.UNINITIALIZED
        set(value) {
            Log.d(TAG, "State: [$field] -> [$value]")
            field = value
        }

    override val state: Codec.State
        get() = _state

    private val _stateLock = ReentrantLock()
    private lateinit var _codec: MediaCodec

    private lateinit var _outputFormatFuture: CompletableFuture<MediaFormat>
    private lateinit var _inputSurface: Surface
    private var _output = SendingPort<Pair<ByteArray, BufferInfo>>()

    override fun configure(format: MediaFormat) = _stateLock.withLock {
        when (_state) {
            Codec.State.UNINITIALIZED -> {
                _codec = MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                _codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                _inputSurface = _codec.createInputSurface()
                _codec.start()
                _state = Codec.State.STOPPED
                _outputFormatFuture = CompletableFuture()
            }

            Codec.State.STOPPED -> {
                _codec.reset()
                _codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                _inputSurface = _codec.createInputSurface()
                _codec.start()
                _outputFormatFuture = CompletableFuture()
            }

            else -> {
                throw IllegalStateException("Invalid state: $_state")
            }
        }
    }


    override fun mediaFormat(): Future<MediaFormat> {
        return _outputFormatFuture
    }

    override fun startEncoder() = _stateLock.withLock {
        if (_state == Codec.State.STOPPED) {
            _state = Codec.State.RUNNING
            _output.reopen()
            thread {
                while (isActive()) {
                    val info = BufferInfo()
                    val status = _codec.dequeueOutputBuffer(info, 0)
                    if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "videoEncoder: output format changed!")
                        _outputFormatFuture.complete(_codec.outputFormat)

                    } else if (status >= 0 && info.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                        val buffer = _codec.getOutputBuffer(status)!!
                        buffer.position(info.offset)
                        buffer.limit(info.size)

                        val array = ByteArray(info.size)
                        buffer.get(array)

                        _output.send(array to info)
                        _codec.releaseOutputBuffer(status, false)
                    }
                }
            }.start()
        } else {
            throw IllegalStateException("Invalid state: $_state")
        }
    }

    private fun isActive(): Boolean = _state == Codec.State.RUNNING


    override fun stopEncoder() = _stateLock.withLock {
        if (_state == Codec.State.RUNNING) {
            _state = Codec.State.STOPPED
            _output.close()
        } else {
            throw IllegalStateException("Invalid state: $_state")
        }
    }

    override fun release() = _stateLock.withLock {
        if (_state == Codec.State.STOPPED) {
            _codec.stop()
            _codec.release()
            _state = Codec.State.UNINITIALIZED
            _output.close()
        } else {
            throw IllegalStateException("Invalid state: $_state")
        }
    }

    fun getInputSurface(): Surface {
        if (_state == Codec.State.UNINITIALIZED) {
            throw IllegalStateException("Encoder is not initialized yet. Call configure method first.")
        }
        return _inputSurface
    }

    fun getCompressedDataStream(): ReceivingPort<Pair<ByteArray, BufferInfo>> = _output
}