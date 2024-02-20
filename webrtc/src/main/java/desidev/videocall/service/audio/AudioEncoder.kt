package desidev.videocall.service.audio

import android.media.AudioFormat
import android.media.MediaFormat
import java.util.concurrent.Future


interface AudioEncoder<Sink : Any> {
    val state: EncoderState

    /**
     * Implementation can decide how to provide the data.
     * Output Sink can be a channel, queue, or any other data structure.
     */
    val encodedData: Sink

    /**
     * Configure the encoder with the given [audioFormat]
     * Audio format should contain the sample rate, channel count and encoding.
     * The encoder will be in [ConfigState.CONFIGURED] state after configuring.
     */
    fun configure(audioFormat: AudioFormat)


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
     * Get the output media format of the encoder. after starting the encoder, the media format will be available.
     * The media format will be available in the future. You can use [Future.get] method to get the media format.
     * Don't call this method before providing the input. The media format will be available after providing the input.
     */
    fun mediaFormat(): Future<MediaFormat>

    sealed interface ConfigState {
        data object UNCONFIG : ConfigState
        data object CONFIGURED : ConfigState
    }

    sealed interface EncoderState {
        data class STOPPED(val configState: ConfigState) : EncoderState
        data object RUNNING : EncoderState
        data object RELEASED : EncoderState
    }

    companion object
}




