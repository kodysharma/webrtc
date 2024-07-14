package desidev.p2p.agent


import arrow.atomic.AtomicLong
import arrow.atomic.update
import com.google.protobuf.ByteString
import desidev.p2p.BaseMessage
import desidev.p2p.LineBlockException
import desidev.p2p.MessageClass
import desidev.p2p.MessageType
import desidev.p2p.baseMessage
import desidev.p2p.util.preciseDelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
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
        delegateSend = { segment ->
            transport.send(baseMessage {
                class_ = MessageClass.data
                type = MessageType.seq
                isReliable = reliable
                seqId = segment.seqId
                bytes = ByteString.copyFrom(segment.data)
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
                    receiveLogic.receive(ReceiveLogic.Segment(it.seqId, it.bytes.toByteArray()))
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
        receiveLogic.close()
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
    private val dataQueue = ConcurrentLinkedDeque<Segment>()

    // contains the send segments helps in to track segment ack.
    private val segmentTraceMap = ConcurrentHashMap<Long, Segment>()

    private var sendRate = MIN_RATE // segments/second
    private var mode: Mode = Mode.INITIAL_START
    private var power = 0
    private var lowestUSequence = AtomicLong(0L)
    private var nextSeq = AtomicLong(0L)


    init {
        senderJob()
        ackTracingJob()
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
                    failed.forEach {
                        dataQueue.addFirst(it)
                    }
                }
            }

            if (segmentTraceMap.isNotEmpty()) {
                lowestUSequence.set(segmentTraceMap.minBy { it.key }.key)
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

//        logger.debug { "${unack.size}/${segments.size}" }

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

    private fun senderJob() = scope.launch {
        while (isActive) {
            while (dataQueue.isEmpty() && segmentTraceMap.isEmpty()) delay(100)

            while (dataQueue.isNotEmpty() && isActive) {
                val nanoseconds = 1000_000_000.0.div(sendRate).roundToLong()

                with(dataQueue.remove()) segment@{
                    launch {
                        sendTimeMs = System.currentTimeMillis()
                        segmentTraceMap[seqId] = this@segment
                        delegateSend(this@segment)
                    }.start()
                    preciseDelay(nanoseconds)
                }
            }
        }
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

//        logger.debug { "sendRate up: $sendRate" }
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

//        logger.debug { "sendRate down: $sendRate" }
    }

    private fun computeSendRate() = 2.0.pow(power).times(MIN_RATE).toInt()
    private fun computeMidRate(a: Int, b: Int) = a.plus(b).times(0.5)
    private fun recordLossRate(rate: Float) {
        lossHistory.add(rate)
        avgLr = lossHistory.average()
    }

    @Throws(LineBlockException::class)
    fun send(data: ByteArray) {
        if (dataQueue.size >= sendRate) {
            throw LineBlockException("Line is blocked, due to buffer is full!")
        }

        if (nextSeq.get() - lowestUSequence.get() > 10000) {
            logger.debug {
                "segment with lowest sequence is unack: ${lowestUSequence.get()} .. " +
                        "${nextSeq.get()}"
            }
            throw LineBlockException("Line is blocked, due to lowest unacknowledged segment!")
        }

        sendInQueue(Segment(data, 0L, nextSeq.get()))
        nextSeq.update { it + 1 }
    }

    private fun sendInQueue(data: Segment) {
        dataQueue.add(data)
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
        val logger = KotlinLogging.logger { SendLogic::class.simpleName }
        const val INITIAL_RESEND_INTERVAL_MS = 3000
        const val MIN_RATE = 100
        const val RTT_HISTORY_SIZE = 50
    }

    data class Segment(
        val data: ByteArray,
        @Volatile var sendTimeMs: Long,
        val seqId: Long,
        @Volatile var ack: Boolean = false
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
    private val sequencer = Sequencer()
    private val buffer by lazy { linkedSetOf<Segment>() }
    private var nextSequenceId = 0L
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        scope.launch {
            while (isActive) {
                delay(1000)
                if (sequencer.isNotEmpty()) {
                    dispatch()
                }
            }
        }
    }

    fun receive(segment: Segment) {
        sequencer.add(segment)
        if (sequencer.isFull()) {
            dispatch()
        }
        sendAcknowledge(segment.seqId)
    }

    private fun dispatch() = synchronized(this) {
        if (isReliable) {
            dispatchNextReliably()
        } else {
            dispatchNext()
        }
    }

    private fun dispatchNext() {
        val segments = sequencer.next()
        segments.forEach { segment ->
            onNext(segment.data)
        }
    }

    private fun dispatchNextReliably() = synchronized(this) {
        val segments = sequencer.next()

        var index = 0
        while (index < segments.size && segments[index].seqId == nextSequenceId) {
            val seg = segments[index]
            onNext(seg.data)
            nextSequenceId++
            index++
        }

        buffer.addAll(segments.drop(index))
        segments.sortedBy { it.seqId }

        while (buffer.isNotEmpty()) {
            val first = buffer.first()
            if (first.seqId == nextSequenceId) {
                onNext(first.data)
                nextSequenceId++
                buffer.remove(first)
            } else {
                break
            }
        }
    }

    private fun sendAcknowledge(id: Long) {
        onSend(baseMessage {
            class_ = MessageClass.data
            type = MessageType.ack
            seqId = id
            isReliable = true
        })
    }

    fun close() {
        scope.cancel()
    }

    inner class Sequencer(private val maxSegments: Int = 128) {
        private var read = mutableListOf<Segment>()
        private var write = mutableListOf<Segment>()
        fun add(segment: Segment) {
            write.add(segment)
        }

        fun isFull(): Boolean {
            return write.size >= maxSegments
        }

        fun isNotEmpty(): Boolean {
            return write.isNotEmpty()
        }

        private fun swap() {
            val temp = read
            read = write
            write = temp
            write.clear()
        }

        fun next(): List<Segment> {
            swap()
            read.sortBy { it.seqId }
            return read
        }
    }

    class Segment(val seqId: Long, val data: ByteArray)
}


