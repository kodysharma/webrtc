package desidev.p2p

import desidev.p2p.TurnRequestFailure.UnauthorizedException
import desidev.p2p.turn.TurnConfig
import desidev.p2p.turn.TurnOperationsImpl
import desidev.p2p.turn.attribute.AddressValue
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test

class TurnOperationsTest {
    private val username = "test"
    private val password = "test123"
    private val config = TurnConfig(username, password, "64.23.160.217", 3478)

    private val turnOperations = let {
        val socket = UdpSocket(null, 9999)
        TurnOperationsImpl(socket, config)
    }


    @Test
    fun createAllocation_Test() {
        runBlocking {
            val ops = turnOperations
            val alloc = try {
                ops.allocate()
            } catch (e: UnauthorizedException) {
                ops.setNonce(e.nonce)
                ops.setRealm(e.realm)
                ops.allocate()
            }


            println("allocation: $alloc ")
        }
    }

    @Test
    fun refresh_Allocation_Test() {
        val ops = turnOperations
        runBlocking {
            try {
                ops.refresh()
            } catch (e: UnauthorizedException) {
                ops.setNonce(e.nonce)
                ops.setRealm(e.realm)
                ops.refresh()
            }
            println("Request Refresh SuccessFully!")
        }
    }

    @Test
    fun create_Permission_Test(): Unit = runBlocking {
        val ops = turnOperations
        val channelNumber = 0x4000
        val peer = InetSocketAddress("192.168.0.103", 44900)

        try {
            ops.createPermission(channelNumber, AddressValue.from(peer))
        } catch (e: UnauthorizedException) {
            ops.setNonce(e.nonce)
            ops.setRealm(e.realm)
            ops.createPermission(channelNumber, AddressValue.from(peer))
        }
    }

    @Test
    fun clear_Allocation_Test(): Unit = runBlocking {
        val ops = turnOperations
        try {
            ops.clear()
        } catch (e: UnauthorizedException) {
            ops.setNonce(e.nonce)
            ops.setRealm(e.realm)
            ops.clear()
        }
    }
}