package desidev.turnclient

import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.message.Message
import desidev.turnclient.message.MessageClass
import desidev.turnclient.message.MessageType
import desidev.turnclient.message.StunRequestBuilder
import desidev.turnclient.util.NumberSeqGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TurnClient(
    private val serverAddress: SocketAddress,
    private val username: String,
    private val password: String
) {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var allocation: Allocation? = null
    private val bindingChannels = mutableSetOf<ChannelBinding>()

    private var socketHandler: SocketHandler = SocketHandler().apply { start() }
    private val numberSeqGenerator = NumberSeqGenerator(0x4000..0x7FFF)

    private var nonce: String? = null
    private var realm: String? = null
    private val keepAliveTime = 15.seconds
    private var refreshJob: Job? = null

    private val allocateRequestBuilder = StunRequestBuilder().apply {
        setMessageType(MessageType.ALLOCATE_REQUEST)
        setUsername(username)
        setPassword(password)
    }

    private val allocateRefreshRequestBuilder = StunRequestBuilder().apply {
        setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
        setUsername(username)
        setPassword(password)
    }

    private val bindingRequestBuilder = StunRequestBuilder().apply {
        setMessageType(MessageType.CHANNEL_BIND_REQ)
        setUsername(username)
        setPassword(password)
    }

    private val refreshRequestBuilder = StunRequestBuilder().apply {
        setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
        setUsername(username)
        setPassword(password)
    }

    suspend fun createAllocation(): Result<List<ICECandidate>> {
        return try {
            if (allocation!= null) { throw IllegalStateException("Allocation already exists") }
            val allocateRequest = allocateRequestBuilder.setNonce(nonce).setRealm(realm).build()
            val response = socketHandler.sendMessage(allocateRequest)

            if (response.msgClass == MessageClass.SUCCESS_RESPONSE) {
                Result.success(parseAllocationResult(response))
                    .also { refreshJob = startRefreshJob() }
            } else {
                val errorAttr =
                    response.attributes.find { it.type == AttributeType.ERROR_CODE.type }

                if (errorAttr != null) {
                    val errorValue = errorAttr.getAsErrorValue()
                    when (errorValue.code) {
                        // Unauthorized
                        401 -> {
                            if (realm != null) {
                                throw IOException("Invalid username or password.")
                            }

                            realm = response.attributes.find { it.type == AttributeType.REALM.type }
                                ?.getValueAsString()
                            nonce = response.attributes.find { it.type == AttributeType.NONCE.type }
                                ?.getValueAsString()
                            // retry
                            createAllocation()
                        }
                        // Stale nonce
                        438 -> {
                            nonce = response.attributes.find { it.type == AttributeType.NONCE.type }
                                ?.getValueAsString()
                            // retry
                            createAllocation()
                        }

                        else -> {
                            throw IOException("Allocation failed with error: $errorValue")
                        }
                    }
                } else {
                    throw IOException("Allocation failed with Unknown error")
                }
            }
        } catch (ex: Exception) {
            Result.failure(ex)
        }
    }

    suspend fun createChannel(peerAddress: AddressValue): Result<ChannelBinding> {
        if (allocation == null) {
            throw IllegalStateException("Cannot add peer address before creating an allocation. Please create an allocation first.")
        }
        val channelNumber = numberSeqGenerator.next()
        val channelBind = bindingRequestBuilder
            .setChannelNumber(channelNumber)
            .setPeerAddress(peerAddress)
            .setNonce(nonce)
            .setRealm(realm)
            .build()

        val response: Message = try {
            socketHandler.sendMessage(channelBind)
        } catch (ex: Exception) {
            return Result.failure(ex)
        }

        return try {
            when (response.msgClass) {
                MessageClass.SUCCESS_RESPONSE -> {
                    bindingChannels.add(ChannelBindingImpl(peerAddress, channelNumber))
                    Result.success(ChannelBindingImpl(peerAddress, channelNumber))
                }

                MessageClass.ERROR_RESPONSE -> {
                    val errorCode =
                        response.attributes.find { it.type == AttributeType.ERROR_CODE.type }
                            ?.getAsErrorValue()
                            ?: throw RuntimeException("Error code not found in response")

                    when (errorCode.code) {
                        // stale nonce
                        438 -> {
                            nonce = response.attributes.find { it.type == AttributeType.NONCE.type }
                                ?.getValueAsString()
                            // retry
                            createChannel(peerAddress)
                        }

                        // channel number is already bind to this peer
                        400 -> {
                            Result.success(ChannelBindingImpl(peerAddress, channelNumber))
                        }

                        else -> {
                            throw IOException("Channel bind request failed with error code: $errorCode")
                        }
                    }
                }

                else -> {
                    throw IOException("Unknown error")
                }
            }

        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            Result.failure(ex)
        }
    }

    private suspend fun refreshChannelBinding(channelBinding: ChannelBinding) {
        val request = this.bindingRequestBuilder
            .setChannelNumber(channelBinding.channelNumber)
            .setPeerAddress(channelBinding.peerAddress)
            .setNonce(nonce)
            .setRealm(realm)
            .build()

        socketHandler.sendMessage(request)
    }

    // this refreshes the allocation
    private suspend fun refresh(lifetime: Duration) {
        check(lifetime.inWholeSeconds >= 0) { "Lifetime should be eq/greater than 0" }
        if (allocation == null) throw IllegalStateException("Allocation is not created yet")

        val refreshRequest = refreshRequestBuilder.apply {
            setLifetime(lifetime.inWholeSeconds.toInt())
            setRealm(realm!!)
            setNonce(nonce!!)
        }.build()

        val response = socketHandler.sendMessage(refreshRequest)

        when (response.msgClass) {
            MessageClass.SUCCESS_RESPONSE -> {
                val lifetimeAttr =
                    response.attributes.find { it.type == AttributeType.LIFETIME.type }
                        ?.getValueAsInt()
                        ?: throw RuntimeException("Lifetime not found in response")

                allocation = allocation?.copy(
                    lifetime = lifetimeAttr.seconds,
                    timestamp = System.currentTimeMillis()
                )
            }

            MessageClass.ERROR_RESPONSE -> {
                val errorValue =
                    response.attributes.find { it.type == AttributeType.ERROR_CODE.type }
                        ?.getAsErrorValue()
                        ?: throw RuntimeException("Error code not found in response")

                when (errorValue.code) {
                    // stale nonce
                    438 -> {
                        nonce = response.attributes.find { it.type == AttributeType.NONCE.type }
                            ?.getValueAsString()
                        refresh(lifetime)
                    }
                    else -> {
                        throw IOException("Refresh failed with error code: $errorValue")
                    }
                }
            }

            else -> throw IOException("Unknown error")
        }
    }

    suspend fun deleteAllocation() {
        refreshJob?.cancel()
        refreshJob = null
        refresh(0.seconds)
        allocation = null
    }

    suspend fun dispose() {
        scope.cancel()
        withContext(Dispatchers.IO) {
            socketHandler.cancel()
            socketHandler.join()
        }
    }

    private fun startRefreshJob() = scope.launch {
        // start a job to refresh the allocation
        launch {
            while (isActive) {
                delay(1.seconds)
                allocation!!.apply {
                    lifetime -= 1.seconds
                }
                if (allocation!!.lifetime < 2.minutes) {
                    try {
                        refresh(10.minutes)
                    }catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }
        }

        // start a job to refresh the permissions/channels bindings
        launch {
            while (isActive) {
                delay(3.minutes)
                bindingChannels.forEach {
                    launch {
                        try {
                            refreshChannelBinding(it)
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }
            }
        }

        // a child job to send keep alive messages
        launch {
            while (isActive) {
                delay(keepAliveTime)
                socketHandler.sendKeepAlive()
            }
        }
    }


    inner class ChannelBindingImpl(
        override val peerAddress: AddressValue,
        override val channelNumber: Int
    ) : ChannelBinding {
        override val timestamp: Long = System.currentTimeMillis()
        override fun sendMessage(bytes: ByteArray) {
            sendChannelData(channelNumber, bytes)
        }

        override fun receiveMessage(cb: IncomingMessage) {
            socketHandler.dataMessageListener[channelNumber] = cb
        }

        private fun sendChannelData(channelNo: Int, data: ByteArray) {
            val channelData = ByteBuffer.allocate(4 + data.size)
            channelData.putShort(channelNo.toShort()) // 2 bytes channel number
            channelData.putShort(data.size.toShort()) // 2 bytes data length
            channelData.put(data) // application data

            val packet = DatagramPacket(channelData.array(), channelData.capacity())
            try {
                socketHandler.sendPacket(packet)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ChannelBindingImpl
            if (peerAddress != other.peerAddress) return false
            return channelNumber == other.channelNumber
        }

        override fun hashCode(): Int {
            var result = peerAddress.hashCode()
            result = 31 * result + channelNumber
            return result
        }

        override fun toString(): String {
            return "ChannelBindingImpl(peerAddress=$peerAddress, channelNumber=$channelNumber)"
        }
    }


    private fun parseAllocationResult(response: Message): List<ICECandidate> {
        val attrs = response.attributes

        val relayedAddr =
            attrs.find { it.type == AttributeType.XOR_RELAYED_ADDRESS.type }?.getValueAsAddress()
                ?.xorAddress()
                ?: throw RuntimeException("Expected attribute not found ${AttributeType.XOR_RELAYED_ADDRESS.name}")

        val xorMappedAddr =
            attrs.find { it.type == AttributeType.XOR_MAPPED_ADDRESS.type }?.getValueAsAddress()
                ?.xorAddress()
                ?: throw RuntimeException("Expected attribute not found ${AttributeType.XOR_MAPPED_ADDRESS.name}")

        val lifetime = attrs.find { it.type == AttributeType.LIFETIME.type }?.getValueAsInt()
            ?: throw RuntimeException(
                "Expected attribute not found ${AttributeType.LIFETIME.name}"
            )

        val candidates = listOf(
            ICECandidate(
                ip = InetAddress.getByAddress(relayedAddr.address).hostAddress,
                port = relayedAddr.port,
                type = ICECandidate.CandidateType.RELAY,
                protocol = TransportProtocol.UDP,
                priority = 3
            ),
            ICECandidate(
                ip = InetAddress.getByAddress(xorMappedAddr.address).hostAddress,
                port = xorMappedAddr.port,
                type = ICECandidate.CandidateType.SRFLX,
                protocol = TransportProtocol.UDP,
                priority = 2
            )

        )
        allocation = Allocation(
            System.currentTimeMillis(), lifetime.seconds, candidates
        )

        return candidates
    }

    private abstract class ResponseCallback {
        var registeredAt: Long =
            0                  // the timestamp of the callback registration. Used to clean up on timed out.
        abstract val timeout: Int                   // timeout value for response.
        abstract val transactionId: String          // transaction id on which this callback is registered.
        abstract fun onResponse(response: Message)
        abstract fun onTimeout()
    }


    private inner class SocketHandler : Thread() {
        val dataMessageListener: MutableMap<Int, IncomingMessage> =
            Collections.synchronizedMap(HashMap())

        private val socketReceiveTimeout: Int = 136
        private var running = false
        private val buffer = ByteArray(1024 * 5)
        private val socket: DatagramSocket = DatagramSocket()

        // A map of response callbacks
        private val resCb: MutableMap<String, ResponseCallback> =
            Collections.synchronizedMap(HashMap())

        fun registerOnTransaction(responseCallback: ResponseCallback) {
            responseCallback.registeredAt = System.currentTimeMillis()
            this.resCb[responseCallback.transactionId] = responseCallback
        }

        override fun run() {
            socket.connect(serverAddress)
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

            val message = Message.parse(byteArray)
            val header = message.header

            val msgClass = header.msgType and 0x0110u
            if (msgClass == MessageClass.SUCCESS_RESPONSE.type || msgClass == MessageClass.ERROR_RESPONSE.type) {

                println("<Response>")
                println(message)
                println("</Response>\n")

                val cb = resCb.remove(header.txId.toHexString())
                cb?.onResponse(message)
            } else if (msgClass == MessageClass.INDICATION.type) {
                TODO()
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

        fun sendReqMessage(message: Message, resCb: ResponseCallback) {
            val header = message.header
            val messageClass: UShort = header.msgType and Message.MESSAGE_CLASS_MASK
            if (messageClass == MessageClass.REQUEST.type) {
                val packet = message.encodeToByteArray().let { DatagramPacket(it, it.size) }
                socket.send(packet)
                socketHandler.registerOnTransaction(resCb)

                println("<Request>")
                println(message)
                println("</Request> \n")

            } else {
                throw IllegalArgumentException("This function only accepts Request Messages")
            }
        }

        @OptIn(ExperimentalStdlibApi::class)
        suspend fun sendMessage(message: Message): Message {
            return suspendCancellableCoroutine { cont ->
                sendReqMessage(message, object : ResponseCallback() {
                    override val timeout: Int = REQUEST_TIMEOUT
                    override val transactionId: String = message.header.txId.toHexString()

                    override fun onResponse(response: Message) {
                        cont.resume(response)
                    }

                    override fun onTimeout() {
                        cont.resumeWithException(IOException("Request timed out"))
                    }
                })
            }
        }

        fun sendKeepAlive() {
            try {
                val packet = DatagramPacket(byteArrayOf(0, 0), 2)
                socket.send(packet)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        fun cancel() = synchronized(Unit) {
            running = false
            socket.close()
        }

        fun sendPacket(packet: DatagramPacket) {
            socket.send(packet)
        }
    }


    data class Allocation(
        val timestamp: Long, var lifetime: Duration, val iceCandidates: List<ICECandidate>
    )

    companion object {
        const val REQUEST_TIMEOUT = 15000 // 15 seconds
    }
}