package desidev.turnclient

import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.message.Message
import desidev.turnclient.message.MessageClass
import desidev.turnclient.message.MessageHeader
import desidev.turnclient.message.MessageType
import desidev.turnclient.message.StunRequestBuilder
import desidev.turnclient.util.NumberSeqGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.desidev.kotlinutils.Fll
import online.desidev.kotlinutils.ReentrantMutex
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


interface TurnAgent {
    fun allocate(): Fll<List<ICECandidate>, IOException>
    fun delete(): Fll<Unit, IOException>
}


class TurnClient(
    private val serverAddress: InetSocketAddress,
    private val username: String,
    private val password: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = ReentrantMutex()
    private var allocation: Allocation? = null
    private val dataChannels = mutableSetOf<DataChannel>()

    private var socketHandler: SocketHandler? = null
    private val numberSeqGenerator = NumberSeqGenerator(0x4000..0x7FFF)

    private var nonce: String? = null
    private var realm: String? = null

    private val allocateRequestBuilder = StunRequestBuilder().apply {
        setMessageType(MessageType.ALLOCATE_REQUEST)
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


    init {
        keepRefreshing()
    }

    suspend fun createAllocation(): Result<List<ICECandidate>> {
        return mutex.withLock {
            withContext(NonCancellable) {
                try {
                    socketHandler = socketHandler ?: SocketHandler().apply { start() }
                    val allocateRequest =
                        allocateRequestBuilder.setNonce(nonce).setRealm(realm).build()

                    val response = socketHandler!!.sendRequest(allocateRequest)
                    if (response.msgClass == MessageClass.SUCCESS_RESPONSE) {
                        allocation = parseAllocationResult(response)
                        Result.success(allocation!!.iceCandidates)

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

                                    realm =
                                        response.attributes.find { it.type == AttributeType.REALM.type }
                                            ?.getValueAsString()
                                    nonce =
                                        response.attributes.find { it.type == AttributeType.NONCE.type }
                                            ?.getValueAsString()
                                    // retry
                                    createAllocation()
                                }
                                // Stale nonce
                                438 -> {
                                    nonce =
                                        response.attributes.find { it.type == AttributeType.NONCE.type }
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
        }
    }

    suspend fun bindChannel(peerAddress: AddressValue): Result<ChannelBinding> =
        withContext(NonCancellable) {
            mutex.withLock {
                try {
                    if (allocation == null) {
                        throw IllegalStateException("Cannot add peer address before creating an allocation. Please create an allocation first.")
                    }

                    val channel = dataChannels.find { it.peerAddress == peerAddress }
                    if (channel != null) {
                        channel.isClosed = false
                        return@withLock Result.success(channel)
                    }

                    val channelNumber = numberSeqGenerator.next()
                    val channelBind = bindingRequestBuilder.setChannelNumber(channelNumber)
                        .setPeerAddress(peerAddress).setNonce(nonce).setRealm(realm).build()

                    val response = socketHandler!!.sendRequest(channelBind)

                    when (response.msgClass) {
                        MessageClass.SUCCESS_RESPONSE -> {
                            val dataChannel = DataChannel(peerAddress, channelNumber)
                            dataChannels.add(dataChannel)
                            Result.success(dataChannel)
                        }

                        MessageClass.ERROR_RESPONSE -> {
                            val errorCode =
                                response.attributes.find { it.type == AttributeType.ERROR_CODE.type }
                                    ?.getAsErrorValue()
                                    ?: throw RuntimeException("Error code not found in response")

                            when (errorCode.code) {
                                // stale nonce
                                438 -> {
                                    nonce =
                                        response.attributes.find { it.type == AttributeType.NONCE.type }
                                            ?.getValueAsString()
                                    // retry
                                    bindChannel(peerAddress)
                                }

                                // channel number is already bind to this peer
                                400 -> {
                                    Result.success(DataChannel(peerAddress, channelNumber))
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
                    Result.failure(ex)
                }
            }
        }

    private suspend fun refreshChannelBinding(channel: DataChannel) {
        mutex.withLock {
            if (allocation != null) {
                withContext(NonCancellable) {
                    val request = bindingRequestBuilder.setChannelNumber(channel.channelNumber)
                        .setPeerAddress(channel.peerAddress).setNonce(nonce).setRealm(realm).build()

                    socketHandler?.sendRequest(request)?.let {
                        if (it.msgClass == MessageClass.SUCCESS_RESPONSE) {
                            channel.resetExpireTime(5.minutes)
                        }
                    }
                }
            }
        }
    }

    // this refreshes the allocation
    private suspend fun refresh(lifetime: Duration) {
        check(lifetime.inWholeSeconds >= 0) { "Lifetime should be eq/greater than 0" }
        mutex.withLock {
            if (allocation != null) {
                val refreshRequest = refreshRequestBuilder.apply {
                    setLifetime(lifetime.inWholeSeconds.toInt())
                    setRealm(realm!!)
                    setNonce(nonce!!)
                }.build()
                val response = socketHandler!!.sendRequest(refreshRequest)
                when (response.msgClass) {
                    MessageClass.SUCCESS_RESPONSE -> {
                        val lifetimeAttr =
                            response.attributes.find { it.type == AttributeType.LIFETIME.type }
                                ?.getValueAsInt()
                                ?: throw RuntimeException("Lifetime not found in response")

                        allocation?.resetExpireTime(lifetimeAttr.seconds)
                    }

                    MessageClass.ERROR_RESPONSE -> {
                        val errorValue =
                            response.attributes.find { it.type == AttributeType.ERROR_CODE.type }
                                ?.getAsErrorValue()
                                ?: throw RuntimeException("Error code not found in response")

                        when (errorValue.code) {
                            // stale nonce
                            438 -> {
                                nonce =
                                    response.attributes.find { it.type == AttributeType.NONCE.type }
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
        }
    }

    suspend fun deleteAllocation() {
        mutex.withLock {
            refresh(0.seconds)
            allocation = null

            dataChannels.forEach { it.isClosed = true }
            dataChannels.clear()

            withContext(Dispatchers.IO) {
                socketHandler?.cancel()
                socketHandler?.join()
                socketHandler = null
            }
        }
    }

    suspend fun reset() {
        mutex.withLock {
            allocation = null
            withContext(Dispatchers.IO) {
                socketHandler?.cancel()
                socketHandler?.join()
                socketHandler = null
            }
        }
    }

    private fun keepRefreshing() {
        scope.launch {
            while (isActive) {
                delay(1.seconds)
                allocation?.let { alloc ->
                    if (alloc.isCloseToExpire()) {
                        refresh(10.minutes)
                    } else if (alloc.isExpired()) {
                        allocation = null
                        dataChannels.forEach {
                            if (!it.isClosed) it.close()
                        }
                        dataChannels.clear()
                    }

                    if (dataChannels.isNotEmpty()) {
                        dataChannels.filter { !it.isClosed }.forEach {
                            if (it.isCloseToExpire()) {
                                try {
                                    refreshChannelBinding(it)
                                } catch (ex: Exception) {
                                    ex.printStackTrace()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseAllocationResult(response: Message): Allocation {
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
            ), ICECandidate(
                ip = InetAddress.getByAddress(xorMappedAddr.address).hostAddress,
                port = xorMappedAddr.port,
                type = ICECandidate.CandidateType.SRFLX,
                protocol = TransportProtocol.UDP,
                priority = 2
            ), ICECandidate(
                ip = InetAddress.getLoopbackAddress().hostAddress,
                port = socketHandler!!.localPort,
                type = ICECandidate.CandidateType.HOST,
                protocol = TransportProtocol.UDP,
                priority = 1
            )
        )

        return Allocation(lifetime.seconds, candidates)
    }

    private inner class DataChannel(
        override val peerAddress: AddressValue,
        override val channelNumber: Int,
    ) : ChannelBinding, ExpireAble by ExpireAbleImpl(5.minutes) {

        private var dataCallback: DataCallback? = null
        var isClosed: Boolean = false
            set(value) = synchronized(this) { field = value }
            get() = synchronized(this) { field }

        override fun sendData(bytes: ByteArray) = synchronized(this) {
            if (!isClosed) {
                sendChannelData(channelNumber, bytes)
            }
        }

        fun onDataReceived(data: ByteArray) {
            if (!isClosed) {
                dataCallback?.onReceived(data)
            }
        }

        override fun setDataCallback(callback: DataCallback) {
            this.dataCallback = callback
        }

        override fun close() {
            isClosed = true
            dataCallback = null
        }

        private fun sendChannelData(channelNo: Int, data: ByteArray) {
            val channelData = ByteBuffer.allocate(4 + data.size)
            channelData.putShort(channelNo.toShort()) // 2 bytes channel number
            channelData.putShort(data.size.toShort()) // 2 bytes data length
            channelData.put(data) // application data

            val packet = DatagramPacket(channelData.array(), channelData.capacity())
            try {
                socketHandler!!.sendPacket(packet)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        override fun toString(): String {
            return "ChannelBindingImpl(peerAddress=$peerAddress, channelNumber=$channelNumber)"
        }
    }

    private abstract class ResponseCallback {
        abstract fun onResponse(response: Message)
        abstract fun onTimeout()
    }

    private inner class SocketHandler : Thread() {
        private var running = false
        private val buffer = ByteArray(65000)
        private val socket: DatagramSocket = DatagramSocket()

        private val callbackRegistry: MutableMap<MessageHeader.TransactionId, ResponseCallback> =
            Collections.synchronizedMap(HashMap())
        val localPort get() = socket.localPort

        override fun run() {
            running = true
            while (running) {
                // receive incoming data from socket
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val receivedData =
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length)

                    dispatchMessage(receivedData)

                } catch (ex: SocketException) {
                    ex.printStackTrace()
                } catch (ex: SocketTimeoutException) {
                    ex.printStackTrace()
                }
            }
        }

        private fun dispatchMessage(byteArray: ByteArray) {
            try {
                val type = (byteArray[0].toInt() shl 8) or byteArray[1].toInt()
                if (type in 0x4000..0x7FFF) {
                    dataChannels.find { it.channelNumber == type }
                        ?.onDataReceived(byteArray.copyOfRange(4, byteArray.size))
                    return
                }

                val message = Message.parse(byteArray)
                val header = message.header

                val msgClass = header.msgType and 0x0110u
                if (msgClass == MessageClass.SUCCESS_RESPONSE.type || msgClass == MessageClass.ERROR_RESPONSE.type) {
                    val cb = callbackRegistry[header.txId]
                    cb?.onResponse(message)
                    if (cb == null) {
                        System.err.println("No response callback found for transaction id: ${header.txId}")
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        suspend fun sendRequest(message: Message, resendInterval: Long = RESEND_INTERVAL): Message {
            assert((message.header.msgType and Message.MESSAGE_CLASS_MASK).toInt() == 0) { "Message does not describe a stun/turn request." }

            val messageTxId = message.header.txId
            val responseDeferred = CompletableDeferred<Message>()
            val messageCallback = object : ResponseCallback() {
                override fun onResponse(response: Message) {
                    responseDeferred.complete(response)
                    println(
                        "onResponse ***************************** " + "\n $response \n"
                    )
                }

                override fun onTimeout() {
                    responseDeferred.completeExceptionally(IOException("request timeout on tx: $messageTxId"))
                }
            }

            scope.launch {
                var timeout = REQUEST_TIMEOUT.seconds
                var prev = System.currentTimeMillis()

                callbackRegistry[messageTxId] = messageCallback
                while (!responseDeferred.isCompleted) {
                    val now = System.currentTimeMillis()
                    timeout -= (now - prev).milliseconds
                    prev = now

                    if (timeout <= 0.seconds) {
                        messageCallback.onTimeout()
                        break
                    }
                    try {
                        println(
                            "Request sent: ******************************" + "\n$message \n"
                        )
                        sendReqMessage(message)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }

                    delay(resendInterval)
                }
                callbackRegistry.remove(messageTxId)
            }

            return responseDeferred.await()
        }

        private fun sendReqMessage(message: Message) {
            val header = message.header
            val messageClass: UShort = header.msgType and Message.MESSAGE_CLASS_MASK
            if (messageClass == MessageClass.REQUEST.type) {
                val packet = message.encodeToByteArray().let { DatagramPacket(it, it.size) }
                sendPacket(packet)
            } else {
                throw IllegalArgumentException("This function only accepts Request Messages")
            }
        }

        fun sendKeepAlive() {
            try {
                val packet = DatagramPacket(byteArrayOf(0, 0), 2)
                sendPacket(packet)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        fun cancel() = synchronized(Unit) {
            running = false
            socket.close()
        }

        fun sendPacket(packet: DatagramPacket) {
            packet.apply {
                address = serverAddress.address
                port = serverAddress.port
            }
            socket.send(packet)
        }
    }


    companion object {
        const val REQUEST_TIMEOUT = 15000L // 15 seconds
        const val RESEND_INTERVAL = 5000L // 5 seconds
    }
}