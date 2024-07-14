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

    @Test
    fun agent_test(): Unit = runBlocking(Dispatchers.IO) {
        val deferred1 = CompletableDeferred<List<ICECandidate>>()
        val deferred2 = CompletableDeferred<List<ICECandidate>>()

        agent1.setCallback(object : P2PAgent.Callback {
            override fun onNetworkConfigUpdate(ice: List<ICECandidate>) {
                logger.debug { "net config update: $ice" }
                deferred1.complete(ice)
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

            val timer = ExpireTimerImpl(5.seconds)


            while (timer.isExpired().not()) {
                try {
                    connection.relStream.send("rel".plus(randomText()).toByteArray())
                    connection.stream.send(randomText().encodeToByteArray())
                } catch (ex: LineBlockException) {
                    delay(100)
                }
            }

            connection.close()
        }
    }


    private fun randomText(): String {
        val byteArray = ByteArray(1300)
        byteArray.fill(('a'..'z').random().code.toByte())
        return byteArray.decodeToString()
    }
}