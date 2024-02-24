package desidev.videocall.service.codec

import android.media.MediaFormat
import desidev.videocall.service.codec.Codec.State
import java.util.concurrent.Future


interface InputScheme<in I : Any> {
    fun provideInput(input: I)
}

interface OutputScheme<out O : Any> {
    fun output(): O
}


/**
 * Codec interface to encode/decode the media data.
 * The codec can be in one of the following states:
 * [State.UNINITIALIZED], [State.STOPPED], [State.RUNNING], [State.IDLE], [State.RELEASED]
 */
interface Codec {
    val state: State

    /**
     * Configure the encoder with the given [format]
     * Audio format should contain the sample rate, channel count and encoding.
     * The encoder will be in [State.STOPPED] state after configuring.
     */
    fun configure(format: MediaFormat)

    /**
     * Start the encoder. Before starting the encoder, it should be configured with [configure] method.
     * The encoder will start encoding the raw audio buffer and the encoded data will be provided by the implementing class.
     * The encoder will be in [State.RUNNING] state after starting.
     * The encoder can be stopped by calling [stopEncoder] method.
     */
    fun startEncoder()


    /**
     * Stop the encoder. The encoder will be in [State.STOPPED] state after stopping.
     * The encoder can be started again by calling [startEncoder] method.
     */
    fun stopEncoder()

    /**
     * Release the encoder. The encoder will be in [State.RELEASED] state after releasing.
     * The encoder can not be used after releasing.
     */
    fun release()


    /**
     * Get the output media format of the encoder. after starting the encoder, the media format will be available.
     * The media format will be available in the future. You can use [Future.get] method to get the media format.
     * Don't call this method before providing the input. The media format will be available after providing the input.
     */
    fun mediaFormat(): Future<MediaFormat>

    enum class State {
        UNINITIALIZED,
        STOPPED,
        RUNNING,
        IDLE,
        RELEASED
    }

    companion object {
        fun createAudioEncoder(): AudioEncoder {
            return AudioEncoder()
        }

        fun createVideoEncoder(): VideoEncoder {
            return VideoEncoder()
        }
    }
}




