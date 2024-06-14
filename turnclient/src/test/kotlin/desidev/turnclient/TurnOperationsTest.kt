package desidev.turnclient

import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.recover
import desidev.turnclient.attribute.AddressValue
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test

class TurnOperationsTest {
    private val username = "test"
    private val password = "test123"
    private val config = TurnConfig(username, password, "64.23.160.217", 3478)

    private val turnOperations = either {
        val socket = UdpSocket(null, 9999).getOrElse { raise(it) }
        TurnOperationsImpl(socket, config)
    }

    @Test
    fun createAllocation_Test() {
        runBlocking {
            val ops = turnOperations.getOrElse {
                it.printStackTrace()
                return@runBlocking
            }

            val alloc = ops.allocate().recover { failure ->
                when (failure) {
                    is StaleNonceException -> {
                        ops.setNonce(failure.nonce)
                        ops.allocate().getOrElse { raise(it) }
                    }

                    is UnauthorizedException -> {
                        ops.apply {
                            setNonce(failure.nonce)
                            setRealm(failure.realm)
                        }
                        ops.allocate().getOrElse { raise(it) }
                    }

                    else -> raise(failure)
                }
            }

            alloc.fold(
                {
                    it.printStackTrace()
                    println("Allocation failed: ")
                },
                {
                    println("Allocation: $it")
                }
            )

        }
    }

    @Test
    fun refresh_Allocation_Test() {
        val ops = turnOperations.getOrElse {
            it.printStackTrace()
            return
        }

        runBlocking {
            ops.refresh().recover { failure ->
                when (failure) {
                    is StaleNonceException -> {
                        ops.setNonce(failure.nonce)
                        ops.refresh().bind()
                    }

                    is UnauthorizedException -> {
                        ops.setNonce(failure.nonce)
                        ops.setRealm(failure.realm)
                        ops.refresh().bind()
                    }

                    else -> raise(failure)
                }
            }.onLeft {
                it.printStackTrace()
            }.onRight {
                println("Refresh succeed")
            }
        }
    }

    @Test
    fun create_Permission_Test(): Unit = runBlocking {
        val ops = turnOperations.getOrElse {
            it.printStackTrace()
            return@runBlocking
        }

        val channelNumber = 0x4000
        val peer = InetSocketAddress("192.168.0.103", 44900)
        ops.createPermission(channelNumber, AddressValue.from(peer))
            .recover { failure ->
                when (failure) {
                    is UnauthorizedException -> {
                        ops.setNonce(failure.nonce)
                        ops.setRealm(failure.realm)
                        ops.createPermission(channelNumber, AddressValue.from(peer))
                    }

                    else -> raise(failure)
                }
            }
            .onLeft {
                it.printStackTrace()
            }
            .onRight {
                println("Request Succeed")
            }
    }

    @Test
    fun clear_Allocation_Test(): Unit = runBlocking {
        val ops = turnOperations.getOrElse {
            it.printStackTrace()
            return@runBlocking
        }

        ops.clear().recover { failure ->
            when (failure) {
                is UnauthorizedException -> {
                    ops.setNonce(failure.nonce)
                    ops.setRealm(failure.realm)
                    ops.clear()
                }

                else -> raise(failure)
            }
        }.onLeft {
            it.printStackTrace()
        }.onRight {
            println("Request success")
        }
    }
}