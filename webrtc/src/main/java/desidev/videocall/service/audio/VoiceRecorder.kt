package desidev.videocall.service.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.util.Log
import desidev.videocall.service.SpeedMeter
import desidev.videocall.service.ext.asMicroSec
import desidev.videocall.service.ext.asMilliSec
import desidev.videocall.service.ext.times
import desidev.videocall.service.ext.toInt
import desidev.videocall.service.ext.toLong
import desidev.videocall.service.ext.toMicroSec
import desidev.videocall.service.ext.toMilliSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var ptsUs: Long = 0

    private val speedMeter = SpeedMeter("VoiceRecorder")

    private var audioBufferChannel = Channel<AudioBuffer>(capacity = Channel.BUFFERED)

    val chunkFlow: ReceiveChannel<AudioBuffer> get() = audioBufferChannel

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        audioSource,
        sampleRate,
        channelConfig,
        encoding,
        chunkSize
    )

    val format: AudioFormat get() = audioRecord.format

    fun start() {
        audioRecord.startRecording()
        startFlowingOutput()
    }

    fun stop() {
        audioRecord.stop()
        audioBufferChannel.close()
        audioBufferChannel = Channel(capacity = Channel.BUFFERED)
    }

    fun release() {
        if (audioRecord.state == AudioRecord.RECORDSTATE_RECORDING) {
            stop()
        }
        audioRecord.release()
        coroutineScope.cancel("VoiceRecorder is released")
        speedMeter.stop()
    }


    private fun startFlowingOutput() = coroutineScope.launch {
        // Jab tak recording chal rahi hai aur coroutine active hain tab tak buffer bhejte raho
        // (send buffer until recording and coroutine is active)
        while (isActive && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.let {
                val buffer = ByteArray(chunkSize)

                // chunkTimeLen ka 80% intzar kare, jabtak buffer fill ho rahi hain
                // (wait 80% of chunkTimeLenUs while the buffer is getting filled)
                delay((chunkTimeLenUs.asMicroSec.toMilliSec() * 0.8).toLong())
                val status = it.read(buffer, 0, buffer.size)

                if (status > 0) {
                    ptsUs += chunkTimeLenUs
                    audioBufferChannel.trySend(AudioBuffer(buffer, ptsUs, 0))
                    speedMeter.update()
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
        private var chunkLenInMs: Long = 20
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

