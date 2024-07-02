package desidev.p2p.agent

import java.net.InetSocketAddress

internal class PeerConnectionRegister {
    private val peerById = mutableMapOf<String, PeerConnectionImpl>()
    private val peerByAddress = mutableMapOf<InetSocketAddress, PeerConnectionImpl>()
    private val lock = Any()
    fun add(peerConnection: PeerConnectionImpl) {
        synchronized(lock) {
            peerById[peerConnection.connectionId] = peerConnection
            peerByAddress[peerConnection.peerAddress] = peerConnection
        }
    }

    fun getByPeerId(peerId: String): PeerConnectionImpl? {
        synchronized(lock) {
            return peerById[peerId]
        }
    }

    fun getByPeerAddress(peerAddress: InetSocketAddress): PeerConnectionImpl? {
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

    fun removeByPeerId(peerId: String): PeerConnectionImpl? {
        synchronized(lock) {
            val peerConnection = peerById.remove(peerId)
            if (peerConnection != null) {
                peerByAddress.remove(peerConnection.peerAddress)
            }
            return peerConnection
        }
    }

    fun removeByPeerAddress(peerAddress: InetSocketAddress): PeerConnectionImpl? {
        synchronized(lock) {
            val peerConnection = peerByAddress.remove(peerAddress)
            if (peerConnection != null) {
                peerById.remove(peerConnection.connectionId)
            }
            return peerConnection
        }
    }

    fun forEach(action: (PeerConnectionImpl) -> Unit) {
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
