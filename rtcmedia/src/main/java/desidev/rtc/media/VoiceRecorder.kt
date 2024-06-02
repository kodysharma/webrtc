package desidev.rtc.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.util.Log
import desidev.rtc.media.codec.createAudioMediaFormat
import desidev.utility.SpeedMeter
import desidev.utility.asMicroSec
import desidev.utility.asMilliSec
import desidev.utility.times
import desidev.utility.toInt
import desidev.utility.toLong
import desidev.utility.toMicroSec
import desidev.utility.toMilliSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class VoiceRecorder private constructor(
    channelConfig: Int,
    sampleRate: Int,
    audioSource: Int,
    encoding: Int,
    val chunkSize: Int,
    val chunkTimeLenUs: Int,
) {
    private val TAG = VoiceRecorder::class.simpleName
    private val _scope = CoroutineScope(Dispatchers.IO)
    private var ptsUs: Long = 0

    private val speedMeter = SpeedMeter("VoiceRecorder")

    private var sendingChannel: Channel<AudioBuffer>? = null
    private var encoder: AudioEncoder? = null

    private val outChannel: ReceiveChannel<AudioBuffer>
        get() {
            if (sendingChannel == null) {
                sendingChannel = Channel()
            }
            return sendingChannel!!
        }

    @SuppressLint("MissingPermission")
    private val _audioRecord = AudioRecord(
        audioSource,
        sampleRate,
        channelConfig,
        encoding,
        chunkSize
    )

    val audioFormat: AudioFormat get() = _audioRecord.format

    suspend fun start() {
        _audioRecord.startRecording()
        startFlowingOutput()
        encoder = AudioEncoder(outChannel).apply {
            configure(createAudioMediaFormat(audioFormat))
            start()
        }
    }

    suspend fun stop() {
        encoder?.stop()
        encoder = null
        _audioRecord.stop()
        sendingChannel?.close()
        sendingChannel = null
    }

    suspend fun release() {
        if (_audioRecord.state == AudioRecord.RECORDSTATE_RECORDING) {
            stop()
        }
        _audioRecord.release()
        _scope.cancel("VoiceRecorder is released")
    }


    fun getCompressChannel(): ReceiveChannel<AudioBuffer> {
        return encoder?.outputChannel ?: throw IllegalStateException("VoiceRecorder is not started")
    }

    fun getCompressFormat() = encoder?.outputFormat ?: throw IllegalStateException("VoiceRecorder is not started")

    @OptIn(DelicateCoroutinesApi::class)
    private fun startFlowingOutput() = _scope.launch {
        // Jab tak recording chal rahi hai aur coroutine active hain tab tak buffer bhejte raho
        while (isActive && _audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            _audioRecord.let {
                val buffer = ByteArray(chunkSize)

                // chunkTimeLen ka 80% intzar kare, jabtak buffer fill ho rahi hain
                delay((chunkTimeLenUs.asMicroSec.toMilliSec() * 0.8).toLong())
                val status = it.read(buffer, 0, buffer.size)

                if (status > 0) {
                    ptsUs += chunkTimeLenUs
                    try {
                        sendingChannel?.send(AudioBuffer(buffer, ptsUs, 0))
                        speedMeter.update()
                    } catch (e: ClosedSendChannelException) {
                        // ignore
                    }
                } else {
                    Log.d(TAG, "AudioRecorder: ${getErrorMessage(status)}")
                }
            }
        }
    }

    private fun getErrorMessage(status: Int): String {
        return when (status) {
            AudioRecord.ERROR_INVALID_OPERATION -> "Invalid operation"
            AudioRecord.ERROR_BAD_VALUE -> "Error bad value"
            AudioRecord.ERROR_DEAD_OBJECT -> "Error dead object"
            else -> "Unknown"
        }
    }

    class Builder {
        private var sampleRate: Int = 24000
        private var channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
        private var audioSource: Int = AudioSource.VOICE_COMMUNICATION
        private var encoding: Int = AudioFormat.ENCODING_PCM_16BIT
        private var chunkLenInMs: Long = 15
        private var chunkSize: Int = -1
        private var audioRecordInternalBufferSize = -1

        private lateinit var audioRecord: AudioRecord

        fun setSampleRate(sampleRate: Int): Builder {
            this.sampleRate = sampleRate
            return this
        }

        fun setChannelConfig(channelConfig: Int): Builder {
            this.channelConfig = channelConfig
            return this
        }

        fun setAudioSource(audioSource: Int): Builder {
            this.audioSource = audioSource
            return this
        }

        fun setEncoding(encoding: Int): Builder {
            this.encoding = encoding
            return this
        }

        fun setChunkLenInMs(chunkLenInMs: Long): Builder {
            this.chunkLenInMs = chunkLenInMs
            return this
        }

        @SuppressLint("MissingPermission")
        fun build(): VoiceRecorder {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate, channelConfig, encoding
            )

            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_16BIT) 2 else 1

            val noOfSampleInChunk = (chunkLenInMs * sampleRate / 1000f).roundToInt()
            chunkSize = noOfSampleInChunk * bytesPerSample
            audioRecordInternalBufferSize = chunkSize * 2

            audioRecordInternalBufferSize =
                audioRecordInternalBufferSize.coerceAtLeast(minBufferSize)

            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                encoding,
                audioRecordInternalBufferSize
            )

            return VoiceRecorder(
                channelConfig,
                sampleRate,
                audioSource,
                encoding,
                chunkSize,
                chunkLenInMs.asMilliSec.toMicroSec().toInt()
            )
        }
    }
}

