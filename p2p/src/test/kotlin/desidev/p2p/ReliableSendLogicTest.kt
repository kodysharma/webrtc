package desidev.p2p

import com.google.protobuf.ByteString
import desidev.p2p.agent.ReliableSendLogic
import desidev.p2p.agent.SendLogic
import desidev.p2p.turn.TurnConfig
import desidev.p2p.turn.TurnOperationsImpl
import desidev.p2p.turn.attribute.AddressValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.time.Duration

class ReliableSendLogicTest {
    val logger = KotlinLogging.logger { ReliableSendLogicTest::class.simpleName }

    private val username = "test"
    private val password = "test123"
    private val config = TurnConfig(username, password, "64.23.160.217", 3478)

    private val ops1 = let {
        val socket = UdpSocket(null, null)
        TurnOperationsImpl(socket, config)
    }

    private val ops2 = let {
        val socket = UdpSocket(null, null)
        TurnOperationsImpl(socket, config)
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun ops_1_send(): Unit = runBlocking {
        var alloc1: ICECandidate
        var alloc2: ICECandidate

        val scope1 = CoroutineScope(newSingleThreadContext("1"))
        val scope2 = CoroutineScope(newSingleThreadContext("1"))

        while (true) {
            try {
                alloc1 = ops1.allocate().iceCandidates.find {
                    it.type == ICECandidate.CandidateType
                        .RELAY
                }!!
                break
            } catch (e: TurnRequestFailure.UnauthorizedException) {
                ops1.setRealm(e.realm)
                ops1.setNonce(e.nonce)
                logger.info { "unauthorized exception" }
            } catch (e: Exception) {
                e.printStackTrace()
                return@runBlocking
            }
        }

        while (true) {
            try {
                alloc2 = ops2.allocate().iceCandidates.find {
                    it.type == ICECandidate.CandidateType
                        .RELAY
                }!!
                break
            } catch (e: TurnRequestFailure.UnauthorizedException) {
                ops2.setRealm(e.realm)
                ops2.setNonce(e.nonce)
                logger.info { "unauthorized exception" }
            } catch (e: Exception) {
                e.printStackTrace()
                return@runBlocking
            }
        }


        ops1.createPermission(
            0x4000, AddressValue.Companion.from(
                InetSocketAddress(
                    alloc2.ip,
                    alloc2.port
                )
            )
        )

        ops2.createPermission(
            0x4001, AddressValue.Companion.from(
                InetSocketAddress(
                    alloc1.ip,
                    alloc1.port
                )
            )
        )
        ops2.setCallback { _, msg ->
            val aMsg = BaseMessage.parseFrom(msg)
            scope2.launch {
                val ack = baseMessage {
                    class_ = MessageClass.data
                    type = MessageType.ack
                    ackMillis = System.currentTimeMillis()
                    seqIds.add(aMsg.seqId)
                }
                ops2.send(listOf(ack.toByteArray()), 0x4001)
            }
        }

        val lineCount = 1
        val sendLines = object : SendLines<String>(lineCount) {
            override fun build(): SendLogic<String> {
                return object : ReliableSendLogic<String>() {
                    override fun onSend(list: List<Pair<String, Long>>) {
                        launch(Dispatchers.IO) {
                            val segments = list.map {
                                baseMessage {
                                    class_ = MessageClass.data
                                    type = MessageType.seq
                                    seqId = it.second
                                    isReliable = true
                                    bytes = ByteString.copyFromUtf8(it.first)
                                    bodyType = BodyType.complete
                                }.toByteArray()
                            }
                            ops1.send(segments, 0x4000)
                        }.start()
                    }

                    override fun sizeOfData(data: String): Long {
                        return data.encodeToByteArray().size.toLong()
                    }

                    override fun onDivide(data: String): List<String> {
                        TODO("Not yet implemented")
                    }
                }
            }
        }

        ops1.setCallback { _, msg ->
            val aMsg = BaseMessage.parseFrom(msg)
            aMsg.seqIdsList.forEach {
                val isAcked = sendLines.pushAckData(SendLogic.AckData(aMsg.ackMillis, it))

                /*                if (!isAcked) {
                    logger.info { "Ack failed: $it" }
                } else {
                    logger.info { "Ack : $it" }
                }*/
            }
        }

        scope1.launch {
            while (isActive) {
                try {
                    val data = randomData()
                    sendLines.push(data)

                } catch (e: LineBlockException) {
                }
            }
        }.join()
    }

    private fun randomData(): String {
        val data = ByteArray(1300)
        data.fill(('a'..'z').random().code.toByte())
        return data.decodeToString()
    }

    fun preciseDelay(delay: Duration) {
        val from = System.nanoTime()
        while (true) {
            val elapse = System.nanoTime() - from
            if (elapse >= delay.inWholeNanoseconds) break
        }
    }


    @Test
    fun acknowledgeSize() {
        val msg = baseMessage {
            class_ = MessageClass.data
            type = MessageType.ack
            val id = 990389058349018522L
            seqIds.add(id)
        }
        println(msg.toByteArray().size)
    }
}

abstract class SendLines<T : Any>(maxSize: Int) {
    private val pool = mutableListOf<SendLogic<T>>()

    init {
        repeat(maxSize) {
            pool.add(build())
        }
    }

    abstract fun build(): SendLogic<T>
    fun pushAckData(ackData: SendLogic.AckData): Boolean {
        for (line in pool) {
            if (line.ackData(ackData)) return true
        }
        return false
    }

    fun push(segment: T) {
        for (line in pool) {
            try {
                line.send(segment)
                return
            } catch (e: LineBlockException) {
                // ignore
            }
        }
        throw LineBlockException("All line is blocked!")
    }
}