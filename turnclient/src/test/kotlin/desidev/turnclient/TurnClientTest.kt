package desidev.turnclient

import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test

class TurnClientTest {
    private val username = "test"
    private val password = "test123"
    private val serverAddress = InetSocketAddress("64.23.160.217", 3478)

    @Test
    fun allocateTest(): Unit = runBlocking {
        val client = TurnClient(serverAddress, username, password)
        val result = client.allocation()
        assert(result.isSuccess) { "Failed to allocate" }

        val allocation = result.getOrThrow()
        println("Allocation: $allocation")
        client.deAllocate()
    }

}