package desidev.turnclient

import desidev.turnclient.message.TurnMessage
import desidev.turnclient.message.MessageType
import desidev.turnclient.message.TurnRequestBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class TurnRequest {

    private val username = "test"
    private val password = "test123"

    var realm: String? = null
    var nonce: String? = null

    val allocateRequest =
        TurnRequestBuilder().setUsername(username).setPassword(password).setLifetime(600)

    val refreshRequest = TurnRequestBuilder()
        .setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
        .setUsername(username)
        .setPassword(password)
        .setLifetime(0)

    val turnTransport = TransportAddress("64.23.160.217", 3478)
/*
    val socket = UdpSocket("0.0.0.0", 47633).unwrap()

    @Test
    fun socketTest(): Unit = runBlocking {
        val printMsgCallback = IncomingMsgObserver.MsgCallback { msg ->
            val turnMsg = Message.parse(msg.bytes)
            println(
                "msg received: ${msg.ipPort}" + "\ncontent: " + "\n$turnMsg\n"
            )
        }

        socket.addCallback(printMsgCallback)

        var allocate = allocateRequest.build()
        socket.sendRequest(allocate).let { msg ->
            nonce = msg.attributes.find { it.type == AttributeType.NONCE.type }?.getValueAsString()
            realm = msg.attributes.find { it.type == AttributeType.REALM.type }?.getValueAsString()
        }

        allocate = allocateRequest.setNonce(nonce).setRealm(realm).build()
        socket.sendRequest(allocate)
        delay(605.seconds)

        var refresh = refreshRequest
            .setRealm(realm)
            .setNonce(nonce)
            .setLifetime(600)
            .build()

        val response = socket.sendRequest(refresh)
        if (response.msgClass == MessageClass.ERROR_RESPONSE) {
            val errorCode = response.attributes.find { it.type == AttributeType.ERROR_CODE.type }
                ?.getValueAsInt() ?: throw RuntimeException("No error code given")
            when (errorCode) {
                438 -> {
                    val nonce = response.attributes.find { it.type == AttributeType.NONCE.type }
                        ?.getValueAsString() ?: throw RuntimeException("Nonce not given")
                    refreshRequest.setNonce(nonce)
                    socket.sendRequest(refreshRequest.build())
                }
            }
        }
    }


    @Test
    fun allocate(): Unit = runBlocking {
        val request = StunRequestBuilder()
            .setMessageType(MessageType.ALLOCATE_REQUEST)
            .setUsername(username)
            .setPassword(password)
            .setLifetime(600)
//            .setRealm("desidev.online")
            .setNonce("8527b3234298c1ff")

        socket.addCallback { msg ->
            val turnMsg = Message.parse(msg.bytes)
            println(
                "msg received: ${msg.ipPort}" + "\ncontent: " + "\n$turnMsg\n"
            )
        }

        socket.sendRequest(request.build()).let { msg ->
            if (msg.msgClass == MessageClass.ERROR_RESPONSE) {
                println("error response")
            }
        }
    }

    @Test
    fun refresh(): Unit = runBlocking {
        val request = StunRequestBuilder()
            .setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
            .setRealm("desidev.online")
            .setNonce("37b4879cf33730db")
            .setUsername(username)
            .setPassword(password)
            .setLifetime(0)

        socket.addCallback { msg ->
            val turnMsg = Message.parse(msg.bytes)
            println(
                "msg received: ${msg.ipPort}" + "\ncontent: " + "\n$turnMsg\n"
            )
        }

        socket.sendRequest(request.build())
    }

    @Test
    fun channelBinding() {
        val peerAddr = InetSocketAddress(InetAddress.getByName("192.168.0.109"), 44999)
        val request = StunRequestBuilder()
            .setMessageType(MessageType.CHANNEL_BIND_REQ)
            .setUsername(username)
            .setPassword(password)
            .setChannelNumber(0x4001)
            .setRealm("desidev.online")
            .setNonce("8527b3234298c1ff")
            .setPeerAddress(AddressValue.from(peerAddr.address, peerAddr.port))
            .build()

        runBlocking {
            socket.addCallback { msg ->
                val turnMsg = Message.parse(msg.bytes)
                println("msg received: ${msg.ipPort}" + "\ncontent: " + "\n$turnMsg\n")
            }
            socket.sendRequest(request)
        }
    }*/

    private suspend fun UdpSocket.sendRequest(turnMsg: TurnMessage): TurnMessage {
        return suspendCancellableCoroutine { cont ->
            val callback = object : MessageObserver.MsgCallback {
                override fun onMsgReceived(msg: UdpMsg) {
                    val response = TurnMessage.parse(msg.bytes)
                    if (response.header.txId == turnMsg.header.txId) {
                        cont.resume(response)
                        removeCallback(this)
                    }
                }
            }
            addCallback(callback)

            val msg = UdpMsg(turnTransport, turnMsg.encodeToByteArray())
            send(msg)

            println("sent: \n$turnMsg\n")
        }
    }
}