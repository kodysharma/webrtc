package desidev.p2p.agent

import desidev.p2p.AgentMessage
import desidev.p2p.ExpireTimer
import desidev.p2p.ExpireTimerImpl
import desidev.p2p.MessageClass
import desidev.p2p.MessageType
import desidev.p2p.agentMessage
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.time.Duration

fun interface SendFunction {
    fun send(data: AgentMessage)
}

internal abstract class ConnectionImpl(
    override val peerAddress: InetSocketAddress,
    override var active: Boolean,
    override val agent: P2PAgent,
    val sendFunction: SendFunction,
    pingInterval: Duration,
    peerReconnectTime: Duration,
    val connectionId: String = UUID.randomUUID().toString()
) : Connection {

    val pingInterval: ExpireTimer = ExpireTimerImpl(pingInterval)
    val peerReconnectTime: ExpireTimer = ExpireTimerImpl(peerReconnectTime)

    @Volatile
    var isClosed: Boolean = false
    private var _callback: Connection.Callback? = null
    override fun setCallback(callback: Connection.Callback) {
        this._callback = callback
    }

    override fun send(data: ByteArray, reliable: Boolean) {

    }

    fun onConnectionClosed() {
        _callback?.onConnectionClosed()
    }

    fun makeInActive() {
        if (!isClosed && active) {
            active = false
            _callback?.onConnectionInactive()
            peerReconnectTime.resetExpireTime()
        }
    }

    fun makeActive() {
        pingInterval.resetExpireTime()
        if (!isClosed && !active) {
            active = true
            _callback?.onConnectionActive()
        }
    }

    fun receive(data: AgentMessage) {
        require(data.class_ == MessageClass.communication) {
            "Message class: ${
                MessageClass
                    .communication.name
            } " + "was expected. "
        }

        if (data.type == MessageType.reliable) {
            sendFunction.send(agentMessage {
                class_ = MessageClass.communication
                type = MessageType.relConfirm
                seqId = data.seqId
            })



        } else if (data.type == MessageType.relConfirm) {

        } else if (data.type == MessageType.nonReliable) {

        }
    }
}


