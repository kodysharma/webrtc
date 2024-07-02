package desidev.p2p.agent


import arrow.atomic.Atomic
import arrow.atomic.AtomicLong
import arrow.atomic.update
import desidev.p2p.LineBlockException
import desidev.p2p.util.ConditionLock
import desidev.p2p.util.preciseDelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.LinkedList
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong


interface SendLogic<T : Any> {
    data class AckData(
        val millis: Long,
        val seqId: Long
    )

    fun send(data: T)
    fun ackData(ackData: AckData): Boolean
}

interface ReceiveLogic<T : Any> {
    data class Segment<T : Any>(val data: T, val seqId: Long)

    fun push(segment: Segment<T>)
    fun onNext(data: T)
    fun onAck(seqIds: List<Long>)
}

abstract class ReliableReceive<T : Any> : ReceiveLogic<T> {
    private val dataQueue = ConcurrentLinkedQueue<ReceiveLogic.Segment<T>>()
    private var nextSeqId = AtomicLong(0L)
    override fun push(segment: ReceiveLogic.Segment<T>) {
        dataQueue.add(segment)
        if (nextSeqId.get() == dataQueue.first().seqId) {
            onNext(dataQueue.remove().data)
            nextSeqId.incrementAndGet()
        }
    }
}

abstract class ReliableSendLogic<T : Any> : SendLogic<T> {
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    var avgRttMs = INITIAL_RESEND_INTERVAL_MS

    @Volatile
    var maxRttMs = Long.MIN_VALUE

    @Volatile
    var minRttMs = Long.MAX_VALUE

    @Volatile
    var avgSendRate: Double = 0.0 // bytes/seconds

    @Volatile
    var avgLr: Double = 0.0

    @Volatile
    var th = Int.MAX_VALUE

    private val rttHistory = LinkedList<Long>()
    private val sendRateHistory = LinkedList<Double>()
    private val lossHistory = LinkedList<Float>()
    private val dataQueue = ConcurrentLinkedDeque<Segment<T>>()
    private val singleExecutor = Executors.newSingleThreadExecutor()
    private val ackTraceMap = ConcurrentHashMap<Long, Segment<T>>()
    private val roundComplete = ConditionLock(false)

    @Volatile
    private var threshold = Long.MAX_VALUE
    private val avgTh = Atomic<Double>(Double.MAX_VALUE)
    private val thHistory = LinkedList<Int>()
    private var breakApplied = false

    private var sendRate = MIN_RATE // segments/second
    private val rateHistory = TreeSet<Int>()
    private var mode: Mode = Mode.INITIAL_START
    private var power = 0
    private var needUp: Boolean = false


    init {
        senderJob()
        ackTracingJob()
    }


    private fun ackTracingJob() = scope.launch {
        while (isActive) {
            while (ackTraceMap.isEmpty()) delay(100)

            val now = System.currentTimeMillis()

            val segments = mutableListOf<Segment<*>>()
            ackTraceMap.entries.removeIf { entry ->
                (entry.value.sendTimeMs.plus(avgRttMs * 2) < now).also { removed ->
                    if (removed) segments.add(entry.value)
                }
            }

            val uSeg = segments.filter { !it.ack }
            logger.debug { "failure: ${uSeg.size}/${segments.size}" }
            val dropped = uSeg.size.toFloat()
            val loss = dropped / segments.size

            if (segments.size > 100) {
                recordLossRate(loss)
                val isNeedUp = (dataQueue.size.toFloat() / sendRate) > 0.60


                if (lossHistory.size >= 20) {
                    if (avgLr > 0.04) {
                        goDown()
                        mode = Mode.MID_BY_MID
                    } else if (loss <= 0.02 && isNeedUp) {
                        goUp()
                    }
                    lossHistory.clear()
                } else {
                    if (loss > 0.04) {
                        goDown()
                    }
                }

                if (mode == Mode.INITIAL_START) {
                    if (loss < 0.04 && isNeedUp) {
                        goUp()
                    } else {
                        goDown()
                        mode = Mode.MID_BY_MID
                    }
                }
            }
            delay(1000)
        }
    }

    private fun senderJob() = scope.launch {
        while (isActive) {
            while (dataQueue.isEmpty() && ackTraceMap.isEmpty()) delay(100)

            while (dataQueue.isNotEmpty()) {
                val nanoseconds = 1000_000_000.0.div(sendRate).roundToLong()

                with(dataQueue.remove()) segment@{
                    launch {
                        sendTimeMs = System.currentTimeMillis()
                        ackTraceMap[seqNumber] = this@segment
                        onSend(listOf(data to seqNumber))
                    }.start()
                    preciseDelay(nanoseconds)
                }
            }
        }
    }

    override fun ackData(ackData: SendLogic.AckData): Boolean {
        val segment = ackTraceMap[ackData.seqId] ?: return false
        segment.ack = true
        val now = System.currentTimeMillis()
        val rtt = now - segment.sendTimeMs

        maxRttMs = max(maxRttMs, rtt)
        minRttMs = min(minRttMs, rtt)

        recordRttHistory(rtt)

        val travelTime = (ackData.millis - segment.sendTimeMs) * 0.0001
        val sendRate = sizeOfData(segment.data) / travelTime
        updateAvgSendrate(sendRate)
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

        logger.debug { "sendRate up: $sendRate" }
    }

    private fun goDown() {
        sendRate = if (mode == Mode.MID_BY_MID) {
            var min = 2.0.pow(power - 1).times(MIN_RATE).toInt().coerceAtLeast(MIN_RATE)
            if (sendRate <= min) {
                sendRate = min
                power--
                min = 2.0.pow(power).times(MIN_RATE).toInt()
            }
            computeMidRate(sendRate, min).floor().toInt()
        } else {
            power = power.minus(1).coerceAtLeast(0)
            computeSendRate()
        }.coerceAtLeast(MIN_RATE)

        logger.debug { "sendRate down: $sendRate" }
    }

    private fun computeSendRate() = 2.0.pow(power).times(MIN_RATE).toInt()
    private fun computeMidRate(a: Int, b: Int) = a.plus(b).times(0.5)

    private fun recordLossRate(rate: Float) {
        lossHistory.add(rate)
        avgLr = lossHistory.average()
    }

    private fun recordThreshold(th: Int) {
        thHistory.add(th)
        if (thHistory.size > 2) thHistory.removeFirst()
        avgTh.set(thHistory.average())
    }


    @Throws(LineBlockException::class)
    override fun send(data: T) {
        if (dataQueue.size >= sendRate) {
            throw LineBlockException("Line is blocked, due to buffer is full!")
        }
        nextSeq.update { it + 1 }
        sendInQueue(Segment(data, 0L, nextSeq.get()))
    }

    private fun sendInQueue(data: Segment<T>) {
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

    private fun updateAvgSendrate(rt: Double) {
        synchronized(sendRateHistory) {
            sendRateHistory.add(rt)
            if (sendRateHistory.size >= SEND_RATE_HISTORY_SIZE) {
                sendRateHistory.removeFirst()
            }
            avgSendRate = sendRateHistory.average()
        }
    }

    private fun Double.floor() = kotlin.math.floor(this)
    private fun Double.ceil() = kotlin.math.ceil(this)
    protected abstract fun sizeOfData(data: T): Long
    protected abstract fun onSend(list: List<Pair<T, Long>>)
    protected abstract fun onDivide(data: T): List<T>


    companion object {
        val logger = KotlinLogging.logger { ReliableSendLogic::class.simpleName }
        const val MAX_UNACKNOWLEDGED_DATA = 20_000 // KB
        const val INITIAL_RESEND_INTERVAL_MS =
            3000 // Initial resend interval in milliseconds (3 seconds)
        const val RTT_HISTORY_SIZE = 25 // Number of RTT values to keep for calculating average
        const val SEND_RATE_HISTORY_SIZE = 100
        const val DATA_QUEUE_MAX_SIZE = 2000
        const val MIN_RATE = 100
        const val MAX_LOSS_HISTORY = 10

        private var nextSeq = AtomicLong(0L)

    }

    private class Segment<out T : Any>(
        val data: T,
        var sendTimeMs: Long,
        val seqNumber: Long,
        var ack: Boolean = false
    )

    private enum class Mode {
        INITIAL_START, MID_BY_MID
    }
}


