package desidev.rtc.media.player

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaFormat
import android.util.Log
import desidev.rtc.media.AudioBuffer
import desidev.rtc.media.codec.audioDecoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume


/**
 * Onetime use audio player. Don't use it after stop.
 */
class AudioPlayer(
    private val format: MediaFormat,
) : CoroutineScope {
    private val TAG = AudioPlayer::class.simpleName

    override val coroutineContext: CoroutineContext = Dispatchers.Default + SupervisorJob()
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    private val coroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable -> throwable.printStackTrace() }

    private val audioSamplesFlow = Channel<AudioBuffer>(Channel.CONFLATED)

    /**
     * As the inputformat becomes available. It will start audio Track.
     * If the input format changed or next format comes. The previous audio track will stopped and a new one will start.
     */
    fun startPlayback() {
        playbackJob = processInputSamples(format, audioSamplesFlow.consumeAsFlow())
    }

    fun stop() {
        audioSamplesFlow.close()
        playbackJob?.cancel()
        playbackJob = null
    }

    fun queueAudioBuffer(audioBuffer: AudioBuffer) {
        audioSamplesFlow.trySend(audioBuffer)
    }

    private fun processInputSamples(
        inputFormat: MediaFormat,
        inputSamples: Flow<AudioBuffer>
    ): Job {
        return launch(coroutineExceptionHandler) {
            Log.d(TAG, "startPlaybackJob: input format -> $inputFormat")
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            var trackInputFormat = inputFormat
            var trackInputSamples: Flow<AudioBuffer> = inputSamples

            if (mime !=  MediaFormat.MIMETYPE_AUDIO_RAW) {
                suspendCancellableCoroutine { cont ->
                    trackInputSamples = audioDecoding(inputSamples, inputFormat) {
                        cont.resume(Unit)
                        trackInputFormat = it
                    }.consumeAsFlow()
                }
            }

            val sampleRate = trackInputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelConfig = if (trackInputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val trackBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            Log.d(TAG, "audio track buffer size = $trackBufferSize")

            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                trackBufferSize,
                AudioTrack.MODE_STREAM
            )

            audioTrack!!.play()

            try {
                trackInputSamples.collect {
                    audioTrack?.write(it.buffer, 0, it.buffer.size)
                }
            } catch (ex: CancellationException) {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                Log.d(TAG, "startPlaybackJob: AudioTrack is released!")
            }
        }
    }
}