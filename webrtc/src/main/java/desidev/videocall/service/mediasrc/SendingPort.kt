package desidev.videocall.service.mediasrc

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SendingPort<T : Any> : ReceivingPort<T> {
    private val queue = LinkedBlockingQueue<T>()
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private var isOpen = true

    val isOpenForSend: Boolean
        get() = lock.withLock { isOpen }

    override val isOpenForReceive: Boolean
        get() = lock.withLock { isOpen || queue.isNotEmpty() }

    override fun receive(): T {
        lock.withLock {
            while (queue.isEmpty() && isOpen) {
                condition.await()
            }
            if (queue.isEmpty() && !isOpen) {
                throw IllegalStateException("Port is closed and queue is empty")
            }
            return queue.poll()!!
        }
    }

    override fun tryReceive(): T? {
        lock.withLock {
            return if (isOpen || queue.isNotEmpty()) queue.poll() else null
        }
    }

    fun send(item: T) {
        lock.withLock {
            check(isOpen) { "Port is closed" }
            queue.put(item)
            condition.signalAll()
        }
    }

    fun close() {
        lock.withLock {
            isOpen = false
            condition.signalAll()
        }
    }

    fun reopen() {
        lock.withLock {
            isOpen = true
            condition.signalAll()
        }
    }
}