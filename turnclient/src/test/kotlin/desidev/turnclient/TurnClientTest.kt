package desidev.turnclient

import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.message.Message
import desidev.turnclient.message.MessageType
import desidev.turnclient.message.StunRequestBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TurnClientTest {
    private val username = "test"
    private val password = "test123"
    private val serverAddress = InetSocketAddress("64.23.160.217", 3478)

    @Test
    fun allocateTest(): Unit = runBlocking {
        val peerAddress = InetSocketAddress("192.168.127.12", 8551)
        val client = TurnClient(serverAddress, username, password)
        val result = client.createAllocation()

        println("first allocation: ")
        println("${result.getOrThrow()}")

        println("Creating bind channel...")
        client.createChannel(AddressValue.from(peerAddress.address, peerAddress.port)).let {
            assert(it.isSuccess)
        }

        println("Deleting allocation")
        client.deleteAllocation()

        println("second allocation: ")
        val secondAllocation = client.createAllocation()
        println("${secondAllocation.getOrThrow()}")

        println("Creating bind channel...")
        client.createChannel(AddressValue.from(peerAddress.address, peerAddress.port)).let {
            assert(it.isSuccess)
        }

        println("Deleting allocation")
        client.deleteAllocation()
    }


    @Test
    fun chattingTest(): Unit = runBlocking(Dispatchers.IO) {
        val peer1Channel = Channel<ICECandidate>(Channel.CONFLATED)
        val peer2Channel = Channel<ICECandidate>(Channel.CONFLATED)
        val scope = CoroutineScope(Dispatchers.IO)

        val messageTemplate = listOf<String>(
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
                val bindingResult = client.createChannel(AddressValue.from(peer2Address, peer2Ice.port))

                val dataChannel = bindingResult.getOrThrow()
                // callback function to receive messages from the remote peer
                dataChannel.receiveMessage { bytes ->
                    println("Peer 1 Received: ${bytes.decodeToString()}")
                }

                while (isActive) {
                    val message = messageTemplate[(messageTemplate.indices).random()]
                    dataChannel.sendMessage(message.encodeToByteArray())
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
                    client.createChannel(AddressValue.from(peer1Address, peer1Ice.port))

                val dataChannel = bindingResult.getOrThrow()
                // callback function to receive messages from the remote peer
                dataChannel.receiveMessage { bytes ->
                    println("Peer 2 Received: ${bytes.decodeToString()}")
                }

                while (isActive) {
                    val message = messageTemplate[(messageTemplate.indices).random()]
                    dataChannel.sendMessage(message.encodeToByteArray())
                    println("Peer 2 Send: $message")
                    delay((5..10).random().seconds)
                }
            }
        }

        listOf(job1, job2).joinAll()
    }


    @Test
    fun receivingMessageTest() : Unit = runBlocking(Dispatchers.IO) {
        val peerAddress = InetAddress.getByName("139.59.85.69")
        val peerAddressValue = AddressValue.from(peerAddress, 8851)
        val client = TurnClient(serverAddress, username, password)
        val result = client.createAllocation()
        val relay = result.getOrThrow().find { it.type == ICECandidate.CandidateType.RELAY }

        val channelBinding = client.createChannel(peerAddressValue).getOrThrow()
        channelBinding.receiveMessage { bytes ->
            println("Received: ${bytes.decodeToString()}")
        }

        println("relay: $relay")


        while (true) {
            delay(1.minutes)
        }
    }
}