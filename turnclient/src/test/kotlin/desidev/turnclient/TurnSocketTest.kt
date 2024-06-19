package desidev.turnclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Date
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class TurnSocketTest
{
    private val username = "test"
    private val password = "test123"
    private val config = TurnSocketConfigParameters(
        username, password, turnServerHost = "64.23.160.217", turnServerPort = 3478
    )
    private var socket = TurnSocket(config)
    private val networkStatus = NetworkStatus
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun test(): Unit = runBlocking {
        socket.allocate()
        println("Allocate at: ${Date()}")
        socket.addCallback(object : TurnSocket.Callback
        {
            override fun onReceive(data: ByteArray, peer: InetSocketAddress)
            {
                println("received:$data")
            }
        })

        networkStatus.addCallback(object : NetworkStatus.Callback
        {
            override fun onNetworkReachable()
            {
                scope.launch {
                    println("New socket created!")
                    socket = TurnSocket(config)
                    socket.allocate()
                }
            }

            override fun onNetworkUnreachable()
            {
                socket.close()
            }
        })
    }
}