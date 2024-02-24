package desidev.videocall.service

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import desidev.videocall.service.camera.CameraLensFacing
import kotlinx.coroutines.flow.StateFlow

interface VideoCallService {
    val isAudioMuted: StateFlow<Boolean>
    val isVideoMuted: StateFlow<Boolean>
    val cameraFace: StateFlow<CameraLensFacing>

    @Composable
    fun ViewContent(modifier: Modifier)

    @Composable
    fun PreviewContent(modifier: Modifier)

    fun switchCamera(camera: CameraLensFacing)
    fun muteAudio()
    fun muteVideo()
    fun unMuteAudio()
    fun unMuteVideo()

    /**
     * Add a listener to be notified when a call offer is received
     * Only one listener can be added at a time.
     *
     * @param callback the callback to be called when a call offer is received
     */
    fun setRingRingListener(callback: RingRingListener)

    /**
     * Add a listener to be notified when a call offer is accepted or rejected
     * Only one listener can be added at a time.
     */
    fun setCallOfferResultListener(callback: CallOfferResultListener)

    /**
     * Call the other party with the given offer,
     * @param offer the call offer
     */
    fun call(offer: CallOffer)


    /**
     * Hang up the call if there is an ongoing call
     */
    fun hangUp()


    /**
     * Companion object to create a new instance of [VideoCallService],
     * Implementations should provide a factory method to create a new instance of [VideoCallService]
     * by defining a extension function on this companion object
     */
    companion object
}


