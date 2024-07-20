package desidev.p2p.turn

import desidev.p2p.ICECandidate
import desidev.p2p.MessageObserver
import desidev.p2p.SocketFailure.PortIsNotAvailable
import desidev.p2p.TransportAddress
import desidev.p2p.TurnRequestFailure
import desidev.p2p.TurnRequestFailure.AllocationMismatchException
import desidev.p2p.TurnRequestFailure.BadRequestException
import desidev.p2p.TurnRequestFailure.MissingAttributeException
import desidev.p2p.TurnRequestFailure.ServerUnreachable
import desidev.p2p.TurnRequestFailure.StaleNonceException
import desidev.p2p.TurnRequestFailure.UnauthorizedException
import desidev.p2p.TurnRequestFailure.WrongCredException
import desidev.p2p.UdpMsg
import desidev.p2p.UdpSocket
import desidev.p2p.turn.attribute.AddressValue
import desidev.p2p.turn.attribute.AttributeType
import desidev.p2p.turn.attribute.TransportProtocol
import desidev.p2p.turn.message.InvalidStunMessage
import desidev.p2p.turn.message.MessageClass
import desidev.p2p.turn.message.MessageHeader
import desidev.p2p.turn.message.MessageType
import desidev.p2p.turn.message.TurnMessage
import desidev.p2p.turn.message.TurnRequestBuilder
import desidev.p2p.util.NumberSeqGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private const val LIFETIME_SEC = 600
private const val SOCK_CLOSED_MSG = "Socket is closed"

typealias Lifetime = Int

internal interface TurnOperations {
    suspend fun allocate(): Allocation
    suspend fun refresh(): Lifetime
    suspend fun clear()
    suspend fun createPermission(channelNumber: Int, peerAddress: AddressValue)
    fun send(bytes: ByteArray, channelNumber: Int)
    fun setRealm(realm: String)
    fun setNonce(nonce: String)
    fun receive(cb: Callback?)
    fun interface Callback {
        fun onReceive(channelNumber: Int, msg: ByteArray)
    }
}


interface TurnSocket {
    suspend fun allocate()
    fun getIce(): List<ICECandidate>

    /**
     * Create Peer Permission via channel binding.
     * You can start communicating after this call.
     */
    suspend fun createPermission(peer: InetSocketAddress)
    fun removePermission(peer: InetSocketAddress)
    fun send(message: ByteArray, peer: InetSocketAddress)
    fun isAllocationExist(): Boolean
    fun addCallback(callback: Callback)
    suspend fun refresh()

    /**
     * This sends a refresh request with 0 lifetime.
     * Which clear the allocation on server
     */
    suspend fun clear()

    /**
     * Don't use after close
     */
    fun close()

    fun interface Callback {
        fun onReceive(data: ByteArray, peer: InetSocketAddress)
    }
}


/**
 *
 * ## Responsibility
 * - Creating and auto refreshing Allocations.
 * - Creating Permission via channel binding request and auto refreshing.
 * - Sending and receiving messages from & to the peer.
 * - Checking Connectivity with TurnServer.
 *
 * ## How to use
 * Create Allocation using [TurnSocket.allocate] function. Once you have created allocation
 * You need to create permission to for peer to start exchanging data using
 * [TurnSocket.createPermission].
 *
 * use [TurnSocket.removePermission] if you want to stop communication with a peer.
 * use [TurnSocket.close] to close the socket and don't use it after closing.
 * use [TurnSocket.addCallback] to listen incoming message from a peer and other events.
 *
 * @throws PortIsNotAvailable: When you chose a port which is not available or is out of range.
 * @throws SecurityException: if a security manager exists and its checkListen method doesn't allow the operation.
 */


private val logger = KotlinLogging.logger { TurnSocket::class.simpleName }
fun TurnSocket(
    configParameters: TurnConfiguration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): TurnSocket {
    val socket = UdpSocket(configParameters.localHostIp, configParameters.localPort)
    val turnConfig = TurnConfig(
        username = configParameters.username,
        password = configParameters.password,
        ip = configParameters.turnServerHost,
        port = configParameters.turnServerPort,
    )

    return object : TurnSocket {
        private var scope = CoroutineScope(dispatcher)
        private var allocation: Allocation? = null
        private val operations = TurnOperationsImpl(socket, turnConfig)
        private val channelBindingRegister = ChannelBindingRegister()
        private val numberSeqGenerator = NumberSeqGenerator(0x4000..0x7fff)
        private val callbacks = mutableListOf<TurnSocket.Callback>()
        private var isClosed: Boolean = false

        init {
            listen()
            refreshTask()
        }

        private fun listen() {
            operations.receive { channelNumber, msg ->
                val peer = channelBindingRegister.getPeerAddress(channelNumber)
                if (peer != null) {
                    callbacks.forEach {
                        it.onReceive(msg, peer)
                    }
                }
            }
        }

        private fun refreshTask() {
            scope.launch {
                while (isActive) {
                    delay(1000)
                    tryRefreshAllocation()
                    tryRefreshChannel()
                    removeExpiredChannel()
                }
            }
        }

        private suspend fun tryRefreshAllocation() {
            allocation?.let {
                if (it.isCloseToExpire()) {
                    try {
                        val lifetime = operations.refresh()
                        allocation?.resetExpireTime(lifetime.seconds)

                    } catch (e: StaleNonceException) {
                        operations.setNonce(e.nonce)
                        tryRefreshAllocation()

                    } catch (e: Throwable) {
                        e.printStackTrace()
                        return@let
                    }
                }
            }
        }

        private suspend fun tryRefreshChannel() {
            channelBindingRegister.toList().forEach {
                if (it.isCloseToExpire() && it.enable) {
                    try {
                        operations.createPermission(it.channelNumber, AddressValue.from(it.peer))
                        it.resetExpireTime()

                    } catch (e: StaleNonceException) {
                        operations.setNonce(e.nonce)
                        tryRefreshChannel()

                    } catch (e: Throwable) {
                        e.printStackTrace()
                        return@forEach
                    }
                }
            }
        }

        private fun removeExpiredChannel() {
            channelBindingRegister.toList().forEach {
                if (it.isExpired()) {
                    channelBindingRegister.remove(it.peer)
                }
            }
        }

        override suspend fun allocate() {
            check(!isClosed) { "Socket is closed" }
            allocation = try {
                operations.allocate()
            } catch (e: UnauthorizedException) {
                operations.setRealm(e.realm)
                operations.setNonce(e.nonce)
                // retry
                operations.allocate()
            } catch (e: StaleNonceException) {
                operations.setNonce(e.nonce)
                // retry
                operations.allocate()
            }
        }

        override fun isAllocationExist(): Boolean {
            return allocation?.isExpired()?.not() ?: false
        }

        /**
         * @exception TurnRequestFailure
         * @exception IllegalStateException if turnSocket is closed
         */
        override suspend fun createPermission(peer: InetSocketAddress) {
            check(!isClosed) { SOCK_CLOSED_MSG }
            channelBindingRegister.get(peer)?.let {
                it.enable = true
                return
            }

            val number = numberSeqGenerator.next()
            try {
                operations.createPermission(number, AddressValue.from(peer))
            } catch (e: UnauthorizedException) {
                operations.setNonce(e.nonce)
                operations.setRealm(e.realm)
                // retry
                operations.createPermission(number, AddressValue.from(peer))
            } catch (e: StaleNonceException) {
                operations.setNonce(e.nonce)
                // retry
                operations.createPermission(number, AddressValue.from(peer))
            }
            channelBindingRegister.registerChannel(ChannelBinding(peer, number, true, LIFETIME_SEC))
        }

        override fun removePermission(peer: InetSocketAddress) {
            check(!isClosed) { SOCK_CLOSED_MSG }
            channelBindingRegister.get(peer)?.let {
                it.enable = false
            }
        }

        override fun getIce(): List<ICECandidate> {
            return allocation?.iceCandidates ?: emptyList()
        }


        override suspend fun refresh() {
            try {
                val lifetime = operations.refresh()
                allocation?.resetExpireTime(lifetime.seconds)
            } catch (e: StaleNonceException) {
                operations.setNonce(e.nonce)
                refresh()
            }
        }


        override suspend fun clear() {
            if (isAllocationExist()) {
                try {
                    operations.clear()
                    allocation = null
                    channelBindingRegister.clear()
                } catch (e: StaleNonceException) {
                    operations.setNonce(e.nonce)
                    clear()
                }
            }
        }

        override fun close() {
            if (isClosed) return
            scope.cancel()
            socket.close()
            operations.receive(null)
            isClosed = true
        }

        override fun send(message: ByteArray, peer: InetSocketAddress) {
            check(!isClosed) { SOCK_CLOSED_MSG }
            val channelNumber = channelBindingRegister.getChannel(peer)
            require(channelNumber != null) { "No permission is created for peer address: $peer" }
            operations.send(message, channelNumber)
        }

        override fun addCallback(callback: TurnSocket.Callback) {
            callbacks.add(callback)
        }
    }
}


internal class TurnOperationsImpl(
    private val socket: UdpSocket,
    turnConfig: TurnConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TurnOperations {
    private var turnRequest = TurnRequestBuilder()
        .setUsername(turnConfig.username)
        .setPassword(turnConfig.password)

    private val turnTransportAddress = TransportAddress(turnConfig.ip, turnConfig.port)

    private var callback: TurnOperations.Callback? = null

    private val turnMsgTxRegister = ConcurrentHashMap<MessageHeader.TransactionId, MsgTx>()

    // dispatches incoming udp messages
    private val udpMsgCallback = MessageObserver.MsgCallback { udpMsg ->
        val byteArray = udpMsg.bytes
        val channelNumber = (byteArray[0].toInt() shl 8) or byteArray[1].toInt()

        if (channelNumber in 0x4000..0x7FFF) {
            callback?.onReceive(channelNumber, byteArray.copyOfRange(4, byteArray.size))
            return@MsgCallback
        }


        val turnMsg = try {
            TurnMessage.parse(byteArray)
        } catch (e: InvalidStunMessage) {
            logger.error(e) { "Invalid Stun message" }
            return@MsgCallback
        }

        val msgTx = turnMsgTxRegister[turnMsg.txId]
        msgTx?.complete(turnMsg)
        if (msgTx == null) {
            logger.info {
                "TurnMsg Response Discarded with id ${turnMsg.txId}"
            }
        }
    }

    init {
        // listen incoming message on socket
        socket.addCallback(udpMsgCallback)
    }


    /**
     * @throws TurnRequestFailure
     * @throws ServerUnreachable If the request is not complete within the time.
     */
    override suspend fun allocate(): Allocation {
        val request = turnRequest.setMessageType(MessageType.ALLOCATE_REQUEST)
            .setLifetime(LIFETIME_SEC)
            .build()

        val response = sendTurnRequest(request)
        return handleAllocateResponse(response)
    }

    override suspend fun refresh(): Lifetime {
        val request = turnRequest
            .setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
            .setLifetime(LIFETIME_SEC)
            .build()

        val response = sendTurnRequest(request)
        if (response.msgClass != MessageClass.SUCCESS_RESPONSE) {
            throw parseError(response)
        }

        val lifetime = response.attributes.find { it.type == AttributeType.LIFETIME.type }
            ?.getValueAsInt() ?: throw MissingAttributeException(AttributeType.LIFETIME.name)

        return lifetime
    }

    private suspend fun sendTurnRequest(msg: TurnMessage): TurnMessage {
        val msgTx = MsgTx(msg.txId)
        registerMsgTx(msgTx)

        val maxRetry = 3
        var retries = 0

        logger.debug { "send turn request: \n$msg" }

        try {
            while (retries < maxRetry) {
                try {
                    msg.sendOnSocket()
                    val response = withTimeout(5000) {
                        msgTx.await()
                    }
                    return response
                } catch (e: TimeoutCancellationException) {
                    retries++
                }
            }
        } finally {
            unregisterMsgTx(msgTx)
        }
        throw ServerUnreachable()
    }

    override suspend fun clear() {
        val request = turnRequest.setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
            .setLifetime(0)
            .build()

        val response = sendTurnRequest(request)
        if (response.msgClass == MessageClass.ERROR_RESPONSE) {
            throw parseError(response)
        }
    }

    override suspend fun createPermission(
        channelNumber: Int,
        peerAddress: AddressValue
    ) {
        val request = turnRequest.setMessageType(MessageType.CHANNEL_BIND_REQ)
            .setChannelNumber(channelNumber)
            .setPeerAddress(peerAddress)
            .build()

        val response = sendTurnRequest(request)
        if (response.msgClass == MessageClass.ERROR_RESPONSE) {
            throw parseError(response)
        }
    }

    override fun send(bytes: ByteArray, channelNumber: Int) {
        val buffer = ByteBuffer.allocate(4 + bytes.size).apply {
            putShort(channelNumber.toShort()) // 2 bytes channel number
            putShort(bytes.size.toShort()) // 2 bytes data length
            put(bytes) // application data
        }
        socket.send(
            UdpMsg(turnTransportAddress, buffer.array())
        )
    }

    override fun setRealm(realm: String) {
        turnRequest.setRealm(realm)
    }

    override fun setNonce(nonce: String) {
        turnRequest.setNonce(nonce)
    }

    override fun receive(cb: TurnOperations.Callback?) {
        this.callback = cb
    }

    private fun TurnMessage.sendOnSocket() {
        socket.send(
            UdpMsg(turnTransportAddress, encodeToByteArray())
        )
    }

    private fun handleAllocateResponse(msg: TurnMessage): Allocation {
        if (msg.msgClass == MessageClass.SUCCESS_RESPONSE) {
            val attrs = msg.attributes

            val relayedAddr = attrs.find { it.type == AttributeType.XOR_RELAYED_ADDRESS.type }
                ?.getValueAsAddress()
                ?.xorAddress()
                ?: throw MissingAttributeException(AttributeType.XOR_RELAYED_ADDRESS.name)

            val xorMappedAddr = attrs.find { it.type == AttributeType.XOR_MAPPED_ADDRESS.type }
                ?.getValueAsAddress()
                ?.xorAddress()
                ?: throw MissingAttributeException(AttributeType.XOR_MAPPED_ADDRESS.name)

            val lifetime = attrs.find { it.type == AttributeType.LIFETIME.type }
                ?.getValueAsInt()
                ?: throw MissingAttributeException(AttributeType.LIFETIME.name)

            val candidates = listOf(
                ICECandidate(
                    ip = InetAddress.getByAddress(relayedAddr.address).hostAddress,
                    port = relayedAddr.port, type = ICECandidate.CandidateType.RELAY,
                    protocol = TransportProtocol.UDP, priority = 3
                ),
                ICECandidate(
                    ip = InetAddress.getByAddress(xorMappedAddr.address).hostAddress,
                    port = xorMappedAddr.port, type = ICECandidate.CandidateType.SRFLX,
                    protocol = TransportProtocol.UDP, priority = 2
                )
            )
            return Allocation(lifetime.seconds, candidates)
        } else {
            throw parseError(msg)
        }
    }

    private fun parseError(msg: TurnMessage): TurnRequestFailure {
        val error = msg.attributes.find { it.type == AttributeType.ERROR_CODE.type }
            ?.getAsErrorValue()
            ?: throw MissingAttributeException(AttributeType.ERROR_CODE.name)

        val turnException = when (error.code) {
            400 -> BadRequestException()
            401 -> {
                val nonce = msg.attributes.find { it.type == AttributeType.NONCE.type }
                    ?.getValueAsString()
                    ?: throw MissingAttributeException(AttributeType.NONCE.name)

                val realm = msg.attributes.find { it.type == AttributeType.REALM.type }
                    ?.getValueAsString()
                    ?: throw MissingAttributeException(AttributeType.REALM.name)

                UnauthorizedException(realm, nonce)
            }

            438 -> {
                val nonce = msg.attributes.find { it.type == AttributeType.NONCE.type }
                    ?.getValueAsString()
                    ?: throw MissingAttributeException(AttributeType.NONCE.name)

                StaleNonceException(nonce)
            }

            437 -> AllocationMismatchException(error.reason)
            441 -> WrongCredException(error.reason)
            else -> TurnRequestFailure.OtherReason(error.code, error.reason)
        }

        return turnException
    }

    private fun registerMsgTx(tx: MsgTx) = synchronized(this) {
        turnMsgTxRegister[tx.txId] = tx
    }

    private fun unregisterMsgTx(tx: MsgTx) = synchronized(this) {
        turnMsgTxRegister.remove(tx.txId)
    }

    data class MsgTx(val txId: MessageHeader.TransactionId) :
        CompletableDeferred<TurnMessage> by CompletableDeferred()
}

data class TurnConfig(
    val username: String,
    val password: String,
    val ip: String, val port: Int
)

data class TurnConfiguration(
    var username: String,
    var password: String,
    var localHostIp: String = "0.0.0.0",
    var localPort: Int? = null,
    var turnServerHost: String,
    var turnServerPort: Int,
)
