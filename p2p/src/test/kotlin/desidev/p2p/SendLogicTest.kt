package desidev.p2p

import com.google.protobuf.ByteString
import desidev.p2p.agent.ReceiveLogic
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

class SendLogicTest {
    private val logger = KotlinLogging.logger { SendLogicTest::class.simpleName }

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

        val receiver = ReceiveLogic(
            isReliable = true,
            onSend = {
                ops2.send(listOf(it.toByteArray()), 0x4001)
            },
            onNext = {

            }
        )

        ops2.receive { _, msg ->
            val baseMessage = BaseMessage.parseFrom(msg)
            receiver.receive(
                ReceiveLogic.Segment(baseMessage.seqId, baseMessage.bytes.toByteArray())
            )
        }

        val sender = SendLogic(isReliable = true) {
            launch(Dispatchers.IO) {
                val bytes = baseMessage {
                    class_ = MessageClass.data
                    type = MessageType.seq
                    seqId = it.seqId
                    isReliable = true
                    bytes = ByteString.copyFrom(it.data)
                    bodyType = BodyType.complete
                }.toByteArray()
                ops1.send(listOf(bytes), 0x4000)
            }.start()
        }



        ops1.receive { _, bytes ->
            val baseMessage = BaseMessage.parseFrom(bytes)
            sender.ackData(baseMessage.seqId)
        }

        scope1.launch {
            while (isActive) {
                try {
                    val data = randomData()
                    sender.send(data.encodeToByteArray())
                } catch (e: LineBlockException) {
                    delay(100)
                }
            }
        }.join()
    }

    private fun randomData(): String {
        val data = ByteArray(1300)
        data.fill(('a'..'z').random().code.toByte())
        return data.decodeToString()
    }
}
