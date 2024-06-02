package desidev.turnclient

import desidev.turnclient.attribute.AddressValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TurnClientTest {
    private val username = "test"
    private val password = "test123"
    private val serverAddress = InetSocketAddress("64.23.160.217", 3478)

    @Test
    fun printLocalAddress() {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()

            if (!networkInterface.isUp) continue

            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address.isSiteLocalAddress && !address.isLoopbackAddress) {
                    println("Private IP Address: ${address.hostAddress}")
                }
            }
        }
    }

    @Test
    fun allocateTest(): Unit = runBlocking {
        val peerAddress = InetSocketAddress("192.168.127.12", 8551)
        val client = TurnClient(serverAddress, username, password)

        suspend fun allocate() {
            client.createAllocation().let {
                assert(it.isSuccess)
                println("allocate ice: ${it.getOrNull()}")
            }
        }

        allocate()
    }


    @Test
    fun chattingTest(): Unit = runBlocking(Dispatchers.IO) {
        val peer1Channel = Channel<ICECandidate>(Channel.CONFLATED)
        val peer2Channel = Channel<ICECandidate>(Channel.CONFLATED)
        val scope = CoroutineScope(Dispatchers.IO)

        val messageTemplate = listOf(
            "Hello",
            "How are you?",
            "I am fine",
            "I am fine too",
            "Bye",
            "Abhi ruko jara",
            "Bye bye",
            "Aur baat karte hain.",
            "Yeh fake message hain."
        )
        // peer1
        val job1 = scope.launch {
            val client = TurnClient(serverAddress, username, password)
            val result = client.createAllocation()
            if (result.isSuccess) {
                val relay = result.getOrThrow().find { it.type == ICECandidate.CandidateType.RELAY }
                peer2Channel.send(relay!!)
                println("peer1 send ice candidate to peer2")

                val peer2Ice = peer1Channel.receive()
                println("peer2 ice candidate: $peer2Ice")

                val peer2Address = InetAddress.getByName(peer2Ice.ip)
                val bindingResult =
                    client.bindChannel(AddressValue.from(peer2Address, peer2Ice.port))

                val dataChannel = bindingResult.getOrThrow()
                // callback function to receive messages from the remote peer
                dataChannel.setDataCallback(object : DataCallback {
                    override fun onReceived(data: ByteArray) {
                        println("Peer 1 Received: ${data.decodeToString()}")
                    }
                })

                while (isActive) {
                    val message = messageTemplate[(messageTemplate.indices).random()]
                    dataChannel.sendData(message.encodeToByteArray())
                    println("Peer 1 Send: $message")
                    delay((5..10).random().seconds)
                }
            }
        }

        // peer2
        val job2 = scope.launch {
            val client = TurnClient(serverAddress, username, password)
            val result = client.createAllocation()
            if (result.isSuccess) {
                val relay = result.getOrThrow().find { it.type == ICECandidate.CandidateType.RELAY }
                peer1Channel.send(relay!!)
                println("peer2 send ice candidate to peer1")

                val peer1Ice = peer2Channel.receive()

                println("peer1 ice candidate: $peer1Ice")

                val peer1Address = InetAddress.getByName(peer1Ice.ip)
                val bindingResult =
                    client.bindChannel(AddressValue.from(peer1Address, peer1Ice.port))

                val dataChannel = bindingResult.getOrThrow()
                // callback function to receive messages from the remote peer
                dataChannel.setDataCallback(object : DataCallback {
                    override fun onReceived(data: ByteArray) {
                        println("Peer 2 Received: ${data.decodeToString()}")
                    }
                })

                while (isActive) {
                    val message = messageTemplate[(messageTemplate.indices).random()]
                    dataChannel.sendData(message.encodeToByteArray())
                    println("Peer 2 Send: $message")
                    delay((5..10).random().seconds)
                }
            }
        }

        listOf(job1, job2).joinAll()
    }
}