package desidev.turnclient

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.message.MessageClass
import desidev.turnclient.message.MessageHeader
import desidev.turnclient.message.MessageType
import desidev.turnclient.message.TurnMessage
import desidev.turnclient.message.TurnRequestBuilder
import desidev.turnclient.util.NumberSeqGenerator
import desidev.turnclient.util.toHexString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

private const val LIFETIME_SEC = 600

internal interface TurnOperations {
    @Throws(AllocationMismatchException::class)
    suspend fun allocate(): Either<TurnRequestFailure, Allocation>
    suspend fun refresh(): Either<TurnRequestFailure, Unit>
    suspend fun clear(): Either<TurnRequestFailure, Unit>
    suspend fun createPermission(
        channelNumber: Int, peerAddress: AddressValue
    ): Either<TurnRequestFailure, Unit>

    suspend fun send(msg: ByteArray, channelNumber: Int)
    fun setRealm(realm: String)
    fun setNonce(nonce: String)
    fun setCallback(cb: Callback?)
    interface Callback {
        fun onReceive(channelNumber: Int, msg: ByteArray)
    }
}


interface TurnSocket {
    suspend fun allocate(): Either<TurnRequestFailure, Unit>
    fun getIce(): List<ICECandidate>

    /**
     * Create Peer Permission via channel binding.
     * You can start communicating after this call.
     */
    suspend fun addPeer(peer: InetSocketAddress): Either<TurnRequestFailure, Unit>
    fun send(message: ByteArray, peer: InetSocketAddress)
    fun removePeer()
    fun isAllocationExist(): Boolean
    fun addListener(listener: Listener)
    suspend fun close()
    interface Listener {
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

fun TurnSocket(configParameters: TurnSocketConfigParameters): Either<Exception, TurnSocket> = either {
    val socket = UdpSocket(configParameters.localHostIp, configParameters.localPort).getOrElse { raise(it) }
    val turnConfig = TurnConfig(
        username = configParameters.username,
        password = configParameters.password,
        ip = configParameters.turnServerHost,
        port = configParameters.turnServerPort,
    )

    val operations = TurnOperationsImpl(socket, turnConfig)

    TODO()
}


internal class TurnOperationsImpl(
    private val socket: UdpSocket,
    turnConfig: TurnConfig
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
        if (channelNumber in 0x4000..0x7FFF) {
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

    init {
        // listen incoming message on socket
        socket.addCallback(udpMsgCallback)
    }

    override suspend fun allocate(): Either<TurnRequestFailure, Allocation> {
        val request = turnRequest.setMessageType(MessageType.ALLOCATE_REQUEST)
            .setLifetime(LIFETIME_SEC)
            .build()

        val response = sendTurnRequest(request).getOrElse { return it.left() }
        return handleAllocateResponse(response)
    }

    override suspend fun refresh(): Either<TurnRequestFailure, Unit> {
        val request = turnRequest
            .setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
            .setLifetime(LIFETIME_SEC)
            .build()

        return sendTurnRequest(request).flatMap {
            if (it.msgClass != MessageClass.SUCCESS_RESPONSE) parseError(it).left()
            else Unit.right()
        }
    }


    private suspend fun sendTurnRequest(
        msg: TurnMessage): Either<RequestTimeoutException, TurnMessage> {
        val msgTx = MsgTx(msg.txId)
        registerMsgTx(msgTx)
        msg.sendOnSocket()
        return Either.catch {
            withTimeout(5000) {
                msgTx.await()
            }
        }.mapLeft { RequestTimeoutException() }
            .also {
                unregisterMsgTx(msgTx)
            }
    }

    override suspend fun clear(): Either<TurnRequestFailure, Unit> {
        val request = turnRequest.setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
            .setLifetime(0)
            .build()

        return sendTurnRequest(request).flatMap {
            if (it.msgClass == MessageClass.SUCCESS_RESPONSE) Unit.right()
            else parseError(it).left()
        }
    }

    override suspend fun createPermission(
        channelNumber: Int,
        peerAddress: AddressValue
    ): Either<TurnRequestFailure, Unit> {
        val request = turnRequest.setMessageType(MessageType.CHANNEL_BIND_REQ)
            .setChannelNumber(channelNumber)
            .setPeerAddress(peerAddress)
            .build()

        return sendTurnRequest(request).flatMap {
            if (it.msgClass == MessageClass.SUCCESS_RESPONSE) Unit.right()
            else parseError(it).left()
        }
    }

    override suspend fun send(msg: ByteArray, channelNumber: Int) {
        val channelData = ByteBuffer.allocate(4 + msg.size)
        channelData.putShort(channelNumber.toShort()) // 2 bytes channel number
        channelData.putShort(msg.size.toShort()) // 2 bytes data length
        channelData.put(msg) // application data

        socket.send(UdpMsg(turnTransportAddress, channelData.array()))
    }

    override fun setRealm(realm: String) {
        turnRequest.setRealm(realm)
    }

    override fun setNonce(nonce: String) {
        turnRequest.setNonce(nonce)
    }

    override fun setCallback(cb: TurnOperations.Callback?) {
        this.callback = cb
    }


    private suspend fun TurnMessage.sendOnSocket() {
        withContext(Dispatchers.IO) {
            socket.send(UdpMsg(turnTransportAddress, encodeToByteArray()))
        }
    }

    private fun handleAllocateResponse(msg: TurnMessage): Either<TurnRequestFailure, Allocation> =
        either {
            if (msg.msgClass == MessageClass.SUCCESS_RESPONSE) {
                val attrs = msg.attributes
                val relayedAddr = attrs.find { it.type == AttributeType.XOR_RELAYED_ADDRESS.type }
                    ?.getValueAsAddress()
                    ?.xorAddress()
                    ?: raise(MissingAttributeException(AttributeType.XOR_RELAYED_ADDRESS.name))

                val xorMappedAddr = attrs.find { it.type == AttributeType.XOR_MAPPED_ADDRESS.type }
                    ?.getValueAsAddress()
                    ?.xorAddress()
                    ?: raise(MissingAttributeException(AttributeType.XOR_MAPPED_ADDRESS.name))

                val lifetime = attrs.find { it.type == AttributeType.LIFETIME.type }
                    ?.getValueAsInt()
                    ?: raise(MissingAttributeException(AttributeType.LIFETIME.name))

                val candidates = listOf(
                    ICECandidate(
                        ip = InetAddress.getByAddress(relayedAddr.address).hostAddress,
                        port = relayedAddr.port, type = ICECandidate.CandidateType.RELAY,
                        protocol = TransportProtocol.UDP, priority = 3
                    ), ICECandidate(
                        ip = InetAddress.getByAddress(xorMappedAddr.address).hostAddress,
                        port = xorMappedAddr.port, type = ICECandidate.CandidateType.SRFLX,
                        protocol = TransportProtocol.UDP, priority = 2
                    )
                )
                Allocation(lifetime.seconds, candidates)

            } else raise(parseError(msg))
        }

    private fun parseError(msg: TurnMessage): TurnRequestFailure {
        val error = msg.attributes.find { it.type == AttributeType.ERROR_CODE.type }
            ?.getAsErrorValue() ?: return MissingAttributeException(AttributeType
            .ERROR_CODE.name)

        val turnException = when (error.code) {
            400 -> BadRequestException()
            401 -> {
                val nonce = msg.attributes.find { it.type == AttributeType.NONCE.type }
                    ?.getValueAsString()
                    ?: return MissingAttributeException(AttributeType.NONCE.name)

                val realm = msg.attributes.find { it.type == AttributeType.REALM.type }
                    ?.getValueAsString()
                    ?: return MissingAttributeException(AttributeType.REALM.name)

                UnauthorizedException(realm, nonce)
            }

            438 -> {
                val nonce = msg.attributes.find { it.type == AttributeType.NONCE.type }
                    ?.getValueAsString()
                    ?: return MissingAttributeException(AttributeType.NONCE.name)

                StaleNonceException(nonce)
            }

            437 -> AllocationMismatchException(error.reason)
            441 -> WrongCredException(error.reason)
            else -> object : TurnRequestFailure() {
                override val message: String = "error code: ${error.code} reason: " + error.reason
            }
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