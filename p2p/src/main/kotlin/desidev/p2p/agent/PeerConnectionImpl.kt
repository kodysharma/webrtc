package desidev.p2p.agent

import desidev.p2p.BaseMessage
import desidev.p2p.ExpireTimer
import desidev.p2p.ExpireTimerImpl
import desidev.p2p.MessageClass
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.time.Duration

internal class PeerConnectionImpl(
    override val peerAddress: InetSocketAddress,
    override var active: Boolean,
    override val agent: P2PAgent,
    val connectionId: String = UUID.randomUUID().toString(),
    pingInterval: Duration,
    peerReconnectTime: Duration,
    private val onClose: suspend (PeerConnection) -> Unit,
    private val onSend: (BaseMessage) -> Unit
) : PeerConnection {

    val pingInterval: ExpireTimer = ExpireTimerImpl(pingInterval)
    val peerReconnectTime: ExpireTimer = ExpireTimerImpl(peerReconnectTime)
    private val transportReceiveListener = mutableListOf<TransportReceiveListener>()

    private val transport = object : Transport {
        override fun send(baseMessage: BaseMessage) {
            onSend(baseMessage)
        }

        override fun receive(listener: TransportReceiveListener) {
            transportReceiveListener.add(listener)
        }
    }

    override val relStream: Stream = PeerStream(
        reliable = true,
        transport = transport
    )

    override val stream: Stream = PeerStream(
        reliable = false,
        transport = transport
    )

    @Volatile
    var isClosed: Boolean = false
    private var _callback: PeerConnection.Callback? = null
    override fun setCallback(callback: PeerConnection.Callback) {
        this._callback = callback
    }

    override suspend fun close() {
        onClose(this)
        isClosed = true
        relStream.close()
        stream.close()
        notifyCloseEvent()
    }

    fun onReceive(data: BaseMessage) {
        check(data.class_ == MessageClass.data) {
            "InvalidArgument! Expected class: ${MessageClass.data.name}"
        }

        if (data.isReliable) {
            transportReceiveListener.find { it.isReliable }?.block?.invoke(data)
        } else {
            transportReceiveListener.find { !it.isReliable }?.block?.invoke(data)
        }
    }

    fun notifyCloseEvent() {
        _callback?.onConnectionClosed()
    }

    internal fun makeInActive() {
        if (!isClosed && active) {
            active = false
            _callback?.onConnectionInactive()
            peerReconnectTime.resetExpireTime()
        }
    }

    internal fun makeActive() {
        pingInterval.resetExpireTime()
        if (!isClosed && !active) {
            active = true
            _callback?.onConnectionActive()
        }
    }
}


