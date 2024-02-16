package desidev.turnclient

import com.shared.livebaat.turn.message.MessageClass
import com.shared.livebaat.turn.message.MessageType
import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.message.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TurnClient(
    serverAddress: SocketAddress,
    private val user: String,
    private val password: String
) {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val socket: DatagramSocket = DatagramSocket()
    private var realm: String? = null
    private var nonce: String? = null
    private var allocation: Allocation? = null

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
    suspend fun requestAllocation(): Result<Allocation> {
        val allocateRequest = Message.buildAllocateRequest(
            username = user, password = password, realm = realm, nonce = nonce
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
                    Result.success(extractAllocateResponse(resMsg).also { this.allocation = it })
                } else {
                    if (realm == null || nonce == null) {
                        realm = resMsg.attributes.find { it.type == AttributeType.REALM.type }
                            ?.getValueAsString()
                        nonce = resMsg.attributes.find { it.type == AttributeType.NONCE.type }
                            ?.getValueAsString()

                        if (realm != null && nonce != null) {
                            requestAllocation()
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
     * Create a Channel mapped to the address given.
     * @param address The address to which new channel is going to bind.
     * @return returns an implementation of ChannelBinding to send/receive the messages
     */
    @OptIn(ExperimentalStdlibApi::class)
    suspend fun createChannel(address: AddressValue): Result<ChannelBinding> {
        if (allocation == null) {
            throw IllegalStateException("Cannot add peer address before creating an allocation. Please create an allocation first.")
        }

        val channelNo = numberGenerator.getNumber()
        val channelBind =
            Message.buildChannelBind(channelNo, address, user, password, realm!!, nonce!!)

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

        if (response.msgClass == MessageClass.SUCCESS_RESPONSE) {
            return Result.success(getChannelBindingImpl(address, channelNo))
        }

        TODO()
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

    fun refresh() {
        TODO()
    }

    fun close() {
        inComingPacketHandler.cancel()
        inComingPacketHandler.join()
        numberGenerator.close()

        // TODO: destroy allocation on server
    }

    private fun getChannelBindingImpl(
        peerAddress: AddressValue,
        channelNumber: Int
    ): ChannelBinding {
        return object : ChannelBinding {
            override val peerAddress: AddressValue = peerAddress
            override val channelNumber: Int = channelNumber

            override fun sendMessage(bytes: ByteArray) {
                sendChannelData(channelNumber, bytes)
            }

            override fun receiveMessage(cb: (ByteArray) -> Unit) {
                inComingPacketHandler.dataMessageListener[channelNumber] = cb
            }

            fun sendChannelData(channelNo: Int, data: ByteArray) {
                val channelData = ByteBuffer.allocate(4 + data.size)
                channelData.putShort(channelNo.toShort()) // 2 bytes channel number
                channelData.putShort(data.size.toShort()) // 2 bytes data length
                channelData.put(data) // application data

                val packet = DatagramPacket(channelData.array(), channelData.capacity())
                socket.send(packet)
            }
        }
    }

    private fun extractAllocateResponse(response: Message): Allocation {
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

        return Allocation(
            relayedAddr.xorAddress(),
            xorMappedAddr.xorAddress(),
            lifetime,
            TransportProtocol.UDP
        )
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