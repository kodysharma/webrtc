package desidev.p2p.agent

import desidev.p2p.BaseMessage
import desidev.p2p.ExpireTimer
import desidev.p2p.ExpireTimerImpl
import desidev.p2p.MessageClass
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.time.Duration

internal abstract class PeerConnectionImpl(
    override val peerAddress: InetSocketAddress,
    override var active: Boolean,
    override val agent: P2PAgent,
    val connectionId: String = UUID.randomUUID().toString(),
    pingInterval: Duration,
    peerReconnectTime: Duration
) : PeerConnection {

    val pingInterval: ExpireTimer = ExpireTimerImpl(pingInterval)
    val peerReconnectTime: ExpireTimer = ExpireTimerImpl(peerReconnectTime)

    @Volatile
    var isClosed: Boolean = false
    private var _callback: PeerConnection.Callback? = null
    override fun setCallback(callback: PeerConnection.Callback) {
        this._callback = callback
    }


    override fun send(data: ByteArray, reliable: Boolean) {

    }


    fun onConnectionClosed() {
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

    fun receive(data: BaseMessage) {
        check(data.class_ == MessageClass.data) {
            "InvalidArgument! Expected class: ${MessageClass.data.name}"
        }
        if (data.isReliable) {

        }
    }

    protected abstract fun onSend(bytes: BaseMessage)
}


