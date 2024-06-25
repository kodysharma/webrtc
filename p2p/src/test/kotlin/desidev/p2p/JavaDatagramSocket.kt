package desidev.p2p

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException


class JavaDatagramSocket {
    val socket = UdpSocket("0.0.0.0", 9999)
}

private fun isReachable(host: String, openPort: Int, timeOutMillis: Int): Boolean {
    try {
        Socket().use { soc ->
            soc.connect(InetSocketAddress(host, openPort), timeOutMillis)
        }
        return true
    } catch (ex: IOException) {
        return false
    }
}

fun isUdpPortReachable(ip: String, port: Int, timeout: Int): Boolean {
    try {
        val inetAddress = InetAddress.getByName(ip)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeout
            // Send a dummy packet
            val sendData = "ping".toByteArray()
            val sendPacket = DatagramPacket(
                sendData,
                sendData.size,
                inetAddress,
                port
            )
            socket.send(sendPacket)

            // Receive response
            val receiveData = ByteArray(1024)
            val receivePacket = DatagramPacket(
                receiveData,
                receiveData.size
            )
            socket.receive(receivePacket)
            return true
        }
    } catch (e: SocketTimeoutException) {
        println("UDP port $port on IP $ip is not reachable (timeout).")
        return false
    } catch (e: IOException) {
        println("UDP port " + port + " on IP " + ip + " is not reachable (" + e.message + ").")
        return false
    }
}