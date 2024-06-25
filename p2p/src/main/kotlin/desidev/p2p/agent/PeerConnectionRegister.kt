package desidev.p2p.agent

import java.net.InetSocketAddress

internal class PeerConnectionRegister {
    private val peerById = mutableMapOf<String, ConnectionImpl>()
    private val peerByAddress = mutableMapOf<InetSocketAddress, ConnectionImpl>()
    private val lock = Any()
    fun add(peerConnection: ConnectionImpl) {
        synchronized(lock) {
            peerById[peerConnection.connectionId] = peerConnection
            peerByAddress[peerConnection.peerAddress] = peerConnection
        }
    }

    fun getByPeerId(peerId: String): ConnectionImpl? {
        synchronized(lock) {
            return peerById[peerId]
        }
    }

    fun getByPeerAddress(peerAddress: InetSocketAddress): ConnectionImpl? {
        synchronized(lock) {
            return peerByAddress[peerAddress]
        }
    }

    fun containsByPeerId(peerId: String): Boolean {
        synchronized(lock) {
            return peerById.containsKey(peerId)
        }
    }

    fun containsByPeerAddress(peerAddress: InetSocketAddress): Boolean {
        synchronized(lock) {
            return peerByAddress.containsKey(peerAddress)
        }
    }

    fun removeByPeerId(peerId: String): ConnectionImpl? {
        synchronized(lock) {
            val peerConnection = peerById.remove(peerId)
            if (peerConnection != null) {
                peerByAddress.remove(peerConnection.peerAddress)
            }
            return peerConnection
        }
    }

    fun removeByPeerAddress(peerAddress: InetSocketAddress): ConnectionImpl? {
        synchronized(lock) {
            val peerConnection = peerByAddress.remove(peerAddress)
            if (peerConnection != null) {
                peerById.remove(peerConnection.connectionId)
            }
            return peerConnection
        }
    }

    fun forEach(action: (ConnectionImpl) -> Unit) {
        synchronized(lock) {
            peerById.values.forEach(action)
        }
    }

    fun clear() {
        synchronized(lock) {
            peerById.clear()
            peerByAddress.clear()
        }
    }
}
