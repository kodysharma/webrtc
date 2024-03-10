package desidev.videocall.service

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import desidev.turnclient.ICECandidate
import desidev.turnclient.ICECandidate.CandidateType
import desidev.turnclient.TurnClient
import desidev.turnclient.attribute.AddressValue
import desidev.videocall.service.CallService.State
import desidev.videocall.service.camera.CameraCapture
import desidev.videocall.service.camera.CameraLensFacing
import desidev.videocall.service.signal.Signal
import desidev.videocall.service.signal.SignalEvent
import desidev.videocall.service.yuv.YuvToRgbConverter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.time.Duration.Companion.seconds


class DefaultCallService<P : Any>(
    context: Context,
    signal: Signal<P>,
    username: String,
    password: String,
    turnIp: String,
    turnPort: Int,
) : CallService<P> {
    private val signal = signal
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val turn = TurnClient(InetSocketAddress(turnIp, turnPort), username, password)
    private val _yuvToRgbConverter = YuvToRgbConverter(context)
    private val _cameraCapture: CameraCapture = CameraCapture.create(context)
    private val _stateLock = Mutex()
    private val _serviceState = MutableStateFlow<State>(State.Idle)

    private val _coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        scope.launch {
            _stateLock.withLock {
                _serviceState.value = State.Error("Error: ${throwable.message}", throwable)
            }
        }
    }

    private val handler = ServiceHandle(scope)

    private var receivedOffer: Offer<P>? = null

    override val cameraFace: StateFlow<CameraLensFacing>
        get() = TODO("Not yet implemented")

    override val isCallActive: StateFlow<Boolean>
        get() = TODO("Not yet implemented")

    override val incomingCall: StateFlow<Offer<P>>
        get() = TODO("Not yet implemented")

    override val isCameraClosed: StateFlow<Boolean>
        get() = TODO("Not yet implemented")

    override val isVoiceMuted: StateFlow<Boolean>
        get() = TODO("Not yet implemented")

    override val serviceState = _serviceState

    init {
        scope.launch {
            signal.signalFlow.collect {
                when (it) {
                    is SignalEvent.OfferEvent<*> -> {
                        Log.d(TAG, "Received offer")
                        handler.sendEvent(Event.OnOfferReceived(it.offer))
                    }

                    is SignalEvent.AnswerEvent -> {
                        Log.d(TAG, "Received answer")
                        handler.sendEvent(Event.OnAnswerReceived(it.answer))
                    }

                    is SignalEvent.CancelOfferEvent -> {
                        Log.d(TAG, "Received cancel offer")
                        handler.sendEvent(Event.OnReceivedOfferCancel)
                    }

                    is SignalEvent.SessionCloseEvent -> {
                        Log.d(TAG, "Received session close")
                        handler.sendEvent(Event.OnSessionClose)
                    }
                }
            }
        }
    }


    @Composable
    override fun PeerViewContent(modifier: Modifier) {
        TODO("Not yet implemented")
    }

    @Composable
    override fun SelfPreviewContent(modifier: Modifier) {
        Box(modifier) {
            val isCamClosed by isCameraClosed.collectAsState()
            if (!isCamClosed) {
                var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(key1 = Unit) {
                    _cameraCapture.addPreviewFrameListener { image ->
                        imageBitmap = image.toImageBitmap()
                        image.close()
                    }
                }
                imageBitmap?.let {
                    // todo: draw the imageBitmap
                }
            } else {
                // todo: draw a placeholder
            }
        }
    }


    private fun Image.toImageBitmap(): ImageBitmap {
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        _yuvToRgbConverter.yuvToRgb(this, outputBitmap)
        return outputBitmap.asImageBitmap()
    }

    override fun muteVoice() {

    }

    override fun unMuteVoice() {

    }

    override fun switchCamera() {
        scope.launch {
            val lensFacing =
                if (cameraFace.value == CameraLensFacing.FRONT) CameraLensFacing.BACK else CameraLensFacing.FRONT
            _cameraCapture.selectCamera(lensFacing)
        }
    }

    override suspend fun openCamera() {
//        _cameraCapture.start()
    }

    override suspend fun closeCamera() {
//        _cameraCapture.stop()
    }

    override fun call(callee: P) {
        handler.sendEvent(
            Event.MakeCall(callee)
        )
    }

    override fun cancel() {
        val event = when (serviceState.value) {
            is State.Calling -> Event.CancelOutgoingOffer
            is State.Ringing -> Event.RejectOffer
            is State.InCall -> Event.CloseSession
            else -> throw IllegalStateException()
        }
        handler.sendEvent(event)
    }

    override fun accept() {
        handler.sendEvent(Event.AcceptOffer)
    }

    override fun dispose() {
    }

    private suspend fun startSession(peerICECandidate: List<ICECandidate>) {
        val relay = peerICECandidate.find { it.type == CandidateType.RELAY }
            ?: throw IllegalStateException("No relay candidate found")

        val transportAddress =
            AddressValue.from(InetAddress.getByName(relay.ip), relay.port)

        turn.createChannel(transportAddress).let { bindingResult ->
            if (bindingResult.isSuccess) {
                val dataChannel = bindingResult.getOrThrow()
                // callback function to receive messages from the remote peer
                dataChannel.receiveMessage { bytes ->
                    Log.d(TAG, "on message received: ${bytes.decodeToString()} ")
                }

                coroutineScope {
                    var packetCount = 0
                    while (isActive) {
                        delay(100)
                        dataChannel.sendMessage("${packetCount++}".encodeToByteArray())
                    }
                }

            } else {
                Log.e(TAG, "Error: ${bindingResult.exceptionOrNull()?.message}")
                _serviceState.value = State.Error(
                    "Error: ${bindingResult.exceptionOrNull()?.message}",
                    bindingResult.exceptionOrNull()!!
                )
            }
        }
    }

    companion object {
        val TAG = DefaultCallService::class.simpleName
        val offerTimeout = 10.seconds
    }

    sealed interface Event {
        data class MakeCall<P : Any>(val callee: P) : Event
        data object AcceptOffer : Event
        data object CancelOutgoingOffer : Event
        data object CloseSession : Event
        data class OnOfferReceived<P>(val offer: Offer<P>) : Event
        data class OnAnswerReceived(val answer: Answer) : Event
        data object OnSessionClose : Event
        data object RejectOffer : Event

        data object OnReceivedOfferCancel : Event
    }

    inner class ServiceHandle(scope: CoroutineScope) {
        private val eventQueue = Channel<Event>(Channel.BUFFERED)
        private var rtcTask: Job? = null

        init {
            scope.loop()
        }

        fun sendEvent(event: Event) {
            eventQueue.trySendBlocking(event)
        }

        private fun CoroutineScope.loop() = launch {
            for (event in eventQueue) {
                when (event) {
                    is Event.MakeCall<*> -> {
                        if (serviceState.value != State.Idle) return@launch

                        val allocationResult = turn.createAllocation()
                        if (allocationResult.isSuccess) {
                            val offer = Offer(
                                id = UUID.randomUUID().toString(),
                                peer = event.callee,
                                candidates = allocationResult.getOrThrow(),
                                timestamp = System.currentTimeMillis(),
                                expiryTime = System.currentTimeMillis() + offerTimeout.inWholeMilliseconds
                            )
                            signal.sendOffer(offer as Offer<P>)
                            _serviceState.value = State.Calling
                        }
                    }

                    Event.CancelOutgoingOffer -> {
                        if (serviceState.value !is State.Calling) return@launch
                        signal.cancelOffer()
                        turn.deleteAllocation()
                        _serviceState.value = State.Idle
                    }

                    is Event.AcceptOffer -> {
                        // accept the incoming call offer
                        if (serviceState.value !is State.Ringing) return@launch
                        val allocationResult = turn.createAllocation()
                        if (allocationResult.isSuccess) {
                            val answer = Answer(
                                id = receivedOffer!!.id,
                                accepted = true,
                                candidates = allocationResult.getOrThrow()
                            )
                            signal.sendAnswer(answer)
                            rtcTask = launch { startSession(receivedOffer!!.candidates) }
                            _serviceState.value = State.InCall
                        }
                    }

                    is Event.RejectOffer -> {
                        if (serviceState.value !is State.Ringing) return@launch
                        signal.sendAnswer(
                            Answer(
                                id = receivedOffer!!.id,
                                accepted = false,
                                candidates = emptyList()
                            )
                        )
                        _serviceState.value = State.Idle
                    }

                    Event.CloseSession -> {
                        if (serviceState.value !is State.InCall) return@launch
                        turn.deleteAllocation()
                        signal.cancelSession()
                        rtcTask?.cancel("RTC task cancelled")
                        _serviceState.value = State.Idle
                    }

                    is Event.OnAnswerReceived -> {
                        if (serviceState.value !is State.Calling) return@launch
                        val answer = event.answer
                        if (answer.accepted) {
                            rtcTask = launch { startSession(answer.candidates) }
                            _serviceState.value = State.InCall
                        } else {
                            _serviceState.value = State.Idle
                        }
                    }

                    is Event.OnOfferReceived<*> -> {
                        if (serviceState.value !is State.Idle) return@launch
                        receivedOffer = event.offer as Offer<P>
                        _serviceState.value = State.Ringing
                    }

                    Event.OnReceivedOfferCancel -> {
                        if (serviceState.value is State.Ringing) {
                            _serviceState.value = State.Idle
                        } else if (serviceState.value is State.InCall) {
                            turn.deleteAllocation()
                            rtcTask?.cancel("RTC task cancelled")
                            _serviceState.value = State.Idle
                        }
                    }

                    is Event.OnSessionClose -> {
                        if (serviceState.value is State.InCall) {
                            turn.deleteAllocation()
                            rtcTask?.cancel("RTC task cancelled")
                            _serviceState.value = State.Idle
                        }
                    }
                }
            }
        }
    }
}


