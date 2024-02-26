package desidev.turnclient

import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.message.Message
import desidev.turnclient.message.MessageClass
import desidev.turnclient.message.MessageType
import desidev.turnclient.util.countdownTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TurnClient(
    serverAddress: SocketAddress,
    private val user: String,
    private val password: String
) {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val socket: DatagramSocket = DatagramSocket()
    private var realm: String? = null
    private var nonce: String? = null
    private var allocation: List<ICECandidate>? = null
    private var refreshBeforeExpTimeJob: Job? = null

    private var inComingPacketHandler = InComingPacketHandler()
    private var numberGenerator = NumberGenerator()

    init {
        scope.launch(Dispatchers.IO) {
            socket.connect(serverAddress)
            inComingPacketHandler.start()
        }
    }


    /**
     * Returns an pair of local ip, port values
     */
    fun getHostAddress(): Pair<String, Int> {
        val localIp = socket.localAddress.hostAddress
        val localPort = socket.localPort
        return localIp to localPort
    }

    /**
     * Send an allocation request to the server.
     */
    @OptIn(ExperimentalStdlibApi::class)
    suspend fun allocation(): Result<List<ICECandidate>> {
        val allocateRequest = Message.buildAllocateRequest(
            username = user,
            password = password,
            realm = realm,
            nonce = nonce,
        )

        return try {
            suspendCancellableCoroutine { cont ->
                sendReqMessage(allocateRequest, object : ResponseCallback() {
                    override val timeout: Int = REQUEST_TIMEOUT
                    override val transactionId: String = allocateRequest.header.txId.toHexString()

                    override fun onResponse(response: Message) {
                        cont.resume(response)
                    }

                    override fun onTimeout() {
                        cont.resumeWithException(IOException("Request timed out"))
                    }
                })
            }.let { resMsg ->
                if (resMsg.msgClass == MessageClass.SUCCESS_RESPONSE) {
                    updateRefreshJob(lifetime = 600.seconds)
                    Result.success(parseAllocationResult(resMsg))
                } else {
                    if (realm == null || nonce == null) {
                        realm = resMsg.attributes.find { it.type == AttributeType.REALM.type }
                            ?.getValueAsString()
                        nonce = resMsg.attributes.find { it.type == AttributeType.NONCE.type }
                            ?.getValueAsString()

                        if (realm != null && nonce != null) {
                            allocation()
                        } else {
                            Result.failure(IOException("Message Response does not contain required attributes"))
                        }
                    } else {
                        Result.failure(IOException("Unknown Error"))
                    }
                }
            }
        } catch (ex: Exception) {
            Result.failure(ex)
        }
    }


    /**
     * Bind a peer address to a bind channel. This basically opens a way to send/receive messages from/to the peer.
     * @param peerAddress The address to which new channel is going to bind.
     * @return returns an implementation of ChannelBinding to send/receive the messages
     */
    @OptIn(ExperimentalStdlibApi::class)
    suspend fun createChannel(peerAddress: AddressValue): Result<ChannelBinding> {
        if (allocation == null) {
            throw IllegalStateException("Cannot add peer address before creating an allocation. Please create an allocation first.")
        }

        val channelNo = numberGenerator.getNumber()
        val channelBind =
            Message.buildChannelBind(channelNo, peerAddress, user, password, realm!!, nonce!!)

        println("channel Bind request: \n$channelBind")

        val response: Message = suspendCancellableCoroutine { cont ->
            sendReqMessage(channelBind, object : ResponseCallback() {
                override val timeout: Int = REQUEST_TIMEOUT
                override val transactionId: String = channelBind.header.txId.toHexString()

                override fun onResponse(response: Message) {
                    cont.resume(response)
                }

                override fun onTimeout() {
                    cont.resumeWithException(CancellationException("Channel bind request timed out"))
                }
            })
        }

        println("response: \n$response")

        when (response.msgClass) {
            MessageClass.SUCCESS_RESPONSE -> {
                return Result.success(ChannelBindingImpl(peerAddress, channelNo))
            }

            MessageClass.ERROR_RESPONSE -> {
                val errorCode =
                    response.attributes.find { it.type == AttributeType.ERROR_CODE.type }
                        ?.getValueAsInt()
                        ?: throw RuntimeException("Error code not found in response")
                return Result.failure(IOException("Channel bind request failed with error code: $errorCode"))
            }

            else -> {
                return Result.failure(IOException("Unknown error"))
            }
        }
    }

    private fun sendReqMessage(message: Message, resCb: ResponseCallback) {
        val header = message.header
        val messageClass: UShort = header.msgType and Message.MESSAGE_CLASS_MASK
        if (messageClass == MessageClass.REQUEST.type) {
            val packet = message.encodeToByteArray().let { DatagramPacket(it, it.size) }
            socket.send(packet)
            inComingPacketHandler.registerOnTransaction(resCb)
        } else {
            throw IllegalArgumentException("This function only accepts Request Messages")
        }
    }

    // this refreshes the allocation
    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun refresh(lifetime: Duration) {
        check(lifetime.inWholeSeconds >= 0) { "Lifetime should be eq/greater than 0" }
        if (allocation == null) throw IllegalStateException("Allocation is not created yet")
        val refreshRequest = Message.buildRefreshRequest(
            lifetime.inWholeSeconds.toInt(),
            user,
            password,
            realm!!,
            nonce!!
        )

        val response: Result<Message> = suspendCancellableCoroutine { cont ->
            sendReqMessage(refreshRequest, object : ResponseCallback() {
                override val timeout: Int = REQUEST_TIMEOUT
                override val transactionId: String = refreshRequest.header.txId.toHexString()

                override fun onResponse(response: Message) {
                    cont.resume(Result.success(response))
                }

                override fun onTimeout() {
                    cont.resume(Result.failure(IOException("Refresh request timed out")))
                }
            })
        }

        if (response.isSuccess) {
            println("Refresh response: ${response.getOrThrow()}")
            val message = response.getOrThrow()
            when (message.msgClass) {
                MessageClass.SUCCESS_RESPONSE -> {
                    val lifetimeAttr =
                        message.attributes.find { it.type == AttributeType.LIFETIME.type }
                            ?.getValueAsInt()
                            ?: throw RuntimeException("Expected attribute not found ${AttributeType.LIFETIME.name}")

                    updateRefreshJob(lifetimeAttr.seconds)
                }

                MessageClass.ERROR_RESPONSE -> {
                    val errorCode =
                        message.attributes.find { it.type == AttributeType.ERROR_CODE.type }
                            ?.getValueAsInt()
                            ?: throw RuntimeException("Expected attribute not found ${AttributeType.ERROR_CODE.name}")
                    throw IOException("Refresh request failed with error code: $errorCode")
                }

                else -> throw IOException("Unknown error")
            }
        } else {
            throw response.exceptionOrNull()!!
        }

    }

    suspend fun deAllocate() {
        refreshBeforeExpTimeJob?.cancel()

        // this closes the turn allocation
        refresh(0.seconds)
        allocation = null

        // cancel the handling incoming packets and closes the sockets
        inComingPacketHandler.cancel()

        withContext(Dispatchers.IO) {
            inComingPacketHandler.join()
        }

        numberGenerator.close()
    }

    private fun updateRefreshJob(lifetime: Duration) {
        refreshBeforeExpTimeJob?.cancel()
        refreshBeforeExpTimeJob = scope.countdownTimer(
            duration = lifetime,
            onTick = {
                if (it.seconds <= 120.seconds) {
                    refresh(600.seconds)
                }
            }
        )
    }

    inner class ChannelBindingImpl(
        override val peerAddress: AddressValue,
        override val channelNumber: Int
    ) : ChannelBinding {
        override fun sendMessage(bytes: ByteArray) {
            sendChannelData(channelNumber, bytes)
        }

        override fun receiveMessage(cb: IncomingMessage) {
            inComingPacketHandler.dataMessageListener[channelNumber] = cb
        }

        private fun sendChannelData(channelNo: Int, data: ByteArray) {
            val channelData = ByteBuffer.allocate(4 + data.size)
            channelData.putShort(channelNo.toShort()) // 2 bytes channel number
            channelData.putShort(data.size.toShort()) // 2 bytes data length
            channelData.put(data) // application data

            val packet = DatagramPacket(channelData.array(), channelData.capacity())
            socket.send(packet)
        }
    }


    private fun parseAllocationResult(response: Message): List<ICECandidate> {
        val attrs = response.attributes

        val relayedAddr =
            attrs.find { it.type == AttributeType.XOR_RELAYED_ADDRESS.type }?.getValueAsAddress()
                ?: throw RuntimeException("Expected attribute not found ${AttributeType.XOR_RELAYED_ADDRESS.name}")

        val xorMappedAddr =
            attrs.find { it.type == AttributeType.XOR_MAPPED_ADDRESS.type }?.getValueAsAddress()
                ?: throw RuntimeException("Expected attribute not found ${AttributeType.XOR_MAPPED_ADDRESS.name}")

        val lifetime = attrs.find { it.type == AttributeType.LIFETIME.type }?.getValueAsInt()
            ?: throw RuntimeException(
                "Expected attribute not found ${AttributeType.LIFETIME.name}"
            )

        updateRefreshJob(lifetime.seconds)

        val iceCandidates = buildList {
            val relay = ICECandidate(
                ip = InetAddress.getByAddress(relayedAddr.xorAddress().address).hostAddress,
                port = relayedAddr.port,
                type = ICECandidate.CandidateType.RELAY,
                protocol = TransportProtocol.UDP,
                priority = 3
            )

            val srflx = ICECandidate(
                ip = InetAddress.getByAddress(xorMappedAddr.xorAddress().address).hostAddress,
                port = xorMappedAddr.port,
                type = ICECandidate.CandidateType.SRFLX,
                protocol = TransportProtocol.UDP,
                priority = 2
            )

            val host = ICECandidate(
                ip = socket.localAddress.hostAddress,
                port = socket.localPort,
                type = ICECandidate.CandidateType.HOST,
                protocol = TransportProtocol.UDP,
                priority = 1
            )

            add(relay)
            add(srflx)
            add(host)
        }

        allocation = iceCandidates

        return iceCandidates
    }

    private abstract class ResponseCallback {
        var registeredAt: Long =
            0                  // the timestamp of the callback registration. Used to clean up on timed out.
        abstract val timeout: Int                   // timeout value for response.
        abstract val transactionId: String          // transaction id on which this callback is registered.
        abstract fun onResponse(response: Message)
        abstract fun onTimeout()
    }

    private inner class NumberGenerator {
        val c = Channel<Int>()

        init {
            start()
        }

        @OptIn(DelicateCoroutinesApi::class)
        private fun start() = scope.launch {
            val range = 0x4000..0x7FFF
            for (v in range) {
                if (c.isClosedForSend) break
                c.send(v)
            }
        }

        suspend fun getNumber(): Int = c.receive()
        fun close() = c.close()
    }

    private inner class InComingPacketHandler : Thread() {
        val dataMessageListener: MutableMap<Int, IncomingMessage> =
            Collections.synchronizedMap(HashMap())
        private val socketReceiveTimeout: Int = 136
        private var running = false
        private val buffer = ByteArray(1024 * 5)

        // A map of response callbacks
        private val resCb: MutableMap<String, ResponseCallback> =
            Collections.synchronizedMap(HashMap())

        fun registerOnTransaction(responseCallback: ResponseCallback) {
            responseCallback.registeredAt = System.currentTimeMillis()
            this.resCb[responseCallback.transactionId] = responseCallback
        }

        override fun run() {
            running = true
            socket.soTimeout = socketReceiveTimeout
            while (running) {

                // receive incoming data from socket
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val receivedData =
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    dispatchMessage(receivedData)
                } catch (_: SocketException) {

                } catch (_: SocketTimeoutException) {

                }

                // Check for a timed-out responseCallback and remove it.
                removeCallbacksOnTimeout()
            }
        }

        @OptIn(ExperimentalStdlibApi::class)
        private fun dispatchMessage(byteArray: ByteArray) {
            val type = (byteArray[0].toInt() shl 8) or byteArray[1].toInt()

            if (type in 0x4000..0x7FFF) {
                dataMessageListener[type]?.invoke(byteArray.copyOfRange(4, byteArray.size))
                return
            }

            if (MessageType.isValidType(type.toUShort())) {
                val message = Message.parse(byteArray)
                val header = message.header

                val msgClass = header.msgType and 0x0110u
                if (msgClass == MessageClass.SUCCESS_RESPONSE.type || msgClass == MessageClass.ERROR_RESPONSE.type) {
                    val cb = resCb.remove(header.txId.toHexString())
                    cb?.onResponse(message)
                } else if (msgClass == MessageClass.INDICATION.type) {
                    TODO()
                }
            }
        }

        fun removeCallbacksOnTimeout() {
            val toRemove = mutableListOf<ResponseCallback>()
            val currentTime = System.currentTimeMillis()

            this.resCb.forEach { entry ->
                if (currentTime - entry.value.registeredAt > entry.value.timeout) {
                    toRemove.add(entry.value)
                }
            }

            toRemove.forEach { entry ->
                resCb.remove(entry.transactionId)
                entry.onTimeout()
            }
        }

        fun cancel() = synchronized(Unit) {
            running = false
            socket.close()
        }
    }

    companion object {
        const val REQUEST_TIMEOUT = 15000 // 15 seconds
    }
}