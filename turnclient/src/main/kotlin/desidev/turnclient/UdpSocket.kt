package desidev.turnclient

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer


interface IncomingMsgObserver {
    fun addCallback(cb: MsgCallback)
    fun remoteCallback(cb: MsgCallback)
    fun interface MsgCallback {
        fun onMsgReceived(msg: UdpMsg)
    }
}

/**
 * A wrapper interface for java [DatagramSocket]
 */
interface UdpSocket : IncomingMsgObserver {
    fun send(msg: UdpMsg)
    fun close()
    fun isClose(): Boolean

    companion object {
        fun createInstance(host: String, port: Int) = UdpSocket(host, port)
    }
}


/**
 * Builder function for UdpSocket
 */
fun UdpSocket(host: String, port: Int): UdpSocket {
    val socket = DatagramSocket(InetSocketAddress(host, port))
    val listenerThread = SocketListenerThread(socket).apply { start() }

    return object : UdpSocket, IncomingMsgObserver by listenerThread {
        private var isClosed = false
        private var packet = DatagramPacket(ByteArray(1500), 0, 1500)
        private var buffer = ByteBuffer.wrap(packet.data)

        override fun send(msg: UdpMsg) {
            val (destIp, destPort) = msg.ipPort
            assert(msg.bytes.size <= buffer.capacity()) {
                "Msg contain bigger data ${msg.bytes.size} bytes > ${buffer.capacity()} bytes"
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
private class SocketListenerThread(private val socket: DatagramSocket) : Thread(),
    IncomingMsgObserver {

    private var msgCallbacks = mutableListOf<IncomingMsgObserver.MsgCallback>()
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
    override fun addCallback(cb: IncomingMsgObserver.MsgCallback) {
        synchronized(this) {
            msgCallbacks.add(cb)
        }
    }
    override fun remoteCallback(cb: IncomingMsgObserver.MsgCallback) {
        synchronized(this) {
            msgCallbacks.remove(cb)
        }
    }
}


data class UdpMsg(
    val ipPort: TransportAddress,
    val bytes: ByteArray
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
