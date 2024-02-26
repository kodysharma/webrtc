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
import desidev.videocall.service.message.VideoSample
import desidev.videocall.service.message.serializeMessage
import desidev.videocall.service.signal.Signal
import desidev.videocall.service.yuv.YuvToRgbConverter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds


/**
 * This is the default implementation of [CallService].
 *
 * ```
 * // Get the default implementation of [VideoCallService]
 * val videoCallService = VideoCallService.getDefault()
 * ```
 */
class DefaultVideoCallService<P : Any> internal constructor(
    context: Context,
    private val _signal: Signal<P>,
    username: String,
    password: String,
    turnIp: String,
    turnPort: Int,
) : CallService<P> {
    private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _turn = TurnClient(InetSocketAddress(turnIp, turnPort), username, password)
    private val _yuvToRgbConverter = YuvToRgbConverter(context)
    private val _cameraCapture: CameraCapture = CameraCapture.create(context)
    private val _stateLock = Mutex()
    private val _serviceState = MutableStateFlow<State>(State.Idle)

    private val _coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        _scope.launch {
            _stateLock.withLock {
                _serviceState.value = State.Error("Error: ${throwable.message}", throwable)
            }
        }
    }
    private var _sessionJob: Job? = null

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

    private var _outgoingCall: Offer<P>? = null
    private var _incomingCall: Offer<P>? = null

    init {
        val cond = ConditionAwait(serviceState.value == State.Idle)
        _scope.launch {
            while (isActive) {
                _incomingCall = _signal.incomingOffer.first()
                cond.await()
                handleCallReceive(_incomingCall!!)
            }

        }

        _scope.launch {
            serviceState.collect {
                cond.update(it == State.Idle)
            }
        }
    }


    private suspend fun handleCallReceive(call: Offer<P>) {
        val timeout = with(call) { expiryTime - System.currentTimeMillis() }
        if (timeout > 0) {
            _stateLock.withLock {
                _serviceState.value = State.Ringing
                delay(timeout)
            }

            _stateLock.withLock {
                if (serviceState.value == State.Ringing) {
                    _serviceState.value = State.Idle
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
        _scope.launch {
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
        _scope.launch(_coroutineExceptionHandler) {
            _stateLock.withLock {
                val allocationResult = _turn.allocation()
                if (allocationResult.isFailure) {
                    _serviceState.value = State.Error(
                        "Error: ${allocationResult.exceptionOrNull()?.message}",
                        allocationResult.exceptionOrNull()!!
                    )
                    return@launch
                }

                _cameraCapture.start()
                val videoFormat = _cameraCapture.getMediaFormat().get()

                _outgoingCall = Offer(
                    id = UUID.randomUUID().toString(),
                    peer = callee,
                    candidates = allocationResult.getOrThrow(),
                    mediaFormat = listOf(videoFormat),
                    timestamp = System.currentTimeMillis(),
                    expiryTime = System.currentTimeMillis() + offerTimeout.inWholeMilliseconds
                )

                _serviceState.value = State.Calling
                _signal.sendOffer(_outgoingCall!!)
            }

            try {
                val answer = withTimeout(offerTimeout) {
                    _signal.incomingAnswer.first { it.id == _outgoingCall!!.id }
                }

                _stateLock.withLock {
                    if (answer.accepted) {
                        startSession(answer.candidates)
                    } else {
                        _serviceState.value = State.Idle
                        _cameraCapture.stop()
                    }
                }

            } catch (ex: TimeoutCancellationException) {
                _stateLock.withLock {
                    _serviceState.value = State.Idle
                    _signal.cancelOffer(_outgoingCall!!)
                }
                return@launch
            }
        }
    }


    override fun cancel() {
        _scope.launch(_coroutineExceptionHandler) {
            _stateLock.withLock {
                when (serviceState.value) {
                    is State.Ringing -> {
                        _serviceState.value = State.Idle
                        _signal.sendAnswer(
                            Answer(
                                id = _incomingCall!!.id,
                                accepted = false,
                                mediaFormat = emptyList(),
                                candidates = emptyList()
                            )
                        )

                        _cameraCapture.stop()
                    }

                    is State.Calling -> {
                        _cameraCapture.stop()
                        _serviceState.value = State.Idle
                        _turn.deleteAllocation()
                        _signal.cancelOffer(_outgoingCall!!)
                    }

                    is State.InCall -> {
                        _cameraCapture.stop()
                        _serviceState.value = State.Idle
                        _turn.deleteAllocation()
                        _signal.cancelSession()
                    }

                    else -> {
                        Log.e(TAG, "Error: Invalid state")
                    }
                }
            }
        }
    }

    override fun accept() {
        _scope.launch {
            _stateLock.withLock {
                if (serviceState.value is State.Ringing) {
                    val allocationResult = _turn.allocation()
                    if (allocationResult.isFailure) {
                        _serviceState.value = State.Error(
                            "Error: ${allocationResult.exceptionOrNull()?.message}",
                            allocationResult.exceptionOrNull()!!
                        )
                        return@launch
                    }

                    _cameraCapture.start()
                    val videoFormat = _cameraCapture.getMediaFormat().get()

                    val answer = Answer(
                        id = _incomingCall!!.id,
                        accepted = true,
                        mediaFormat = listOf(videoFormat),
                        candidates = allocationResult.getOrThrow()
                    )

                    _signal.sendAnswer(answer)
                    startSession(_incomingCall!!.candidates)
                    _serviceState.value = State.InCall
                }
            }
        }
    }

    override fun dispose() {
        _scope.launch {
            _stateLock.withLock {
                if (serviceState.value is State.Idle && serviceState.value is State.Error) {
                    _cameraCapture.release()
                    _scope.cancel()
                    _serviceState.value = State.Disposed
                    _turn.deleteAllocation()
                } else {
                    Log.e(TAG, "Error: Invalid state")
                }
            }
        }
    }


    private suspend fun startSession(peerICECandidate: List<ICECandidate>) {
        val relay = peerICECandidate.find { it.type == CandidateType.RELAY }
            ?: throw IllegalStateException("No relay candidate found")

        val transportAddress =
            AddressValue.from(InetAddress.getByName(relay.ip), relay.port)

        _turn.createChannel(transportAddress).let { bindingResult ->
            if (bindingResult.isSuccess) {
                val dataChannel = bindingResult.getOrThrow()

                // callback function to receive messages from the remote peer
                dataChannel.receiveMessage { bytes ->
                    Log.d(TAG, "on message received: ${bytes.size}")
                }

                // send the camera frames to the remote peer
                _cameraCapture.compressedDataChannel().let {
                    while (it.isOpenForReceive) {
                        try {
                            val data = it.receive()
                            val sample = VideoSample(
                                data.second.presentationTimeUs,
                                data.second.flags,
                                data.first
                            )
                            dataChannel.sendMessage(serializeMessage(sample))
                        } catch (ex: Exception) {
                            Log.e(TAG, "Error: ${ex.message}")
                        }
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
        val TAG = DefaultVideoCallService::class.simpleName
        val offerTimeout = 10.seconds
    }
}

typealias ConditionCallback = () -> Unit

class ConditionAwait(initialValue: Boolean) {
    private var _condition: Boolean = initialValue
    private var callback = mutableListOf<ConditionCallback>()
    private val _mutex1 = Mutex()
    fun update(booleanValue: Boolean) {
        synchronized(this) {
            _condition = booleanValue
            if (_condition) {
                callback.forEach { it() }
                callback.clear()
            }
        }
    }

    suspend fun await() {
        _mutex1.withLock {
            if (!_condition) {
                coroutineScope {
                    suspendCoroutine { cont ->
                        callback.add {
                            cont.resume(Unit)
                        }
                    }
                }
            }
        }
    }
}
