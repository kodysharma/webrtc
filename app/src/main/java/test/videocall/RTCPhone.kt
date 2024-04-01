package test.videocall

import android.content.Context
import android.media.MediaCodec.BufferInfo
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import desidev.rtc.media.Actor
import desidev.rtc.media.camera.CameraCaptureImpl
import desidev.rtc.media.player.VideoPlayer
import desidev.turnclient.ICECandidate
import desidev.utility.yuv.YuvToRgbConverter
import desidev.videocall.service.rtcclient.RTC
import desidev.videocall.service.rtcclient.TrackListener
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Sample
import desidev.videocall.service.rtcmsg.toMediaFormat
import desidev.videocall.service.rtcmsg.toRTCFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import test.videocall.signalclient.AnswerEvent
import test.videocall.signalclient.OfferCancelledEvent
import test.videocall.signalclient.OfferEvent
import test.videocall.signalclient.Peer
import test.videocall.signalclient.PostAnswerParams
import test.videocall.signalclient.PostOfferParams
import test.videocall.signalclient.RPCConnectionEvent
import test.videocall.signalclient.SessionClosedEvent
import test.videocall.signalclient.SignalClient
import java.io.IOException


class RTCPhone(context: Context) : Actor<RTCPhoneAction>(Dispatchers.Default) {
    companion object {
        const val URL = "ws://139.59.85.69:8080"
        val TAG: String = RTCPhone::class.java.simpleName
    }

    // Internal Events handled by the RTCPhone
    private data class OnCallReceived(val peer: Peer, val candidates: List<ICECandidate>) :
        RTCPhoneAction

    private data class OnCallAccepted(val peer: Peer, val candidates: List<ICECandidate>) :
        RTCPhoneAction

    private data class OnCallRejected(val peer: Peer) : RTCPhoneAction
    private data object OnCallEnded : RTCPhoneAction


    sealed class CallState {
        data object NoSession : CallState()
        data class CallingToPeer(val receiver: Peer) : CallState()
        data class IncomingCall(val sender: Peer) : CallState()
        data class InSession(val peer: Peer) : CallState()
    }

    enum class ConnectionState { DisConnected, Connected }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rtc = RTC()
    private val signalClient = SignalClient()
    private val cameraCapture = CameraCaptureImpl(context)

    private val mutCallStateFlow = MutableStateFlow<CallState>(CallState.NoSession)
    private val mutCameraStateFlow = MutableStateFlow(false)
    private val mutErrorSharedFlow = MutableSharedFlow<RtcPhoneException>()
    private val mutConnectionStateFlow = MutableStateFlow(ConnectionState.DisConnected)

    val errorSharedFlow: SharedFlow<RtcPhoneException> = mutErrorSharedFlow.asSharedFlow()
    val callStateFlow: StateFlow<CallState> = mutCallStateFlow
    val cameraStateFlow: StateFlow<Boolean> = mutCameraStateFlow
    val connectionStateFlow: StateFlow<ConnectionState> = mutConnectionStateFlow

    private var realTimeDataJob: Job? = null

    private val remoteVideoPlayerState = MutableStateFlow<VideoPlayer?>(null)
    private val yuvToRgbConverter = YuvToRgbConverter(context)

    private val rtcTrackListener = object : TrackListener {
        override fun onVideoStreamAvailable(videoFormat: RTCMessage.Format) {
            Log.i(TAG, "onVideoStreamAvailable: $videoFormat")
            remoteVideoPlayerState.value =
                VideoPlayer(yuvToRgbConverter, videoFormat.toMediaFormat()).also { it.play() }
        }

        override fun onAudioStreamAvailable(audioFormat: RTCMessage.Format) {
        }

        override fun onNextVideoSample(videoSample: Sample) {
            remoteVideoPlayerState.value?.let { player ->
                val info = BufferInfo().apply {
                    presentationTimeUs = videoSample.ptsUs
                    flags = videoSample.flags
                    size = videoSample.buffer.size
                }
                actorScope.launch {
                    player.inputData(
                        videoSample.buffer,
                        info
                    )
                }
            }
        }

        override fun onNextAudioSample(audioSample: Sample) {
        }

        override fun onVideoStreamDisable() {
            scope.launch {
                remoteVideoPlayerState.value?.let {
                    remoteVideoPlayerState.value = null
                }
            }
        }

        override fun onAudioStreamDisable() {
        }
    }

    init {
        rtc.setTrackListener(rtcTrackListener)
        scope.launch {
            signalClient.connectionStateFlow.collect { state ->
                Log.d(TAG, "connectionStateFlow: $state")
                val connState = when (state) {
                    is RPCConnectionEvent.OnConnect -> ConnectionState.Connected
                    is RPCConnectionEvent.OnDisconnect -> ConnectionState.DisConnected
                }
                mutConnectionStateFlow.emit(connState)
            }
        }
        subscribeSignalEvents()
    }


    suspend fun getPeers(): List<Peer> {
        return signalClient.getPeers()
    }


    suspend fun enableCamera() {
        cameraCapture.start()
        this.mutCameraStateFlow.value = true
    }

    suspend fun disableCamera() {
        cameraCapture.stop()
        this.mutCameraStateFlow.value = false
    }


    private fun createSendingRealTimeDataJob() {
        this.realTimeDataJob = actorScope.launch {
            launch {
                mutCameraStateFlow.collect { isCameraEnable ->
                    if (isCameraEnable) {
                        addVideoSource()
                    }
                }
            }
        }
    }

    private fun stopSendingRealTimeData() {
        this.realTimeDataJob?.cancel()
        this.realTimeDataJob = null
    }

    private suspend fun addVideoSource() {
        val format = cameraCapture.getMediaFormat().await().toRTCFormat()
        val samplesFlow = cameraCapture.compressChannel().receiveAsFlow().map {
            val (data, info) = it
            Sample(buffer = data, ptsUs = info.presentationTimeUs, flags = info.flags)
        }
        rtc.addVideoSource(format, samplesFlow)
    }

    @Composable
    fun RemotePeerView(modifier: Modifier = Modifier) {
        val remoteVideoPlayer by this.remoteVideoPlayerState.collectAsState()
        remoteVideoPlayer?.VideoPlayerView(modifier)
    }

    @Composable
    fun LocalPeerView(modifier: Modifier = Modifier) {
        val currentPreviewFrame = remember { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(Unit) {
            cameraCapture.setPreviewFrameListener { image ->
                currentPreviewFrame.value = image.asImageBitmap()
            }
        }

        if (currentPreviewFrame.value != null) {
            Image(
                bitmap = currentPreviewFrame.value!!,
                contentDescription = "Camera frame",
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        }
    }

    override suspend fun onNextAction(action: RTCPhoneAction) {
        when (action) {
            is RTCPhoneAction.GoOnline -> {
                val connectionState = connectionStateFlow.value
                if (connectionState == ConnectionState.DisConnected) {
                    try {
                        signalClient.connect(URL, action.localPeerName)
                        mutConnectionStateFlow.value = ConnectionState.Connected
                        action.onSuccess()
                    } catch (ex: IOException) {
                        Log.e(TAG, "Failed to connect to signal server", ex)
                        action.onFailure(ex)
                    }
                }
            }

            is RTCPhoneAction.MakeCall -> {
                if (callStateFlow.value is CallState.NoSession) {
                    try {
                        rtc.createLocalIce()
                        signalClient.postOffer(
                            PostOfferParams(
                                receiverId = action.remotePeer.id,
                                candidates = rtc.getLocalIce(),
                            )
                        )
                        mutCallStateFlow.value = CallState.CallingToPeer(action.remotePeer)
                        action.onSuccess()
                    } catch (ex: IOException) {
                        action.onFailure(ex)
                    }
                }
            }

            is RTCPhoneAction.PickUpCall -> {
                val callState = callStateFlow.value
                if (callState is CallState.IncomingCall) {
                    val caller = callState.sender
                    try {
                        rtc.createLocalIce()
                        signalClient.postAnswer(
                            PostAnswerParams(
                                receiverId = caller.id,
                                candidates = rtc.getLocalIce(),
                                accepted = true
                            )
                        )
                        rtc.createPeerConnection()
                        createSendingRealTimeDataJob()
                        mutCallStateFlow.value = CallState.InSession(caller)
                    } catch (ex: IOException) {
                        Log.e(TAG, "Failed to create peer connection", ex)
                    }
                }
            }

            is RTCPhoneAction.EndCall -> {
                val callState = callStateFlow.value
                if (callState is CallState.InSession) {
                    try {
                        signalClient.postCloseSession()
                        stopSendingRealTimeData()
                        rtc.apply {
                            closePeerConnection()
                            clearRemoteIce()
                        }
                        mutCallStateFlow.value = CallState.NoSession
                    } catch (ex: IOException) {
                        Log.e(TAG, "Failed to close peer connection", ex)
                    }
                } else if (callState is CallState.CallingToPeer) {
                    try {
                        rtc.closePeerConnection()
                        signalClient.cancelRecentOffer()
                        mutCallStateFlow.value = CallState.NoSession
                    } catch (ex: IOException) {
                        Log.e(TAG, "Failed to close peer connection", ex)
                    }
                }
            }

            is OnCallAccepted -> {
                val callState = callStateFlow.value
                if (callState is CallState.CallingToPeer) {
                    try {
                        rtc.apply {
                            addRemoteIce(action.candidates)
                            createPeerConnection()
                        }
                        createSendingRealTimeDataJob()
                        mutCallStateFlow.value = CallState.InSession(callState.receiver)
                    } catch (ex: IOException) {
                        Log.e(TAG, "Failed to create peer connection", ex)
                    }
                }
            }

            is OnCallReceived -> {
                val callState = callStateFlow.value
                if (callState is CallState.NoSession) {
                    rtc.addRemoteIce(action.candidates)
                    mutCallStateFlow.value = CallState.IncomingCall(action.peer)
                }
            }

            is OnCallRejected -> {
                val callState = callStateFlow.value
                if (callState is CallState.CallingToPeer) {
                    rtc.apply {
                        closePeerConnection()
                        clearRemoteIce()
                    }
                    mutCallStateFlow.value = CallState.NoSession
                }
            }

            is OnCallEnded -> {
                val callState = callStateFlow.value
                if (callState is CallState.InSession) {
                    stopSendingRealTimeData()
                    rtc.apply {
                        closePeerConnection()
                        clearRemoteIce()
                    }
                    mutCallStateFlow.value = CallState.NoSession
                } else if (callState is CallState.IncomingCall) {
                    mutCallStateFlow.value = CallState.NoSession
                }
            }
        }
    }


    private fun subscribeSignalEvents() {
        actorScope.launch {
            signalClient.eventFlow.collect { sigEvent ->
                Log.i(TAG, "Next SignalEvent: $sigEvent")
                when (sigEvent) {
                    is OfferEvent -> {
                        send(
                            OnCallReceived(
                                sigEvent.sender,
                                sigEvent.candidates
                            )
                        )
                    }

                    is AnswerEvent -> {
                        if (sigEvent.accepted) {
                            send(
                                OnCallAccepted(
                                    sigEvent.sender,
                                    sigEvent.candidates!!
                                )
                            )
                        } else {
                            send(
                                OnCallRejected(
                                    sigEvent.sender
                                )
                            )
                        }
                    }

                    is SessionClosedEvent -> {
                        send(OnCallEnded)
                    }

                    is OfferCancelledEvent -> {
                        send(OnCallEnded)
                    }
                }
            }
        }
    }
}

sealed interface RTCPhoneAction {

    data class GoOnline(
        val localPeerName: String, val onSuccess: () -> Unit, val onFailure: (ex: Exception) -> Unit
    ) : RTCPhoneAction

    data class MakeCall(
        val remotePeer: Peer, val onSuccess: () -> Unit, val onFailure: (ex: Exception) -> Unit
    ) : RTCPhoneAction

    data object PickUpCall : RTCPhoneAction
    data object EndCall : RTCPhoneAction
}

sealed class RtcPhoneException(
    override val message: String?, override val cause: Throwable?
) : Exception() {
    class NetworkError(message: String?, cause: Throwable?) : RtcPhoneException(message, cause)
    class SignalServerError(message: String?, cause: Throwable?) : RtcPhoneException(message, cause)
    class CallError(message: String?, cause: Throwable?) : RtcPhoneException(message, cause)
    class PeerConnectionError(message: String?, cause: Throwable?) : RtcPhoneException(message, cause)
}
