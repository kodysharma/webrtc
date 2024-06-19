package desidev.turnclient

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * ## p2pa is responsible for -
 *
 * - knowing its ice candidate using stun/turn server etc.
 * - Sense peer connectivity using ping / pong. If a peer does not give pong response the connection
 *   may be considered
 *   as temporary closed connection and onPeerConnectionBreak callback will be called. You may remove
 *   that peer or P2PAgent will start waiting for the peer to comeback when that peer comeback you
 *   would be
 *   notify with onPeerConnectionRestore callback. If the peer does not comeback within a certain time
 *   span then p2pa will remove the connection with that peer and a notification is given to you with
 *   onPeerConnectionRemoved callback.
 * - Reconfigure network ice candidate on network reset.
 * - It sense the device network availability.
 *
 * ## Time values
 *
 * - `ping-timeout`: The maximum time P2PA waits for a pong response from a peer. If the response is
 *   not received within this time, the connection is marked as temporarily closed.
 *
 * - `peer-reconnect-timeout`: The maximum time P2PA waits for a peer to reconnect after a connection
 *   break. If the peer does not reconnect within this period, the connection is permanently removed.
 *
 *  - `ping-interval`: The regular interval at which P2PA sends ping messages to peers to check
 *  their availability. This interval ensures periodic connectivity checks without overwhelming
 *  the network with too frequent pings.
 */
interface P2PAgent {
    fun close()
    suspend fun createConnection(id: String, peerIce: List<ICECandidate>)
    suspend fun closeConnection(id: String)
    fun send(data: ByteArray, id: String)
    interface Callback {
        /**
         * Notification when P2PA discovered its ice (Local, public transport address).
         * This could be shared with other peer with whom you can start communication. This could
         * be done via a signaling server.
         */
        fun onNetworkConfigUpdate(ice: List<ICECandidate>)

        /**
         * This callback function is invoked to notify the user that a connection with a peer has become inactive.
         * This occurs when P2PA has not received a pong response from the peer within the specified ping-timeout period.
         * The peer is considered temporarily unavailable, and the connection is marked as inactive.
         */
        fun onConnectionInactive(id: String)

        /**
         * This callback function is invoked to notify the user that a previously inactive peer
         * reconnected within the specified peer-reconnect-timeout period. The connection with the
         * peer is now considered active again.
         */
        fun onConnectionActive(id: String)

        /**
         * This callback function is invoked to notify the user that a peer has been removed from
         * the connection list. This can happen for several reasons:
         *
         * - The peer did not reconnect within the peer-reconnect-timeout period.
         * - The peer closed the connection itself.
         * - The P2PA user client intentionally removed the peer.
         */
        fun onConnectionRemoved(id: String)

        /**
         * This callback function is invoked to notify the user that data has been received from
         * a peer.
         * The received data is provided as a byte array, along with the unique identifier of the
         * peer that sent the data.
         */
        fun onReceive(data: ByteArray, id: String)
    }

    class Config(
        val pingInterval: Duration = 10.seconds,
        val pingTimeout: Duration = 5.seconds,
        val peerReconnectTimeout: Duration = 30.seconds
    )

}


/**
 * P2PAgent builder function which creates the implementation of p2p agent interface.
 * @param config: p2p agent configuration parameters.
 */
fun P2PAgent(config: P2PAgent.Config): P2PAgent {

    return object : P2PAgent {
        private var pingInterval = ExpireTimerImpl()

        override suspend fun createNetConfig() {
            TODO("Not yet implemented")
        }
    }
}