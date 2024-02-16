package desidev.videocall.service.video

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.util.Log
import desidev.videocall.service.audio.AudioBuffer

class VoiceRecorder : Observable<AudioBuffer> {
    private val TAG = VoiceRecorder::class.simpleName
    private val observers = mutableListOf<Observer<AudioBuffer>>()

    object Config {
        const val channelConfig = AudioFormat.CHANNEL_IN_MONO
        const val sampleRate = 24000
        const val bitrate = 36000
        const val encoding = AudioFormat.ENCODING_PCM_16BIT
        val channelCount = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_16BIT) 2 else 1
    }

    private var audioRecord: AudioRecord? = null
    private var bufferSize = AudioRecord.getMinBufferSize(
        Config.sampleRate, Config.channelConfig, Config.encoding
    )
    private var timePerSampleUs: Long =
        calculateAudioBufferTimeLenUs(Config.sampleRate, bufferSize, Config.bytesPerSample)

    private var ptsUs: Long = 0

    private val recordingThread = RecordingThread()

    @SuppressLint("MissingPermission")
    fun start() {
        audioRecord = AudioRecord(
            AudioSource.VOICE_COMMUNICATION,
            Config.sampleRate,
            Config.channelConfig,
            Config.encoding,
            bufferSize
        )
        audioRecord?.startRecording()

        recordingThread.start()
    }

    fun stop() {
        if (recordingThread.isRunning) {
            recordingThread.isRunning = false
            recordingThread.join()
            this.observers.clear()
            Log.d(TAG, "VoiceRecorder stopped!")
        }
    }

    private fun calculateAudioBufferTimeLenUs(
        sampleRate: Int, bufferSize: Int, bytesPerSample: Int
    ): Long {
        return (bufferSize / bytesPerSample / sampleRate.toFloat() * 1000_000).toLong()
    }

    override fun addObserver(observer: Observer<AudioBuffer>) {
        this.observers.add(observer)
    }

    override fun removeObserver(observer: Observer<AudioBuffer>) {
        this.observers.remove(observer)
    }


    inner class RecordingThread : Thread("VoiceRecorderThread") {
        @Volatile
        var isRunning = false
        override fun run() {
            isRunning = true
            while (isRunning) {
                audioRecord?.let {
                    val buffer = ByteArray(bufferSize)
                    val status = it.read(buffer, 0, buffer.size)
                    if (status > 0) {
                        observers.forEach { observer ->
                            observer.next(AudioBuffer(buffer, ptsUs, 0))
                        }
                        ptsUs += timePerSampleUs

                    } else {
                        val msg = when (status) {
                            AudioRecord.ERROR_INVALID_OPERATION -> "Invalid operation"
                            AudioRecord.ERROR_BAD_VALUE -> "Error bad value"
                            AudioRecord.ERROR_DEAD_OBJECT -> "Error dead object"
                            else -> "Unknown"
                        }

                        Log.d(TAG, "AudioRecorder: $msg")
                    }
                }
            }
            audioRecord?.stop()
        }
    }
}