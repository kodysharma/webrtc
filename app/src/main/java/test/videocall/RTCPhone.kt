package test.videocall

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
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
import androidx.compose.ui.platform.LocalContext
import desidev.rtc.media.camera.CameraCaptureImpl
import desidev.rtc.media.player.VideoPlayer
import desidev.turnclient.ICECandidate
import desidev.utility.yuv.YuvToRgbConverter
import desidev.videocall.service.rtcclient.DefaultRtcClient
import desidev.videocall.service.rtcclient.TrackListener
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Sample
import desidev.videocall.service.rtcmsg.toMediaFormat
import desidev.videocall.service.rtcmsg.toRTCFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.RtcPhoneException.SignalServerError
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


class RTCPhone(context: Context) {
    companion object {
        const val URL = "ws://139.59.85.69:8080"
        val TAG: String = RTCPhone::class.java.simpleName
    }

    sealed class CallState {
        data object NoSession : CallState()
        data class CallingToPeer(val receiver: Peer) : CallState()
        data class IncomingCall(val sender: Peer) : CallState()
        data class InSession(val peer: Peer) : CallState()
    }

    enum class ConnectionState { DisConnected, Connected }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rtc = DefaultRtcClient("64.23.160.217", 3478, "test", "test123")
    private val signalClient = SignalClient()
    private val cameraCapture = CameraCaptureImpl(context)

    private val mutCallStateFlow = MutableStateFlow<CallState>(CallState.NoSession)
    private val mutCameraStateFlow = MutableStateFlow(false)
    private val mutErrorSharedFlow = MutableSharedFlow<RtcPhoneException>()
    private val mutConnectionStateFlow =
        MutableStateFlow<ConnectionState>(ConnectionState.DisConnected)

    val errorSharedFlow: SharedFlow<RtcPhoneException> = mutErrorSharedFlow.asSharedFlow()
    val callStateFlow: StateFlow<CallState> = mutCallStateFlow
    val cameraStateFlow: StateFlow<Boolean> = mutCameraStateFlow
    val connectionStateFlow: StateFlow<ConnectionState> = mutConnectionStateFlow

    private var realTimeDataJob: Job? = null
    private val eventHandler = RTCPhoneEventHandler()

    private val remoteVideoPlayerState = MutableStateFlow<VideoPlayer?>(null)

    private val rtcTrackListener = object : TrackListener {
        override fun onVideoStreamAvailable(videoFormat: RTCMessage.Format) {
            Log.d(TAG, "onVideoStreamAvailable: $videoFormat")
            remoteVideoPlayerState.value = VideoPlayer(videoFormat.toMediaFormat()).also { it.play() }
        }

        override fun onAudioStreamAvailable(audioFormat: RTCMessage.Format) {
        }

        override fun onNextVideoSample(videoSample: Sample) {
            remoteVideoPlayerState.value?.let {
                it.inputData(buffer = videoSample.buffer,
                    info = BufferInfo().apply {
                        presentationTimeUs = videoSample.ptsUs
                        flags = videoSample.flags
                        size = videoSample.buffer.size
                        offset = 0
                    }
                )
            }
        }

        override fun onNextAudioSample(audioSample: Sample) {
        }

        override fun onVideoStreamDisable() {
            remoteVideoPlayerState.value?.let {
                it.stop()
                remoteVideoPlayerState.value = null
            }
        }

        override fun onAudioStreamDisable() {
        }
    }

    init {
        rtc.setTrackListener(rtcTrackListener)
        eventHandler.start()
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
    }

    fun goOnline(peerName: String, onSuccess: () -> Unit = {}, onFailure: (ex: Exception) -> Unit) {
        eventHandler.put(RTCPhoneEvent.GoOnline(peerName, onSuccess, onFailure))
    }

    suspend fun getPeers(): List<Peer> {
        return signalClient.getPeers()
    }

    private fun subscribeToIncomingEvents() {
        scope.launch {
            signalClient.eventFlow.collect { sigEvent ->
                Log.i(TAG, "Next SignalEvent: $sigEvent")
                when (sigEvent) {
                    is OfferEvent -> {
                        eventHandler.put(
                            RTCPhoneEvent.OnCallReceived(
                                sigEvent.sender,
                                sigEvent.candidates
                            )
                        )
                    }

                    is AnswerEvent -> {
                        eventHandler.put(
                            RTCPhoneEvent.OnRemotePeerAcceptCall(
                                sigEvent.sender, sigEvent.candidates!!
                            )
                        )
                    }

                    is SessionClosedEvent -> {
                        eventHandler.put(RTCPhoneEvent.OnRemotePeerClosedSession)
                    }

                    is OfferCancelledEvent -> {
                        eventHandler.put(RTCPhoneEvent.OnIncomingCallCanceled)
                    }
                }
            }
        }
    }

    fun makeCall(peer: Peer, onSuccess: () -> Unit, onFailure: (ex: Exception) -> Unit) {
        eventHandler.put(RTCPhoneEvent.MakeCallEvent(peer, onSuccess, onFailure))
    }

    fun endCall(onSuccess: (() -> Unit)? = null, onFailure: ((ex: Exception) -> Unit)? = null) {
        eventHandler.put(RTCPhoneEvent.EndCall(onSuccess, onFailure))
    }

    fun acceptCall(
        onSuccess: (() -> Unit)? = null,
        onFailure: ((ex: Exception) -> Unit)? = null
    ) {
        eventHandler.put(RTCPhoneEvent.AcceptIncomingCall(
            onSuccess = onSuccess ?: {
                Log.d(TAG, "acceptCall: success"); Unit
            },
            onFailure = onFailure ?: { Log.d(TAG, "acceptCall: failure"); Unit }
        ))
    }

    fun rejectCall(
        onSuccess: (() -> Unit)? = null,
        onFailure: ((ex: Exception) -> Unit)? = null
    ) {
        eventHandler.put(RTCPhoneEvent.EndCall(onSuccess, onFailure))
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
        this.realTimeDataJob = scope.launch() {
            // sending camera stream
            launch {
                var videoSendingJob: Job? = null
                mutCameraStateFlow.collect { isCameraEnable ->
                    videoSendingJob = if (isCameraEnable) {
                        launch {
                            Log.d(TAG, "sending video stream")
                            sendCaptureDataToRemotePeer()
                        }
                    } else {
                        videoSendingJob?.cancel()
                        null
                    }
                }
            }

            // sending audio stream
            // TODO: implement audio stream
        }
    }

    private fun stopSendingRealTimeData() {
        this.realTimeDataJob?.cancel()
        this.realTimeDataJob = null
    }

    private suspend fun sendCaptureDataToRemotePeer() {
        val format = withContext(Dispatchers.IO) {
            cameraCapture.getMediaFormat().get()
        }.toRTCFormat()

        val cameraOut = channelFlow<Sample> {
            val port = cameraCapture.compressedDataChannel()
            while (port.isOpenForReceive && isActive) {
                try {
                    send(port.receive().run {
                        Sample(
                            ptsUs = second.presentationTimeUs, buffer = first, flags = second.flags
                        )
                    })
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
        rtc.addStream(format, cameraOut)
    }

    @Composable
    fun RemotePeerView(modifier: Modifier = Modifier) {
        val remoteVideoPlayer by this.remoteVideoPlayerState.collectAsState()
        remoteVideoPlayer?.VideoPlayerView(modifier)
    }

    @Composable
    fun LocalPeerView(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val currentPreviewFrame = remember { mutableStateOf<ImageBitmap?>(null) }
        val yuvToRgbConverter = remember { YuvToRgbConverter(context) }

        fun Image.toBitmap(): Bitmap {
            val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            yuvToRgbConverter.yuvToRgb(this, outputBitmap)
            return outputBitmap
        }

        LaunchedEffect(Unit) {
            cameraCapture.setPreviewFrameListener { image ->
                currentPreviewFrame.value = image.toBitmap().asImageBitmap()
                image.close()
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

    inner class RTCPhoneEventHandler {
        private val eventQueue = Channel<RTCPhoneEvent>(Channel.BUFFERED)
        fun put(event: RTCPhoneEvent) {
            eventQueue.trySendBlocking(event)
        }

        fun start() {
            scope.launch {
                while (isActive) {
                    val event = eventQueue.receive()
                    Log.d(TAG, "event: $event")
                    processNextEvent(event)
                }
            }
        }

        private suspend fun processNextEvent(event: RTCPhoneEvent) {
            val callState = callStateFlow.value
            when (event) {
                is RTCPhoneEvent.GoOnline -> {
                    val connectionState = this@RTCPhone.connectionStateFlow.value
                    if (connectionState == ConnectionState.DisConnected) {
                        try {
                            signalClient.connect("$URL/${event.localPeerName}")
                            this@RTCPhone.mutConnectionStateFlow.value = ConnectionState.Connected
                            subscribeToIncomingEvents()
                            event.onSuccess()
                        } catch (ex: IOException) {
                            event.onFailure(
                                SignalServerError(
                                    "Failed to connect to signal server", ex
                                )
                            )
                        }
                    }
                }

                is RTCPhoneEvent.MakeCallEvent -> {
                    if (callState is CallState.NoSession) {
                        try {
                            rtc.createLocalCandidate()
                            signalClient.postOffer(
                                PostOfferParams(
                                    receiverId = event.remotePeer.id, candidates = rtc.getLocalIce()
                                )
                            )
                            mutCallStateFlow.value = CallState.CallingToPeer(event.remotePeer)
                            event.onSuccess()
                        } catch (ex: Exception) {
                            if (ex is CancellationException) throw ex
                            event.onFailure(RtcPhoneException.CallError("Failed to make call", ex))
                        }
                    }
                }

                is RTCPhoneEvent.OnRemotePeerAcceptCall -> {
                    if (callState is CallState.CallingToPeer) {
                        try {
                            rtc.setRemoteIce(event.candidates)
                            rtc.createPeerConnection()
                            createSendingRealTimeDataJob()
                            mutCallStateFlow.value = CallState.InSession(event.remotePeer)
                        } catch (ex: Exception) {
                            if (ex is CancellationException) throw ex
                            Log.e(TAG, "Failed to create peer connection", ex)
                        }
                    }
                }

                is RTCPhoneEvent.OnRemotePeerRejectCall -> {
                    if (callState is CallState.CallingToPeer) {
                        Log.d(TAG, "remote peer rejected the call ${event.remotePeer}")
                        mutCallStateFlow.value = CallState.NoSession
                        rtc.closePeerConnection()
                        rtc.setRemoteIce(emptyList())
                    }
                }

                is RTCPhoneEvent.EndCall -> {
                    when (callState) {
                        is CallState.CallingToPeer -> {
                            try {
                                mutCallStateFlow.value = CallState.NoSession
                                signalClient.cancelRecentOffer()
                                rtc.closePeerConnection()
                                rtc.setRemoteIce(emptyList())
                                event.onSuccess?.invoke()
                            } catch (ex: Exception) {
                                if (ex is CancellationException) throw ex
                                event.onFailure?.invoke(ex)
                            }
                        }

                        is CallState.InSession -> {
                            try {
                                this@RTCPhone.mutCallStateFlow.value = CallState.NoSession
                                signalClient.postCloseSession()
                                rtc.closePeerConnection()
                                rtc.setRemoteIce(emptyList())
                                event.onSuccess?.invoke()
                            } catch (ex: Exception) {
                                if (ex is CancellationException) throw ex
                                event.onFailure?.invoke(ex)
                            }
                        }

                        is CallState.IncomingCall -> {
                            try {
                                this@RTCPhone.mutCallStateFlow.value = CallState.NoSession
                                signalClient.postAnswer(
                                    PostAnswerParams(
                                        receiverId = callState.sender.id,
                                        candidates = null,
                                        accepted = false
                                    )
                                )
                                event.onSuccess?.invoke()
                            } catch (ex: Exception) {
                                if (ex is CancellationException) throw ex
                                event.onFailure?.invoke(ex)
                            }
                        }

                        else -> {}
                    }
                }

                is RTCPhoneEvent.OnCallReceived -> {
                    if (callState is CallState.NoSession) {
                        rtc.setRemoteIce(event.candidates)
                        mutCallStateFlow.value = CallState.IncomingCall(event.remotePeer)
                        Log.i(TAG, "call received from ${event.remotePeer}")
                    }
                }

                is RTCPhoneEvent.AcceptIncomingCall -> {
                    if (callState is CallState.IncomingCall) {
                        try {
                            rtc.createLocalCandidate()
                            signalClient.postAnswer(
                                PostAnswerParams(
                                    receiverId = callState.sender.id,
                                    candidates = rtc.getLocalIce(),
                                    accepted = true
                                )
                            )
                            rtc.createPeerConnection()
                            createSendingRealTimeDataJob()
                            mutCallStateFlow.value = CallState.InSession(callState.sender)
                            event.onSuccess()
                        } catch (ex: Exception) {
                            if (ex is CancellationException) throw ex
                            event.onFailure(
                                RtcPhoneException.CallError(
                                    "Failed to accept call",
                                    ex
                                )
                            )
                            Log.e(TAG, "Failed to create peer connection", ex)
                        }
                    }
                }

                is RTCPhoneEvent.OnIncomingCallCanceled -> {
                    if (callState is CallState.IncomingCall) {
                        mutCallStateFlow.value = CallState.NoSession
                        rtc.setRemoteIce(emptyList())
                    } else if (callState is CallState.InSession) {
                        try {
                            rtc.closePeerConnection()
                            stopSendingRealTimeData()
                            mutCallStateFlow.value = CallState.NoSession
                        } catch (ex: Exception) {
                            if (ex is CancellationException) throw ex
                            Log.e(TAG, "Failed to close peer connection", ex)
                        }
                    }
                }

                is RTCPhoneEvent.OnRemotePeerClosedSession -> {
                    if (callState is CallState.InSession) {
                        mutCallStateFlow.value = CallState.NoSession
                        try {
                            rtc.closePeerConnection()
                            rtc.setRemoteIce(emptyList())
                        } catch (ex: Exception) {
                            if (ex is CancellationException) throw ex
                            Log.e(TAG, "Failed to close peer connection", ex)
                        }
                    }
                }
            }
        }
    }
}

sealed interface RTCPhoneEvent {

    data class GoOnline(
        val localPeerName: String, val onSuccess: () -> Unit, val onFailure: (ex: Exception) -> Unit
    ) : RTCPhoneEvent


    /**
     * When Local peer make a call to the remote peer.
     */
    data class MakeCallEvent(
        val remotePeer: Peer, val onSuccess: () -> Unit, val onFailure: (ex: Exception) -> Unit
    ) : RTCPhoneEvent

    /**
     * Remote peer has accepted the call.
     */
    data class OnRemotePeerAcceptCall(
        val remotePeer: Peer,
        val candidates: List<ICECandidate>,
    ) : RTCPhoneEvent

    data class OnRemotePeerRejectCall(val remotePeer: Peer) : RTCPhoneEvent

    /**
     * Local peer has cancel the current call session/ joining of the session.
     */
    data class EndCall(
        val onSuccess: (() -> Unit)?, val onFailure: ((ex: Exception) -> Unit)?
    ) : RTCPhoneEvent

    data object OnRemotePeerClosedSession : RTCPhoneEvent

    /**
     * When Local peer accept a call from the remote peer.
     */
    data class AcceptIncomingCall(
        val onSuccess: () -> Unit, val onFailure: (ex: Exception) -> Unit
    ) : RTCPhoneEvent


    data class OnCallReceived(
        val remotePeer: Peer, val candidates: List<ICECandidate>
    ) : RTCPhoneEvent

    /**
     * Remote peer has cancelled his call offer.
     */
    data object OnIncomingCallCanceled : RTCPhoneEvent
}

sealed class RtcPhoneException(
    override val message: String?, override val cause: Throwable?
) : Exception() {
    class NetworkError(message: String?, cause: Throwable?) : RtcPhoneException(message, cause)
    class SignalServerError(message: String?, cause: Throwable?) : RtcPhoneException(message, cause)
    class CallError(message: String?, cause: Throwable?) : RtcPhoneException(message, cause)
    class PeerConnectionError(message: String?, cause: Throwable?) :
        RtcPhoneException(message, cause)
}
