package desidev.rtc.rtcclient

import desidev.rtc.rtcmsg.RTCMessage
import desidev.rtc.rtcmsg.RTCMessage.Acknowledge
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.coroutines.resume

typealias OnAcknowledge = () -> Unit

class MessageAcknowledgement {
    private val timeoutMs: Long = 1000
    private val callbacks = Collections.synchronizedMap(mutableMapOf<Int, OnAcknowledge>())
    suspend fun isAck(message: RTCMessage.Control): Boolean {
        return withAckTimeout(message.txId)
    }

    private suspend fun withAckTimeout(key: Int) = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                callbacks[key] = { if (continuation.isActive) continuation.resume(true) }
            }
        }
    } catch (ex: TimeoutCancellationException) {
        false
    } finally {
        callbacks.remove(key)
    }

    fun acknowledge(message: Acknowledge) {
        callbacks[message.txId]?.let {
            it.invoke()
            println("Acknowledged message with txId: ${message.txId}")
        } ?: println("No callback found for txId: ${message.txId}")
    }
}