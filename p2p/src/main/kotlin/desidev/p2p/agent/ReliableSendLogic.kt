package desidev.p2p.agent


import desidev.p2p.DataSendBlockedException
import mu.KotlinLogging
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


interface SendLogic<T : Any> {
    data class Feedback(
        val seqNumber: Long,
        val speed: Int
    )

    fun send(data: T)
    fun feedback(feedback: Feedback)
}

abstract class ReliableSendLogic<T : Any> : SendLogic<T> {
    companion object {
        val logger = KotlinLogging.logger { ReliableSendLogic::class.simpleName }
        const val MAX_UNACKNOWLEDGED_DATA = 100
        const val INITIAL_RESEND_INTERVAL_MS =
            3000 // Initial resend interval in milliseconds (3 seconds)
        const val RTT_HISTORY_SIZE = 100 // Number of RTT values to keep for calculating average
    }

    private class DataInfo<out T : Any>(
        data: T,
        val sendTime: Long
    ) {
        private var _data: T? = data
        val data: T? get() = _data
        fun acknowledge() = synchronized(this) {
            _data = null
        }

        fun isAcknowledged() = synchronized(this) { data == null }
    }

    private val dataInfo = ConcurrentHashMap<Long, DataInfo<T>>()
    private val executor = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    var avgRTT = INITIAL_RESEND_INTERVAL_MS
    private val rttHistory = LinkedList<Long>()

    private var nextSeq: Long = 0L
    private val slidingSequence = ConcurrentLinkedQueue<Long>()

    init {
        // Task to resend unacknowledged data periodically
        executor.scheduleWithFixedDelay({
            for (seq in slidingSequence) {
                dataInfo[seq]?.let { info ->
                    if (!info.isAcknowledged() && shouldResend(info)) {
                        onSend(seq, info.data!!)
                    }
                }
            }
        }, 0, 300, TimeUnit.MILLISECONDS)
    }

    private fun shouldResend(info: DataInfo<*>): Boolean {
        return (System.currentTimeMillis() - info.sendTime) >= avgRTT
    }

    /**
     * Sends data to the peer.
     *
     * This method attempts to send the given data to the peer. If the number of unacknowledged
     * data items exceeds the `MAX_UNACKNOWLEDGED_DATA` limit, it checks if all previously sent
     * data items have been acknowledged by the peer. If all previously sent data items are
     * acknowledged, the method clears the tracking data structure and sends the new data. If not,
     * it throws a `DataSendBlockedException`.
     *
     * @param data The data to be sent to the peer.
     * @throws DataSendBlockedException If the number of unacknowledged data items exceeds
     * the `MAX_UNACKNOWLEDGED_DATA` limit and not all previously sent data items have been
     * acknowledged by the peer.
     */


    @Throws(DataSendBlockedException::class)
    override fun send(data: T) {
        if (slidingSequence.size >= MAX_UNACKNOWLEDGED_DATA) {
            // Throw an exception indicating that sending is blocked
            throw DataSendBlockedException(
                "Cannot send data until all previously sent data is accepted."
            )
        } else {
            sendWithSeq(data)
        }
    }

    private fun sendWithSeq(data: T) {
        val sequenceId = nextSeq++
        slidingSequence.add(sequenceId)
        dataInfo[sequenceId] = DataInfo(data, System.currentTimeMillis())
        onSend(sequenceId, data)
    }

    /**
     * Receives an acknowledgment from the peer.
     *
     * This method is called when an acknowledgment for a previously sent data item is received
     * from the peer. It updates the internal tracking data structure to mark the data item
     * corresponding to the given sequence number as acknowledged.
     *
     * @param seqNumber The sequence number of the data item that has been acknowledged by the peer.
     */
    private fun receiveAcknowledgement(seqNumber: Long) {
        val info = dataInfo[seqNumber] ?: return
        if (info.isAcknowledged()) return

        info.acknowledge()
        val rtt = System.currentTimeMillis() - info.sendTime
        logger.debug { "rtt: $rtt on $seqNumber" }
        updateResendInterval(rtt)

        val iterator = slidingSequence.iterator()
        while (iterator.hasNext()) {
            val seq = iterator.next()
            if (dataInfo[seq]?.isAcknowledged() != true) {
                break
            }
            slidingSequence.remove()
            dataInfo.remove(seq)
        }
    }

    override fun feedback(feedback: SendLogic.Feedback) {
        receiveAcknowledgement(feedback.seqNumber)
    }

    private fun updateResendInterval(rtt: Long) {
        synchronized(rttHistory) {
            rttHistory.add(rtt)
            if (rttHistory.size > RTT_HISTORY_SIZE) {
                rttHistory.removeFirst()
            }
            // Calculate average RTT
            val avgRtt = rttHistory.average().toLong()
            // Update resend interval based on average RTT
            avgRTT = avgRtt.toInt()
        }
    }


    /**
     * Abstract method to send data to the peer.
     *
     * This method should be implemented by subclasses to provide the actual data sending logic.
     *
     * @param seqNumber The sequence number of the data item to be sent.
     * @param data The data to be sent to the peer.
     */
    protected abstract fun onSend(seqNumber: Long, data: T)
}



