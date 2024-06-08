package desidev.turnclient

import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.message.Message
import desidev.turnclient.message.MessageClass
import desidev.turnclient.message.MessageType
import desidev.turnclient.message.StunRequestBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class TurnRequest {

    private val username = "test"
    private val password = "test123"

    var realm: String? = null
    var nonce: String? = null

    val allocateRequest =
        StunRequestBuilder().setUsername(username).setPassword(password).setLifetime(600)

    val refreshRequest = StunRequestBuilder()
        .setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
        .setUsername(username)
        .setPassword(password)
        .setLifetime(0)

    val turnTransport = TransportAddress("64.23.160.217", 3478)

    val socket = UdpSocket.createInstance("0.0.0.0", 47633)

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
            .setRealm("desidev.online")
            .setNonce("b1ba702b306c016b")

        socket.addCallback { msg ->
            val turnMsg = Message.parse(msg.bytes)
            println(
                "msg received: ${msg.ipPort}" + "\ncontent: " + "\n$turnMsg\n"
            )
        }

        socket.sendRequest(request.build())
    }

    @Test
    fun refresh(): Unit = runBlocking {
        val request = StunRequestBuilder()
            .setMessageType(MessageType.ALLOCATE_REFRESH_REQUEST)
            .setRealm("desidev.online")
            .setNonce("b1ba702b306c016b")
            .setUsername(username)
            .setPassword(password)

        socket.addCallback { msg ->
            val turnMsg = Message.parse(msg.bytes)
            println(
                "msg received: ${msg.ipPort}" + "\ncontent: " + "\n$turnMsg\n"
            )
        }

        socket.sendRequest(request.build())
    }

    private suspend fun UdpSocket.sendRequest(turnMsg: Message): Message {
        return suspendCancellableCoroutine { cont ->
            val callback = object : IncomingMsgObserver.MsgCallback {
                override fun onMsgReceived(msg: UdpMsg) {
                    val response = Message.parse(msg.bytes)
                    if (response.header.txId == turnMsg.header.txId) {
                        cont.resume(response)
                        remoteCallback(this)
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