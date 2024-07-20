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
    suspend fun openConnection(socketAddress: InetSocketAddress): PeerConnection
    fun setCallback(callback: Callback?)
    interface Callback {
        /**
         * Notification when P2PA discovered its ice (Local, public transport address).
         * This could be shared with other peer with whom you can start communication. This could
         * be done via a signaling server.
         */
        fun onNetworkConfigUpdate(ice: List<ICECandidate>)
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
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        }

        private fun listenNetState() {
            NetworkStatus.addCallback(object : NetworkStatus.Callback {
                override fun onNetworkReachable() {
                    try {
                        discoverIce()
                        logger.debug { "onNetwork reachable" }
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
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
                            MessageType.open -> handleOpenRequest(peer, msg)
                            MessageType.close -> handleCloseRequest(peer, msg)
                            MessageType.ping -> handlePing(peer, msg)
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
                            it.onReceive(msg)
                        }
                    }

                    else -> { /* ignore */
                    }
                }
            }
        }

        private fun handlePing(peer: InetSocketAddress, msg: BaseMessage) {
            connections[peer]?.let {
                it.makeActive()
                scope.launch {
                    turnSocket.send(msg.copy {
                        class_ = MessageClass.response
                    }.toByteArray(), peer)
                }
            }
        }

        private fun handleOpenRequest(peer: InetSocketAddress, msg: BaseMessage) {
            scope.launch {
                turnSocket.send(
                    msg.copy {
                        class_ = MessageClass.response
                        accepted = true
                    }.toByteArray(),
                    peer
                )
            }
        }

        private fun handleCloseRequest(peer: InetSocketAddress, msg: BaseMessage) {
            connections[peer]?.let {
                connections.remove(it.peerAddress)
                it.notifyCloseEvent()
            }
            scope.launch {
                turnSocket.send(msg.copy {
                    class_ = MessageClass.response
                }.toByteArray(), peer)
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
                        callback?.onNetworkConfigUpdate(turnSocket.getIce())

                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
        }

        private fun pingTask() {
            scope.launch {
                while (isActive) {
                    connections.values.filter { it.active && it.pingInterval.isExpired() }.map {
                        scope.async { it.ping() }
                    }.awaitAll()

                    connections.values.removeIf {
                        if (it.active.not() && it.peerReconnectTime.isExpired()) {
                            it.notifyCloseEvent()
                            true
                        } else {
                            false
                        }
                    }

                    delay(1.seconds)
                }
            }
        }

        private suspend fun PeerConnectionImpl.ping() {
            try {
                sendP2pRequest(baseMessage {
                    type = MessageType.ping
                    class_ = MessageClass.request
                    txId = UUID.randomUUID().toString()
                }, this)

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

        @Throws(Status::class)
        private suspend fun sendP2pRequest(
            request: BaseMessage, connection: PeerConnection
        ): BaseMessage {
            val deferred = AsyncDeferred<BaseMessage>()
            responseCallbacks.register(request.txId, deferred)
            val maxTries = 2
            var tries = 0

            try {
                while (tries < maxTries && NetworkStatus.isNetworkAvailable) {
                    turnSocket.send(request.toByteArray(), connection.peerAddress)
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

        override suspend fun openConnection(
            socketAddress: InetSocketAddress
        ): PeerConnection {
            turnSocket.createPermission(socketAddress)

            val peerConnection = PeerConnectionImpl(
                peerAddress = socketAddress,
                active = false,
                agent = this,
                connectionId = UUID.randomUUID().toString(),
                pingInterval = config.pingInterval,
                peerReconnectTime = config.peerReconnectTimeout,
                onClose = ::onClose,
                onSend = { onSend(it, socketAddress) }
            )

            sendP2pRequest(baseMessage {
                class_ = MessageClass.request
                type = MessageType.open
                txId = UUID.randomUUID().toString()
            }, peerConnection)

            connections[socketAddress] = peerConnection
            peerConnection.makeActive()

            return peerConnection
        }


        private fun onSend(msg: BaseMessage, peerAddress: InetSocketAddress) {
            turnSocket.send(msg.toByteArray(), peerAddress)
        }

        private suspend fun onClose(peerConnection: PeerConnection) {
            try {
                sendP2pRequest(baseMessage {
                    class_ = MessageClass.request
                    type = MessageType.close
                    txId = UUID.randomUUID().toString()
                }, peerConnection)
            } catch (e: Status) {
                logger.error(e) { "connection closed improperly" }
            }
            connections.remove(peerConnection.peerAddress)
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
    }
}


fun interface CallbackFunction<T : Any> {
    fun onReceive(obj: T)
}

class AsyncDeferred<T : Any> : CompletableDeferred<T> by CompletableDeferred(),
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