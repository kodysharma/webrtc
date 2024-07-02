package desidev.p2p

import desidev.p2p.agent.PeerConnection
import desidev.p2p.agent.P2PAgent
import desidev.p2p.agent.P2PAgent.Config
import desidev.p2p.turn.TurnConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import kotlin.test.Test

class P2PAgentTest {
    private val logger = P2PAgentTest::class.simpleName!!.let { KotlinLogging.logger(it) }
    private val username = "test"
    private val password = "test123"
    private val turnConfig = TurnConfiguration(
        username, password, turnServerHost = "64.23.160.217", turnServerPort = 3478
    )

    private val agent1 = P2PAgent(Config(turnConfig = turnConfig))
    private val agent2 = P2PAgent(Config(turnConfig = turnConfig))

    @Test
    fun agent_test(): Unit = runBlocking(Dispatchers.IO) {
        val deferred1 = CompletableDeferred<List<ICECandidate>>()
        val deferred2 = CompletableDeferred<List<ICECandidate>>()

        agent1.setCallback(object : P2PAgent.Callback {
            override fun onNetworkConfigUpdate(ice: List<ICECandidate>) {
                logger.debug { "net config update: $ice" }
                deferred1.complete(ice)
            }

            override fun onConnectionInactive(id: String) {
                logger.debug { "on connection Inactive" }
            }

            override fun onConnectionActive(id: String) {
                logger.debug { "on connection active" }
            }

            override fun onConnectionRemoved(id: String) {
                logger.debug { "on connection removed" }
            }

            override fun onError(e: Throwable) {
                logger.error(e.message, e)
            }
        })
        agent2.setCallback(object : P2PAgent.Callback {
            override fun onNetworkConfigUpdate(ice: List<ICECandidate>) {
                logger.debug { "net config update: $ice" }
                deferred2.complete(ice)
            }

            override fun onConnectionInactive(id: String) {
                logger.debug { "on connection Inactive" }
            }

            override fun onConnectionActive(id: String) {
                logger.debug { "on connection active" }
            }

            override fun onConnectionRemoved(id: String) {
                logger.debug { "on connection removed" }
            }

            override fun onError(e: Throwable) {
                logger.error(e.message, e)
            }
        })

        launch {
            agent1.openConnection(deferred2.await()).let {
                it.setCallback(object : PeerConnection.Callback {
                    override fun onConnectionClosed() {
                        logger.debug { "agent1: on Connection closed" }
                    }

                    override fun onConnectionActive() {
                    }

                    override fun onReceive(data: ByteArray) {
                    }

                    override fun onConnectionInactive() {
                    }

                    override fun onReceiveReliable(data: ByteArray) {
                    }
                })
            }

            logger.debug { "agent 1: connection open " }
        }
        launch {
            val connection = agent2.openConnection(deferred1.await()).apply {
                setCallback(object : PeerConnection.Callback {
                    override fun onConnectionClosed() {
                        logger.debug { "agent2: on Connection closed" }
                    }

                    override fun onConnectionActive() {
                    }

                    override fun onReceive(data: ByteArray) {
                    }

                    override fun onConnectionInactive() {
                    }

                    override fun onReceiveReliable(data: ByteArray) {
                    }
                })
            }
            logger.debug { "agent 2: connection open " }

            delay(1000)
            connection.close()
        }
        delay(10 * 60 * 1000)
    }
}