package desidev.videocall.service

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import desidev.videocall.service.camera.CameraLensFacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CallService<P : Any> {
    // state and listeners flow
    val isVoiceMuted: StateFlow<Boolean>
    val isCameraClosed: StateFlow<Boolean>
    val cameraFace: StateFlow<CameraLensFacing>
    val isCallActive: StateFlow<Boolean>

    val incomingCall: Flow<Offer<P>>

    val serviceState: StateFlow<State>

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
    fun call(callee: P)
    fun cancel()
    fun accept()

    fun dispose()

    sealed interface State {
        data object Idle : State // initial state
        data object Calling : State // outgoing call
        data object Ringing : State // incoming call
        data object InCall : State // call is active
        data object Disposed : State // service disposed
        data class Error(val message: String, val exception: Throwable) : State
    }
}


