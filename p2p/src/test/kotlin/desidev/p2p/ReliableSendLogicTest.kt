package desidev.p2p

import desidev.p2p.agent.ReliableSendLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ReliableSendLogicTest {
    val logger = KotlinLogging.logger { ReliableSendLogicTest::class.simpleName }
    val scope = CoroutineScope(Dispatchers.IO)

    @Test
    fun send_Logic_Test() {
        val timer = ExpireTimerImpl(10.seconds)

        val sender = object : ReliableSendLogic<String>() {
            override fun onSend(seqNumber: Long, data: String) {
                // simulate send and receive acknowledgement
                scope.launch {
                    delay(600)
                    receiveAcknowledgement(seqNumber)
                }
            }
        }

        runBlocking {
            while (!timer.isExpired()) {
                try {
                    sender.send(('a'..'z').random().toString())
                } catch (e: DataSendBlockedException) {
                    logger.error {
                        "Sending is blocked please wait: average send time ${sender.avgRTT}"
                    }
                    delay(100)
                }
            }
        }

    }
}