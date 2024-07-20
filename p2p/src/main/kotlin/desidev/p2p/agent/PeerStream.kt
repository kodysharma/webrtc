package desidev.p2p.agent


import arrow.atomic.AtomicLong
import com.google.protobuf.ByteString
import desidev.p2p.BaseMessage
import desidev.p2p.BodyType
import desidev.p2p.LineBlockException
import desidev.p2p.MessageClass
import desidev.p2p.MessageType
import desidev.p2p.baseMessage
import desidev.p2p.body
import desidev.p2p.util.preciseDelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.LinkedList
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong


data class TransportReceiveListener(val isReliable: Boolean, val block: (BaseMessage) -> Unit)

interface Stream {
    fun send(data: ByteArray)
    fun receive(block: (data: ByteArray) -> Unit)
    fun close()
}

interface Transport {
    fun send(baseMessage: BaseMessage)
    fun receive(listener: TransportReceiveListener)
}

class PeerStream(
    private val transport: Transport,
    reliable: Boolean
) : Stream {
    private var receiveCallback: ((ByteArray) -> Unit)? = null

    private val sendLogic = SendLogic(
        isReliable = reliable,
        delegateSend = { segment: SendLogic.Segment ->
            transport.send(baseMessage {
                class_ = MessageClass.data
                type = MessageType.seq
                isReliable = reliable
                seqId = segment.seqId
                body = body {
                    data = ByteString.copyFrom(segment.data)
                    bodyType = segment.bodyType
                }
            })
        })

    private val receiveLogic = ReceiveLogic(
        isReliable = reliable,
        onSend = transport::send,
        onNext = {
            receiveCallback?.invoke(it)
        }
    )

    init {
        transport.receive(TransportReceiveListener(reliable) {
            check(it.class_ == MessageClass.data)
            when (it.type) {
                MessageType.ack -> {
                    sendLogic.ackData(it.seqId)
                }

                MessageType.seq -> {
                    receiveLogic.receive(
                        ReceiveLogic.Segment(
                            seqId = it.seqId,
                            data = it.body.data.toByteArray(),
                            bodyType = it.body.bodyType
                        )
                    )
                }

                else -> {
                    // ignore
                }
            }
        })
    }

    override fun send(data: ByteArray) = sendLogic.send(data)

    override fun receive(block: (data: ByteArray) -> Unit) {
        receiveCallback = block
    }

    override fun close() {
        sendLogic.close()
    }
}


internal class SendLogic(
    private val isReliable: Boolean,
    private val delegateSend: (Segment) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    var avgRttMs = INITIAL_RESEND_INTERVAL_MS

    @Volatile
    var maxRttMs = Long.MIN_VALUE

    @Volatile
    var minRttMs = Long.MAX_VALUE

    @Volatile
    var avgLr: Double = 0.0

    private val rttHistory = LinkedList<Long>()
    private val lossHistory = LinkedList<Float>()

    private val comparable = compareBy<Segment> { it.seqId }
    private val dataQueue = PriorityBlockingQueue(1000, comparable)

    private val segmentTraceMap = ConcurrentHashMap<Long, Segment>()
    private var sendRate = MIN_RATE // segments/second
    private var mode: Mode = Mode.INITIAL_START
    private var power = 0
    private var nextSeq = AtomicLong(0L)


    init {
        senderJob()
        ackTracingJob()
    }

    @Throws(LineBlockException::class)
    fun send(data: ByteArray) {
        if (dataQueue.size >= sendRate) {
            throw LineBlockException("Line is blocked, due to buffer is full!")
        }

        if (dataQueue.isNotEmpty() && nextSeq.get() - dataQueue.first().seqId > 1000) {
            throw LineBlockException("Line is blocked, due to lowest unacknowledged segment!")
        }

        createSegments(data, 1300).forEach {
            dataQueue.add(it)
        }
    }

    private fun createSegments(data: ByteArray, mss: Int): List<Segment> = synchronized(this) {
        val segments = mutableListOf<Segment>()
        val totalSegments = (data.size + mss - 1) / mss  // Calculate the total number of segments

        for (i in 0 until totalSegments) {
            val start = i * mss
            val end = minOf(start + mss, data.size)
            val segmentData = data.copyOfRange(start, end)
            val bodyType = when (i) {
                0 -> if (totalSegments == 1) BodyType.complete else BodyType.partialStart
                totalSegments - 1 -> BodyType.partialEnd
                else -> BodyType.partialMiddle
            }
            segments.add(
                Segment(
                    seqId = nextSeq.getAndIncrement(),
                    sendTimeMs = 0,
                    data = segmentData,
                    bodyType = bodyType
                )
            )
        }

        return segments
    }

    private fun senderJob() = scope.launch {
        while (isActive) {
            while (dataQueue.isEmpty() && segmentTraceMap.isEmpty()) delay(100)

            while (dataQueue.isNotEmpty() && isActive) {
                val nanoseconds = 1000_000_000.0.div(sendRate).roundToLong()
                dataQueue.poll()?.let {
                    launch {
                        it.sendTimeMs = System.currentTimeMillis()
                        segmentTraceMap[it.seqId] = it
                        delegateSend(it)
                    }.start()
                    preciseDelay(nanoseconds)
                }
            }
        }
    }

    private fun ackTracingJob() = scope.launch {
        while (isActive) {
            val delayTime = 200f.div(sendRate).times(1000).roundToLong()
            if (delayTime > 0) {
                delay(delayTime)
            }

            while (segmentTraceMap.isEmpty()) delay(100)
            updateRate()
            collectCompleteOrFailedSegments().let { segments ->
                if (isReliable) {
                    val failed = segments.filter { it.ack.not() }
                    if (failed.isNotEmpty()) {
                        dataQueue.addAll(failed)
                    }
                }
            }
        }
    }

    private fun updateRate() {
        if (segmentTraceMap.isEmpty()) return

        val isNeedUp = (dataQueue.size.toFloat() / sendRate) > 0.60
        val segments = mutableListOf<Segment>()
        val currentTime = System.currentTimeMillis()
        segmentTraceMap.forEach { (_, segment) ->
            if (segment.sendTimeMs.plus(avgRttMs) < currentTime) {
                segments.add(segment)
            }
        }

        if (segments.isEmpty()) return

        val unack = segments.filter { !it.ack }
        val loss = unack.size.toFloat() / segments.size
        recordLossRate(loss)

        if (lossHistory.size >= 20) {
            if (avgLr > 0.04) {
                goDown()
                mode = Mode.MID_BY_MID
            } else if (loss <= 0.02 && isNeedUp) {
                goUp()
            }
            lossHistory.clear()
        }

        if (mode == Mode.INITIAL_START) {
            if (loss < 0.10 && isNeedUp) {
                goUp()
            } else {
                goDown()
                mode = Mode.MID_BY_MID
            }
        } else if (loss > 0.10) {
            goDown()
        }
    }

    private fun collectCompleteOrFailedSegments(): List<Segment> {

        val currentTime = System.currentTimeMillis()
        val segments = mutableListOf<Segment>()
        val ackTimeout = avgRttMs * 2

        segmentTraceMap.entries.removeIf {
            val isAckTimeout = currentTime > it.value.sendTimeMs.plus(ackTimeout)
            if (isAckTimeout) {
                segments.add(it.value)
            }
            isAckTimeout
        }
        return segments
    }

    fun ackData(seqId: Long): Boolean {
        val segment = segmentTraceMap[seqId] ?: return false
        segment.ack = true
        val now = System.currentTimeMillis()
        val rtt = now - segment.sendTimeMs

        maxRttMs = max(maxRttMs, rtt)
        minRttMs = min(minRttMs, rtt)

        recordRttHistory(rtt)
        return true
    }

    private fun goUp() {
        sendRate = if (mode == Mode.MID_BY_MID) {
            var max = 2.0.pow(power + 1).times(MIN_RATE).toInt()
            if (sendRate >= max) {
                sendRate = max
                power++
                max *= 2
            }
            computeMidRate(sendRate, max).ceil().toInt()
        } else {
            power++
            2.0.pow(power).times(MIN_RATE).toInt()
        }
    }

    private fun goDown() {
        sendRate = if (mode == Mode.MID_BY_MID) {
            var min = 2.0.pow(power - 1).times(MIN_RATE).toInt().coerceAtLeast(MIN_RATE)
            if (sendRate <= min) {
                sendRate = min
                power--
                power = power.coerceAtLeast(0)
                min = 2.0.pow(power).times(MIN_RATE).toInt()
            }
            computeMidRate(sendRate, min).floor().toInt()
        } else {
            power = power.minus(1).coerceAtLeast(0)
            computeSendRate()
        }.coerceAtLeast(MIN_RATE)
    }

    private fun computeSendRate() = 2.0.pow(power).times(MIN_RATE).toInt()
    private fun computeMidRate(a: Int, b: Int) = a.plus(b).times(0.5)
    private fun recordLossRate(rate: Float) {
        lossHistory.add(rate)
        avgLr = lossHistory.average()
    }


    private fun recordRttHistory(rtt: Long) {
        rttHistory.add(rtt)
        if (rttHistory.size > RTT_HISTORY_SIZE) {
            rttHistory.removeFirst()
        }
        // Calculate average RTT
        val avgRtt = rttHistory.average().toLong()
        // Update resend interval based on average RTT
        avgRttMs = avgRtt.toInt()
    }

    fun close() {
        scope.cancel()
    }

    private fun Double.floor() = kotlin.math.floor(this)
    private fun Double.ceil() = kotlin.math.ceil(this)

    companion object {
        const val INITIAL_RESEND_INTERVAL_MS = 3000
        const val MIN_RATE = 100
        const val RTT_HISTORY_SIZE = 50
    }

    data class Segment(
        val seqId: Long,
        @Volatile var ack: Boolean = false,
        val bodyType: BodyType,
        @Volatile var sendTimeMs: Long,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Segment

            if (!data.contentEquals(other.data)) return false
            if (sendTimeMs != other.sendTimeMs) return false
            if (seqId != other.seqId) return false
            if (ack != other.ack) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + sendTimeMs.hashCode()
            result = 31 * result + seqId.hashCode()
            result = 31 * result + ack.hashCode()
            return result
        }
    }

    private enum class Mode {
        INITIAL_START, MID_BY_MID
    }
}


class ReceiveLogic(
    private val isReliable: Boolean,
    private val onSend: (BaseMessage) -> Unit,
    private val onNext: (ByteArray) -> Unit
) {
    //    private val queue = PriorityQueue<Segment>(compareBy { it.seqId })
    private val tempBuffer = mutableListOf<Segment>()
    private var expectedSeqId: Long = 0L

    private val segments = TreeSet<Segment>()

    @Synchronized
    fun receive(segment: Segment) {
        sendAcknowledge(segment.seqId)
        if (segment.seqId >= expectedSeqId) {
            segments.add(segment)
            if (segment.bodyType == BodyType.complete || segment.bodyType == BodyType.partialEnd) {
                checkForCompleteMessages()
            }
        }
    }

    private fun sendAcknowledge(id: Long) {
        val ackMessage = BaseMessage.newBuilder()
            .setClass_(MessageClass.data)
            .setType(MessageType.ack)
            .setSeqId(id)
            .setIsReliable(isReliable)
            .build()

        onSend(ackMessage)
    }

    private fun checkForCompleteMessages() {
        while (segments.isNotEmpty()) {
            val segment = segments.first()
            if (segment.seqId == expectedSeqId) {
                expectedSeqId++
                when (segment.bodyType) {
                    BodyType.partialStart -> {
                        tempBuffer.clear()
                        tempBuffer.add(segment)
                    }

                    BodyType.partialMiddle -> {
                        tempBuffer.add(segment)
                    }

                    BodyType.partialEnd -> {
                        if (tempBuffer.isNotEmpty() && tempBuffer.first().bodyType == BodyType
                                .partialStart
                        ) {
                            tempBuffer.add(segment)
                            val message = assembleMessage(tempBuffer)
                            onNext(message)
                        }
                    }

                    BodyType.complete -> {
                        onNext(segment.data)
                    }

                    BodyType.UNRECOGNIZED -> {
                        // Ignore
                    }
                }
                segments.remove(segment)
            } else {
                if (!isReliable) {
                    tempBuffer.clear()
                    expectedSeqId = segment.seqId
                } else {
                    break
                }
            }
        }
    }

    private fun assembleMessage(segments: List<Segment>): ByteArray {
        val byteBuffer = ByteBuffer.allocate(segments.sumOf { it.data.size })
        segments.forEach { segment ->
            byteBuffer.put(segment.data)
        }
        return byteBuffer.array()
    }


    data class Segment(
        val seqId: Long,
        val bodyType: BodyType,
        val data: ByteArray
    ) : Comparable<Segment> {
        override fun compareTo(other: Segment): Int {
            return seqId.compareTo(other.seqId)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Segment

            if (seqId != other.seqId) return false
            if (!data.contentEquals(other.data)) return false
            if (bodyType != other.bodyType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = seqId.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + bodyType.hashCode()
            return result
        }
    }
}


