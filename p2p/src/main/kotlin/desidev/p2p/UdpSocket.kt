package desidev.p2p

import desidev.p2p.SocketFailure.PortIsNotAvailable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.random.Random

const val MAX_TRANSACTION_UNIT_SIZE = 1500

/**
 * Observe UDP Incoming Message interface
 */
interface MessageObserver {
    fun addCallback(cb: MsgCallback)
    fun removeCallback(cb: MsgCallback)
    fun interface MsgCallback {
        fun onMsgReceived(msg: UdpMsg)
    }
}

/**
 * A wrapper interface for java [DatagramSocket]
 * It makes easy to send and receive data
 */
interface UdpSocket : MessageObserver {
    fun send(msg: UdpMsg)
    fun close()
    fun isClose(): Boolean
}


/**
 * Builder Function for [UdpSocket]
 * @param host : Optional local ip address  for this socket by default it is "0.0.0.0"
 * @param port: Optional local port for this socket by default any port value would be select.
 *
 *
 * @throws PortIsNotAvailable: When you chose a port which is not available or is out of range.
 * @throws SecurityException: if a security manager exists and its checkListen method doesn't allow the operation.
 */
fun UdpSocket(host: String?, port: Int?): UdpSocket {
    if (port != null && !isUdpPortAvailable(port)) {
        throw PortIsNotAvailable(port)
    }

    val lHost = host ?: "0.0.0.0"
    val lPort = port ?: getRandomAvailableUDPPort()
    val socket = DatagramSocket(InetSocketAddress(lHost, lPort))

    val listenerThread = MessageObserverThread(socket).apply { start() }
    return object : UdpSocket, MessageObserver by listenerThread {
        private var isClosed = false
        private var packet = let {
            val buffer = ByteArray(MAX_TRANSACTION_UNIT_SIZE)
            DatagramPacket(buffer, 0, buffer.size)
        }

        private var buffer = ByteBuffer.wrap(packet.data)


        /**
         * @throws MtuSizeExceed : When the data size is bigger than the Mtu (Max transaction
         * unit) limit.
         *
         * @throws IllegalStateException: If the socket is closed.
         */
        override fun send(msg: UdpMsg) {
            check(!isClosed) { "Socket is closed" }

            val (destIp, destPort) = msg.ipPort
            if (msg.bytes.size > buffer.capacity()) {
                throw MtuSizeExceed(msg.bytes.size, buffer.capacity())
            }

            buffer.put(msg.bytes)
            buffer.flip()

            packet.socketAddress = InetSocketAddress(destIp, destPort)
            packet.length = buffer.limit()

            try {
                socket.send(packet)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            buffer.clear()
        }

        override fun close() {
            socket.close()
            isClosed = true
        }

        override fun isClose() = isClosed
    }
}

/**
 * While the socket is open this thread continues to receive messages.
 */
private class MessageObserverThread(private val socket: DatagramSocket) : Thread(),
    MessageObserver {
    private var msgCallbacks = mutableListOf<MessageObserver.MsgCallback>()
    override fun run() {
        listenOnSocket()
    }

    private fun listenOnSocket() {
        val packet = let {
            val buffer = ByteArray(1500)
            DatagramPacket(buffer, 0, buffer.size)
        }

        while (!socket.isClosed) {
            try {
                socket.receive(packet)
                val udpMsg = with(packet) {
                    UdpMsg(
                        ipPort = TransportAddress(address.hostAddress, port),
                        bytes = data.copyOfRange(offset, length)
                    )
                }
                synchronized(this) {
                    msgCallbacks.toList().forEach {
                        it.onMsgReceived(udpMsg)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        msgCallbacks.clear()
    }

    override fun addCallback(cb: MessageObserver.MsgCallback) {
        synchronized(this) {
            msgCallbacks.add(cb)
        }
    }

    override fun removeCallback(cb: MessageObserver.MsgCallback) {
        synchronized(this) {
            msgCallbacks.remove(cb)
        }
    }
}

data class UdpMsg(
    val ipPort: TransportAddress, val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UdpMsg
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }
}

data class TransportAddress(val ip: String, val port: Int)

fun getRandomAvailableUDPPort(): Int {
    val lowerBound = 49152
    val upperBound = 65535
    var port: Int

    while (true) {
        port = Random.nextInt(lowerBound, upperBound + 1)
        if (isUdpPortAvailable(port)) {
            return port
        }
    }
}

/**
 * @return Weather the port is not in range or it is not available.
 */
fun isUdpPortAvailable(port: Int): Boolean {
    return try {
        DatagramSocket(port).use { socket ->
            socket.reuseAddress = true
            true
        }
    } catch (e: Exception) {
        false
    }
}
