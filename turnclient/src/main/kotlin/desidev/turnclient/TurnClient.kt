package desidev.turnclient

import arrow.core.Either
import arrow.core.raise.either
import desidev.turnclient.SocketFailure.PortIsNotAvailable
import desidev.turnclient.TurnRequestFailure.AllocationMismatchException
import desidev.turnclient.TurnRequestFailure.BadRequestException
import desidev.turnclient.TurnRequestFailure.MissingAttributeException
import desidev.turnclient.TurnRequestFailure.RequestTimeoutException
import desidev.turnclient.TurnRequestFailure.StaleNonceException
import desidev.turnclient.TurnRequestFailure.UnauthorizedException
import desidev.turnclient.TurnRequestFailure.WrongCredException
import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.message.MessageClass
import desidev.turnclient.message.MessageHeader
import desidev.turnclient.message.MessageType
import desidev.turnclient.message.TurnMessage
import desidev.turnclient.message.TurnRequestBuilder
import desidev.turnclient.util.toHexString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

private const val LIFETIME_SEC = 600

internal interface TurnOperations
{
    suspend fun allocate(): Allocation
    suspend fun refresh()
    suspend fun clear()
    suspend fun createPermission(
        channelNumber: Int, peerAddress: AddressValue
    )

    suspend fun send(msg: ByteArray, channelNumber: Int)
    fun setRealm(realm: String)
    fun setNonce(nonce: String)
    fun setCallback(cb: Callback?)
    fun interface Callback
    {
        fun onReceive(channelNumber: Int, msg: ByteArray)
    }
}


interface TurnSocket
{
    suspend fun allocate()
    fun getIce(): List<ICECandidate>

    /**
     * Create Peer Permission via channel binding.
     * You can start communicating after this call.
     */
    suspend fun addPeer(peer: InetSocketAddress)
    fun send(message: ByteArray, peer: InetSocketAddress)
    fun removePeer()
    fun isAllocationExist(): Boolean
    fun addListener(listener: Listener)
    suspend fun close()
    fun interface Listener
    {
        fun onReceive(message: ByteArray, peer: InetSocketAddress)
    }
}

class TurnSocketConfigParameters(
    var username: String,
    var password: String,
    var localHostIp: String = "0.0.0.0",
    var localPort: Int? = null,
    var turnServerHost: String,
    var turnServerPort: Int,
)


/**
 * @throws PortIsNotAvailable: When you chose a port which is not available or is out of range.
 * @throws SecurityException: if a security manager exists and its checkListen method doesn't allow the operation.
 */
fun TurnSocket(
    configParameters: TurnSocketConfigParameters
): Either<Exception, TurnSocket> = either()
{
    val socket = UdpSocket(configParameters.localHostIp, configParameters.localPort)
    val turnConfig = TurnConfig(
        username = configParameters.username,
        password = configParameters.password,
        ip = configParameters.turnServerHost,
        port = configParameters.turnServerPort,
    )

    object : TurnSocket
    {
        private var allocation: Allocation? = null
        private val operations = TurnOperationsImpl(socket, turnConfig)

        override suspend fun allocate()
        {
            allocation = try
            {
                operations.allocate()
            }
            catch (e: UnauthorizedException)
            {
                operations.setRealm(e.realm)
                operations.setNonce(e.nonce)
                // retry
                operations.allocate()
            }
        }
    }
}


internal class TurnOperationsImpl(
    private val socket: UdpSocket,
    turnConfig: TurnConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TurnOperations
{
    private var turnRequest = TurnRequestBuilder()
        .setUsername(turnConfig.username)
        .setPassword(turnConfig.password)

    private val turnTransportAddress = TransportAddress(turnConfig.ip, turnConfig.port)

    private var callback: TurnOperations.Callback? = null

    private val turnMsgTxRegister = mutableMapOf<MessageHeader.TransactionId, MsgTx>()

    // dispatches incoming udp messages
    private val udpMsgCallback = MessageObserver.MsgCallback { udpMsg ->
        val byteArray = udpMsg.bytes
        val channelNumber = (byteArray[0].toInt() shl 8) or byteArray[1].toInt()
        if (channelNumber in 0x4000..0x7FFF)
        {
            callback?.onReceive(channelNumber, byteArray)
        }
        val turnMsg = TurnMessage.parse(byteArray)
        val msgTx = turnMsgTxRegister[turnMsg.txId]
        msgTx?.complete(turnMsg)
            ?: println("TurnMsg Response Discarded with id ${
                turnMsg.txId.bytes
                    .toHexString()
            }")
    }

    init
    {
        // listen incoming message on socket
        socket.addCallback(udpMsgCallback)
    }


    /**
     * @throws TurnRequestFailure
     * @throws RequestTimeoutException If the request is not complete within the time.
     */
    override suspend fun allocate(): Allocation
    {
        val request = turnRequest.setMessageType(MessageType.ALLOCATE_REQUEST)
            .setLifetime(LIFETIME_SEC)
            .build()

        val response = sendTurnRequest(request)
        return handleAllocateResponse(response)
    }

    override suspend fun refresh()
    {
        val request = turnRequest
            .setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
            .setLifetime(LIFETIME_SEC)
            .build()

        val response = sendTurnRequest(request)
        if (response.msgClass != MessageClass.SUCCESS_RESPONSE)
        {
            throw parseError(response)
        }
    }


    /**
     * @throws RequestTimeoutException If the request is not complete within the time.
     */
    private suspend fun sendTurnRequest(msg: TurnMessage): TurnMessage
    {
        val msgTx = MsgTx(msg.txId)
        registerMsgTx(msgTx)
        msg.sendOnSocket()

        println("TURN REQUEST \n$msg\n")

        return try
        {
            withTimeout(5000) {
                msgTx.await()
            }
        }
        catch (e: TimeoutCancellationException)
        {
            throw RequestTimeoutException()
        }
        finally
        {
            unregisterMsgTx(msgTx)
        }.also {
            println("TURN RESPONSE \n$it\n")
        }
    }

    override suspend fun clear()
    {
        val request = turnRequest.setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
            .setLifetime(0)
            .build()

        val response = sendTurnRequest(request)
        if (response.msgClass == MessageClass.ERROR_RESPONSE)
        {
            throw parseError(response)
        }
    }

    override suspend fun createPermission(
        channelNumber: Int,
        peerAddress: AddressValue
    )
    {
        val request = turnRequest.setMessageType(MessageType.CHANNEL_BIND_REQ)
            .setChannelNumber(channelNumber)
            .setPeerAddress(peerAddress)
            .build()

        val response = sendTurnRequest(request)
        if (response.msgClass == MessageClass.ERROR_RESPONSE)
        {
            throw parseError(response)
        }
    }

    override suspend fun send(msg: ByteArray, channelNumber: Int)
    {
        val channelData = ByteBuffer.allocate(4 + msg.size)
        channelData.putShort(channelNumber.toShort()) // 2 bytes channel number
        channelData.putShort(msg.size.toShort()) // 2 bytes data length
        channelData.put(msg) // application data

        socket.send(UdpMsg(turnTransportAddress, channelData.array()))
    }

    override fun setRealm(realm: String)
    {
        turnRequest.setRealm(realm)
    }

    override fun setNonce(nonce: String)
    {
        turnRequest.setNonce(nonce)
    }

    override fun setCallback(cb: TurnOperations.Callback?)
    {
        this.callback = cb
    }


    private suspend fun TurnMessage.sendOnSocket()
    {
        withContext(ioDispatcher) {
            socket.send(UdpMsg(turnTransportAddress, encodeToByteArray()))
        }
    }

    private fun handleAllocateResponse(msg: TurnMessage): Allocation
    {
        if (msg.msgClass == MessageClass.SUCCESS_RESPONSE)
        {
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
        } else
        {
            throw parseError(msg)
        }
    }


    private fun parseError(msg: TurnMessage): TurnRequestFailure
    {
        val error = msg.attributes.find { it.type == AttributeType.ERROR_CODE.type }
            ?.getAsErrorValue()
            ?: throw MissingAttributeException(AttributeType.ERROR_CODE.name)

        val turnException = when (error.code)
        {
            400 -> BadRequestException()
            401 ->
            {
                val nonce = msg.attributes.find { it.type == AttributeType.NONCE.type }
                    ?.getValueAsString()
                    ?: throw MissingAttributeException(AttributeType.NONCE.name)

                val realm = msg.attributes.find { it.type == AttributeType.REALM.type }
                    ?.getValueAsString()
                    ?: throw MissingAttributeException(AttributeType.REALM.name)

                UnauthorizedException(realm, nonce)
            }

            438 ->
            {
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
    val username: String, val password: String, val ip: String, val port: Int
)