package desidev.p2p

import desidev.p2p.turn.TurnConfiguration
import desidev.p2p.turn.TurnSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Date
import kotlin.test.Test

class TurnSocketTest {
    private val username = "test"
    private val password = "test123"
    private val config = TurnConfiguration(
        username, password, turnServerHost = "64.23.160.217", turnServerPort = 3478
    )
    private var socket = TurnSocket(config)
    private val networkStatus = NetworkStatus
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun test(): Unit = runBlocking {
        socket.allocate()
        println("Allocate at: ${Date()}")
        socket.addCallback { data, peer -> println("received:$data, from: $peer") }
        networkStatus.addCallback(object : NetworkStatus.Callback {
            override fun onNetworkReachable() {
                scope.launch {
                    println("New socket created!")
                    socket = TurnSocket(config)
                    socket.allocate()
                }
            }

            override fun onNetworkUnreachable() {
                socket.close()
            }
        })
    }
}