package test.videocall

import android.util.Log
import desidev.p2p.ICECandidate
import desidev.p2p.agent.P2PAgent
import desidev.p2p.agent.P2PAgent.Config
import desidev.p2p.agent.PeerConnection
import desidev.p2p.turn.TurnConfiguration
import desidev.p2p.turn.TurnSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class P2PAgentTest {
    private val TAG = "P2pAgentTest"
    private val username = "test"
    private val password = "test123"
    private val turnConfig = TurnConfiguration(
        username, password, turnServerHost = "64.23.160.217", turnServerPort = 3478
    )

    private val agent1 = P2PAgent(Config(turnConfig = turnConfig))
    private val agent2 = P2PAgent(Config(turnConfig = turnConfig))

    private val turnSocket = TurnSocket(turnConfig)


    @Test
    fun createAllocation_Test() {
        runBlocking {
            turnSocket.allocate()
            println("allocation: ${turnSocket.getIce()} ")
        }
    }

    @Test
    fun agent_test(): Unit = runBlocking(Dispatchers.IO) {
        val deferred1 = CompletableDeferred<List<ICECandidate>>()
        val deferred2 = CompletableDeferred<List<ICECandidate>>()

        agent1.setCallback(object : P2PAgent.Callback {
            override fun onNetworkConfigUpdate(ice: List<ICECandidate>) {
                Log.d(TAG, "nect config update: $ice")
                deferred1.complete(ice)
            }

            override fun onError(e: Throwable) {
                Log.e(TAG, "", e)
            }
        })

        agent2.setCallback(object : P2PAgent.Callback {
            override fun onNetworkConfigUpdate(ice: List<ICECandidate>) {
                Log.d(TAG, "nect config update: $ice")
                deferred2.complete(ice)
            }

            override fun onError(e: Throwable) {
                Log.e(TAG, "", e)
            }
        })

     /*   launch {
            val conn = agent1.openConnection(deferred2.await())

            conn.setCallback(object : PeerConnection.Callback {
                override fun onConnectionClosed() {
                }

                override fun onConnectionActive() {

                }

                override fun onConnectionInactive() {

                }
            })

            conn.relStream.receive {
                Log.d(TAG, "agent 1 receive => ${it.decodeToString()}")
            }
        }

        launch {
            val connection = agent2.openConnection(deferred1.await()).apply {
                setCallback(object : PeerConnection.Callback {
                    override fun onConnectionClosed() {
                    }

                    override fun onConnectionActive() {
                    }

                    override fun onConnectionInactive() {
                    }
                })
            }

            connection.relStream.receive {
            }
        }

        delay(10 * 60 * 1000)*/
    }
}