package desidev.p2p.agent

import java.net.InetSocketAddress

interface PeerConnection {
    val active: Boolean
    val peerAddress: InetSocketAddress
    val agent: P2PAgent
    val relStream: Stream
    val stream: Stream
    fun setCallback(callback: Callback)
    suspend fun close()
    interface Callback {
        fun onConnectionInactive()
        fun onConnectionActive()
        fun onConnectionClosed()
    }
}