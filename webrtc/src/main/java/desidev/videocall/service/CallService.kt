package desidev.videocall.service

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import desidev.videocall.service.camera.CameraLensFacing
import desidev.videocall.service.signal.Signal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface VideoCallService<P : Any> {
    // state and listeners flow
    val isVoiceMuted: StateFlow<Boolean>
    val isCameraClosed: StateFlow<Boolean>
    val cameraFace: StateFlow<CameraLensFacing>
    val isCallActive: StateFlow<Boolean>

    val incomingCall: Flow<IncomingCall<P>>

    val state: StateFlow<State>

    // view content
    @Composable
    fun PeerViewContent(modifier: Modifier)

    @Composable
    fun SelfPreviewContent(modifier: Modifier)

    // voice control
    fun muteVoice()
    fun unMuteVoice()

    // video control
    fun switchCamera()
    suspend fun openCamera()
    suspend fun closeCamera()

    suspend fun call(callee: P): Result<Boolean>
    suspend fun endCall()

    suspend fun answerToCall(accept: Boolean, call: IncomingCall<P>)

    fun dispose()

    sealed interface State {
        object Idle : State // initial state
        object Calling : State // outgoing call
        object Ringing : State // incoming call
        object InCall : State // call is active
        object Ended : State // call ended
        object Disposed : State // service disposed
    }


    /**
     * Companion object to create a new instance of [VideoCallService],
     * Implementations should provide a factory method to create a new instance of [VideoCallService]
     * by defining a extension function on this companion object
     */
    companion object {
        fun <P : Any> create(signal: Signal<P>): VideoCallService<P> {
            throw NotImplementedError("This method should be implemented by the companion object of the implementation")
        }
    }
}


