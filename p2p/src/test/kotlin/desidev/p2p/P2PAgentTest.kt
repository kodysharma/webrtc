package desidev.p2p

import desidev.p2p.agent.P2PAgent
import desidev.p2p.agent.P2PAgent.Config
import desidev.p2p.agent.PeerConnection
import desidev.p2p.turn.TurnConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class P2PAgentTest {
    private val logger = P2PAgentTest::class.simpleName!!.let { KotlinLogging.logger(it) }
    private val username = "test"
    private val password = "test123"
    private val turnConfig = TurnConfiguration(
        username, password, turnServerHost = "64.23.160.217", turnServerPort = 3478
    )

    private val agent1 = P2PAgent(Config(turnConfig = turnConfig))
    private val agent2 = P2PAgent(Config(turnConfig = turnConfig))
    private val sampleData = ByteArray(5000)

    @Test
    fun agent_test(): Unit = runBlocking(Dispatchers.IO) {
        val deferred1 = CompletableDeferred<InetSocketAddress>()
        val deferred2 = CompletableDeferred<InetSocketAddress>()
        sampleData.fill(88)

        agent1.setCallback(object : P2PAgent.Callback {
            override fun onNetworkConfigUpdate(ice: List<ICECandidate>) {
                logger.debug { "net config update: $ice" }
                ice.find { it.type == ICECandidate.CandidateType.RELAY}?.let {
                    deferred1.complete(InetSocketAddress(it.ip, it.port))
                }
            }

            override fun onError(e: Throwable) {
                logger.error(e.message, e)
            }
        })

        agent2.setCallback(object : P2PAgent.Callback {
            override fun onNetworkConfigUpdate(ice: List<ICECandidate>) {
                logger.debug { "net config update: $ice" }
                ice.find { it.type == ICECandidate.CandidateType.RELAY}?.let {
                    deferred2.complete(InetSocketAddress(it.ip, it.port))
                }
            }

            override fun onError(e: Throwable) {
                logger.error(e.message, e)
            }
        })

        launch {
            val conn = agent1.openConnection(deferred2.await())
            conn.setCallback(object : PeerConnection.Callback {
                override fun onConnectionClosed() {
                    logger.debug { "agent1: on Connection closed" }
                }

                override fun onConnectionActive() {
                }

                override fun onConnectionInactive() {
                }
            })

            conn.stream.receive { data ->
                val isCorrupted = !data.contentEquals(sampleData)
                logger.debug { "corrupted: $isCorrupted" }
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

                    override fun onConnectionInactive() {
                    }
                })
            }

            logger.debug { "agent 2: connection open " }

            val timer = ExpireTimerImpl(10.seconds)

            while (timer.isExpired().not()) {
                try {
                    connection.stream.send(sampleData)
                } catch (e: LineBlockException) {
                    Thread.sleep(100)
                }
            }

            connection.close()
        }
    }
}