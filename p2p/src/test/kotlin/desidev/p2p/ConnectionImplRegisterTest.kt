package desidev.p2p

/*
class ConnectionImplRegisterTest {
    @Test
    fun test_function() {
        val register = PeerConnectionRegister()
        val connection1 = ConnectionImpl(
            "peer1", InetSocketAddress("localhost", 8080),
            ExpireTimerImpl(1000.seconds)
        )
        val connection2 =
            ConnectionImpl(
                "peer2", InetSocketAddress("localhost", 8081), ExpireTimerImpl(
                    2000
                        .seconds
                )
            )

        register.add(connection1)
        register.add(connection2)

        println(register.getByPeerId("peer1")) // Output: connection1
        println(
            register.getByPeerAddress(InetSocketAddress("localhost", 8081))
        ) // Output: connection2
        println(register.containsByPeerId("peer2")) // Output: true
        println(
            register.containsByPeerAddress(InetSocketAddress("localhost", 8080))
        ) // Output: true

        register.forEach { println(it) } // Output: connection1, connection2

        register.removeByPeerId("peer1")
        println(register.containsByPeerId("peer1")) // Output: false

        register.clear()
        println(register.containsByPeerId("peer2")) // Output: false
    }
}*/
