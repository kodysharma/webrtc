package test.videocall

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import desidev.rtc.media.camera.CameraCaptureImpl
import desidev.utility.yuv.YuvToRgbConverter
import desidev.videocall.service.rtcclient.DefaultRtcClient
import desidev.videocall.service.rtcclient.TrackListener
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.videocall.service.rtcmsg.RTCMessage.Sample
import desidev.videocall.service.rtcmsg.toRTCFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.videocall.signalclient.AnswerEvent
import test.videocall.signalclient.OfferCancelledEvent
import test.videocall.signalclient.OfferEvent
import test.videocall.signalclient.Peer
import test.videocall.signalclient.PostAnswerParams
import test.videocall.signalclient.PostOfferParams
import test.videocall.signalclient.SessionClosedEvent
import test.videocall.signalclient.SignalClient


class RTCPhone(context: Context) {
    companion object {
        const val url = "ws://139.59.85.69:8080"
        val TAG = RTCPhone::class.java.simpleName
    }

    sealed class State {
        data object OnLine: State()
        data object OffLine: State()
        data class OutgoingCall(val receiver: Peer): State()
        data class IncomingCall(val sender: Peer): State()
        data class InSession(val peer: Peer): State()
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val rtc = DefaultRtcClient("64.23.160.217", 3478, "test", "test123")
    private val signalClient = SignalClient()

    private val cameraCapture = CameraCaptureImpl(context)
    private val mutPhoneStateFlow = MutableStateFlow<State>(State.OffLine)
    private var cameraStarted = false
    private val mutCameraStateFlow = MutableStateFlow(false)
    private var realTimeDataJob: Job? = null

    val phoneStateFlow: StateFlow<State> = mutPhoneStateFlow
    val cameraStateFlow: StateFlow<Boolean> = mutCameraStateFlow

    private val rtcTrackListener = object : TrackListener {
        override fun onVideoStreamAvailable(videoFormat: RTCMessage.Format) {
            Log.d(TAG, "onVideoStreamAvailable: $videoFormat")
        }

        override fun onAudioStreamAvailable(audioFormat: RTCMessage.Format) {
        }

        override fun onNextVideoSample(videoSample: Sample) {
            Log.d(TAG, "onNextVideoSample: ${videoSample.timeStamp}")
        }

        override fun onNextAudioSample(audioSample: Sample) {
        }

        override fun onVideoStreamDisable() {
        }

        override fun onAudioStreamDisable() {
        }
    }

    init {
        rtc.setTrackListener(rtcTrackListener)
    }

    suspend fun goOnline(peerName: String) {
        signalClient.connect("$url/${peerName}")
        mutPhoneStateFlow.value = State.OnLine
        scope.launch { subscribeToIncomingEvents() }
    }

    suspend fun getPeers(): List<Peer> {
        return signalClient.getPeers()
    }

    private suspend fun subscribeToIncomingEvents() {
        signalClient.eventFlow.collect { event ->
            when (event) {
                is OfferEvent -> {
                    if (phoneStateFlow.value is State.OnLine) {
                        rtc.setRemoteIce(event.candidates)
                        mutPhoneStateFlow.value = State.IncomingCall(event.sender)
                    }
                }

                is AnswerEvent -> {
                    if (phoneStateFlow.value is State.OutgoingCall) {
                        if (event.accepted) {
                            rtc.setRemoteIce(event.candidates!!)
                            rtc.createPeerConnection()
                            mutPhoneStateFlow.value = State.InSession(event.sender)
                            createSendingRealTimeDataJob()
                        } else {
                            mutPhoneStateFlow.value = State.OnLine
                        }
                    }
                }

                is SessionClosedEvent -> {
                    if (phoneStateFlow.value is State.InSession) {
                        rtc.closePeerConnection()
                        stopSendingRealTimeData()
                        mutPhoneStateFlow.value = State.OnLine
                    }
                }
                // remote peer has cancelled the call
                is OfferCancelledEvent -> {
                    val state = phoneStateFlow.value
                    if (state is State.IncomingCall || state is State.InSession) {
                        mutPhoneStateFlow.value = State.OnLine
                        if (state is State.InSession) {
                            rtc.closePeerConnection()
                            stopSendingRealTimeData()
                        }
                    }
                }
            }
        }
    }



    suspend fun makeCall(peer: Peer) {
        rtc.createLocalCandidate()
        signalClient.postOffer(
            PostOfferParams(
                receiverId = peer.id,
                candidates = rtc.getLocalIce()
            )
        )
        mutPhoneStateFlow.value = State.OutgoingCall(peer)
    }

    suspend fun endCall() {
        rtc.closePeerConnection()
        stopSendingRealTimeData()
        when (phoneStateFlow.value) {
            is State.InSession -> {
                signalClient.postCloseSession()
                mutPhoneStateFlow.value = State.OnLine
            }
            is State.OutgoingCall -> {
                signalClient.cancelRecentOffer()
                mutPhoneStateFlow.value = State.OnLine
            }
            else -> {}
        }
    }

    suspend fun acceptCall(peer: Peer) {
        if (phoneStateFlow.value is State.IncomingCall) {
            rtc.createLocalCandidate()
            rtc.createPeerConnection()
            signalClient.postAnswer(
                PostAnswerParams(
                    receiverId = peer.id,
                    accepted = true,
                    candidates = rtc.getLocalIce()
                )
            )
            mutPhoneStateFlow.value = State.InSession(peer)
            createSendingRealTimeDataJob()
        }
    }

    suspend fun rejectCall(peer: Peer) {
        if (phoneStateFlow.value is State.IncomingCall) {
            signalClient.postAnswer(
                PostAnswerParams(
                    receiverId = peer.id,
                    accepted = false,
                    candidates = null
                )
            )
            mutPhoneStateFlow.value = State.OnLine
        }
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
        this.realTimeDataJob =  scope.launch() {
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

    private fun  stopSendingRealTimeData() {
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
                            timeStamp = second.presentationTimeUs,
                            sample = first,
                            flag = second.flags
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
    fun RemotePeerView() {}

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
                Log.d(TAG, "preview frame update")
                image.close()
            }
        }

        if (currentPreviewFrame.value != null) {
            Image(
                bitmap = currentPreviewFrame.value!!,
                contentDescription = "Camera frame",
                modifier = modifier.fillMaxSize()
            )
        }
    }
}