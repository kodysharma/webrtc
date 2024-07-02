package desidev.p2p.agent

import com.google.protobuf.InvalidProtocolBufferException
import desidev.p2p.BaseMessage
import desidev.p2p.ICECandidate
import desidev.p2p.MessageClass
import desidev.p2p.MessageType
import desidev.p2p.NetworkStatus
import desidev.p2p.TurnRequestFailure.AllocationMismatchException
import desidev.p2p.baseMessage
import desidev.p2p.copy
import desidev.p2p.turn.TurnConfiguration
import desidev.p2p.turn.TurnSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * ## p2pa is responsible for -
 *
 * - knowing its ice candidate using stun/turn server etc.
 *
 * - Sense peer connectivity using ping / pong. If a peer does not give pong response the connection
 *   may be considered
 *   as temporary closed connection and [P2PAgent.Callback.onConnectionInactive] callback will be called. You may
 *   remove
 *   that peer or P2PAgent will start waiting for the peer to comeback when that peer comeback you
 *   would be
 *   notify with [P2PAgent.Callback.onConnectionActive] callback. If the peer does not comeback within a certain
 *   time
 *   span then p2pa will remove the connection with that peer and a notification is given to you with
 *   [P2PAgent.Callback.onConnectionRemoved] callback.
 *
 * - Reconfigure network ice candidate on network interface change.
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
    suspend fun openConnection(peerIce: List<ICECandidate>): PeerConnection
    fun setCallback(callback: Callback?)
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
        fun onError(e: Throwable)
    }

    class Config(
        val pingInterval: Duration = 10.seconds,
        val pingTimeout: Duration = 5.seconds,
        val peerReconnectTimeout: Duration = 30.seconds,
        val turnConfig: TurnConfiguration
    )

}


/**
 * P2PAgent builder function which creates the implementation of p2p agent interface.
 * @param config: p2p agent configuration parameters.
 */
fun P2PAgent(
    config: P2PAgent.Config,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): P2PAgent {
    return object : P2PAgent {
        val logger = KotlinLogging.logger { P2PAgent::class.simpleName }
        private val scope = CoroutineScope(dispatcher)
        private var turnSocket = TurnSocket(config.turnConfig)
        private var callback: P2PAgent.Callback? = null
        private val connections = ConcurrentHashMap<InetSocketAddress, PeerConnectionImpl>()
        private val responseCallbacks = MappedCallbacks<String, BaseMessage>()

        init {
            listenNetState()
            listenTurnSocket()
            pingTask()
            if (NetworkStatus.isNetworkAvailable) {
                discoverIce()
            }
        }

        private fun listenNetState() {
            NetworkStatus.addCallback(object : NetworkStatus.Callback {
                override fun onNetworkReachable() {
                    discoverIce()
                }

                override fun onNetworkUnreachable() {
                    connections.forEach { (_, con) ->
                        con.makeInActive()
                    }
                }
            })
        }

        private fun listenTurnSocket() {
            turnSocket.addCallback { data, peer ->
                val msg = try {
                    BaseMessage.parseFrom(data)
                } catch (e: InvalidProtocolBufferException) {
                    logger.error(e.message, e)
                    return@addCallback
                }
                when (msg.class_) {
                    MessageClass.request -> {
                        when (msg.type) {
                            MessageType.open -> {
                                scope.launch {
                                    turnSocket.send(msg.copy {
                                        class_ = MessageClass.response
                                        accepted = true
                                    }.toByteArray(), peer)
                                }
                            }

                            MessageType.close -> {
                                connections[peer]?.let {
                                    scope.launch {
                                        turnSocket.send(msg.copy {
                                            class_ = MessageClass.response
                                        }.toByteArray(), peer)
                                    }
                                    connections.remove(it.peerAddress)
                                    it.onConnectionClosed()
                                }
                            }

                            MessageType.ping -> {
                                connections[peer]?.let {
                                    it.makeActive()
                                    scope.launch {
                                        turnSocket.send(msg.copy {
                                            class_ = MessageClass.response
                                        }.toByteArray(), peer)
                                    }
                                }
                            }

                            else -> {
                                // ignore
                            }
                        }
                    }

                    MessageClass.response -> {
                        responseCallbacks.getListener(msg.txId)?.onReceive(msg)
                    }

                    MessageClass.data -> {
                        connections[peer]?.let {
                            it.makeActive()
                            it.receive(msg)
                        }
                    }

                    else -> {}
                }
            }
        }

        private suspend fun openConnection(peer: InetSocketAddress) {
            val uid = UUID.randomUUID().toString()
            val message = baseMessage {
                class_ = MessageClass.request
                type = MessageType.open
                txId = uid
            }

            val deferred = CompletableDeferred<BaseMessage>()
            responseCallbacks.register(uid) { deferred.complete(it) }
            turnSocket.send(message.toByteArray(), peer)

            var tries = 0
            val maxTries = 3

            try {
                while (tries < maxTries) {
                    try {
                        val response = withTimeout(4.seconds) {
                            deferred.await()
                        }
                        assert(response.class_ == MessageClass.response)
                        assert(response.type == MessageType.open)

                        if (!response.accepted) {
                            throw Status.ConnectionRejected()
                        }
                        return
                    } catch (e: TimeoutCancellationException) {
                        logger.debug { "connection open timeout retrying.." }
                        tries++
                    }
                }
                throw Status.PeerUnreachable()
            } finally {
                responseCallbacks.unregister(uid)
            }
        }

        private fun discoverIce() {
            if (turnSocket.isAllocationExist()) {
                scope.launch {
                    try {
                        turnSocket.refresh()

                    } catch (e: AllocationMismatchException) {
                        logger.debug { "Network changed!" }

                        turnSocket.close()
                        turnSocket = TurnSocket(config.turnConfig)
                        discoverIce()
                    }
                }
            } else {
                scope.launch {
                    try {
                        turnSocket.allocate()
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                    callback?.onNetworkConfigUpdate(turnSocket.getIce())
                }
            }
        }

        private fun pingTask() {
            scope.launch {
                while (isActive) {
                    connections.forEach { (_, con) ->
                        if (con.active && con.pingInterval.isExpired()) {
                            con.ping()
                        } else if (!con.active && con.peerReconnectTime.isExpired()) {
                            connections.remove(con.peerAddress)
                            con.onConnectionClosed()
                        }
                    }
                    delay(1.seconds)
                }
            }
        }

        private fun PeerConnectionImpl.ping() {
            scope.launch {
                try {
                    sendP2pRequest(baseMessage {
                        class_ = MessageClass.request
                        type = MessageType.ping
                        txId = UUID.randomUUID().toString()
                    })

                    pingInterval.resetExpireTime()
                } catch (e: Status.PeerUnreachable) {
                    logger.error {
                        "peer/${peerAddress} is unreachable, making" +
                                "this connection inactive."
                    }
                    makeInActive()
                } catch (e: Status.NetworkUnreachable) {
                    logger.error("PING failed", e)
                }
            }
        }

        private suspend fun PeerConnectionImpl.sendP2pRequest(request: BaseMessage): BaseMessage {
            val deferred = DeferredObject<BaseMessage>()
            responseCallbacks.register(request.txId, deferred)
            val maxTries = 2
            var tries = 0

            try {
                while (tries < maxTries && NetworkStatus.isNetworkAvailable) {
                    turnSocket.send(request.toByteArray(), peerAddress)
                    try {
                        return withTimeout(4000) {
                            deferred.await()
                        }
                    } catch (e: TimeoutCancellationException) {
                        tries++
                    }
                }
                throw if (NetworkStatus.isNetworkAvailable) Status.PeerUnreachable() else Status
                    .NetworkUnreachable()
            } finally {
                responseCallbacks.unregister(request.txId)
            }
        }

        override suspend fun openConnection(peerIce: List<ICECandidate>): PeerConnection {
            val peerAddress = peerIce.find { it.type == ICECandidate.CandidateType.RELAY }!!.let {
                InetSocketAddress(it.ip, it.port)
            }
            turnSocket.createPermission(peerAddress)
            openConnection(peerAddress)

            return PeerConnection(
                peerAddress,
                UUID.randomUUID().toString()
            ).apply {
                makeActive()
            }
        }

        override fun close() {
            val deferred = connections.map {
                scope.async {
                    try {
                        it.value.close()
                    } catch (e: Status) {
                        logger.error(
                            "connection closed without ack! remote: ${it.value.peerAddress}"
                        )
                    }
                }
            }

            scope.launch {
                turnSocket.close()
                deferred.awaitAll()
                scope.cancel()
                logger.debug { "P2p agent is closed!" }
            }
        }

        override fun setCallback(callback: P2PAgent.Callback?) {
            this.callback = callback
        }


        private inner class PeerConnection(
            peerAddress: InetSocketAddress, connectionId: String
        ) : PeerConnectionImpl(
            pingInterval = config.pingInterval,
            peerReconnectTime = config.peerReconnectTimeout,
            peerAddress = peerAddress,
            connectionId = connectionId,
            active = false,
            agent = this
        ) {
            override fun onSend(bytes: BaseMessage) {
                scope.launch {
                    turnSocket.send(bytes.toByteArray(), peerAddress)
                }
            }

            override suspend fun close() {
                try {
                    sendP2pRequest(baseMessage {
                        txId = UUID.randomUUID().toString()
                        class_ = MessageClass.request
                        type = MessageType.close
                    })
                } catch (e: Status.PeerUnreachable) {
                    logger.error { "closing connection without ack! remote: $peerAddress" }
                }
            }
        }
    }
}


fun interface CallbackFunction<T : Any> {
    fun onReceive(obj: T)
}

class DeferredObject<T : Any> : CompletableDeferred<T> by CompletableDeferred(),
    CallbackFunction<T> {
    override fun onReceive(obj: T) {
        complete(obj)
    }
}

class MappedCallbacks<T : Any, C : Any> {
    private val map = mutableMapOf<T, CallbackFunction<C>>()
    fun register(id: T, cb: CallbackFunction<C>) = synchronized(this) {
        map[id] = cb
    }

    fun unregister(id: T) = synchronized(this) {
        map.remove(id)
    }

    fun getListener(id: T) = synchronized(this) { map[id] }
}


sealed class Status : RuntimeException() {
    class PeerUnreachable() : Status()
    class ConnectionRejected() : Status()
    class NetworkUnreachable() : Status()
}