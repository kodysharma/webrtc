package desidev.p2p.agent

import java.net.InetSocketAddress

interface PeerConnection {
    val active: Boolean
    val peerAddress: InetSocketAddress
    val agent: P2PAgent
    fun setCallback(callback: Callback)
    suspend fun close()
    fun send(data: ByteArray, reliable: Boolean)
    interface Callback {
        fun onReceive(data: ByteArray)
        fun onReceiveReliable(data: ByteArray)
        fun onConnectionInactive()
        fun onConnectionActive()
        fun onConnectionClosed()
    }
}