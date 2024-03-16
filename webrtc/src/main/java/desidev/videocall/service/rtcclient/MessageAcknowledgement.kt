package desidev.videocall.service.rtcclient

import desidev.videocall.service.rtcmsg.RTCMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

typealias OnAcknowledge = () -> Unit

class MessageAcknowledgement {
    private val timeout: Long = 1000
    private val callbacks = Collections.synchronizedMap(mutableMapOf<Int, OnAcknowledge>())
    suspend fun isAck(message: RTCMessage.Control): Boolean {
        return withAckTimeout(message.txId)
    }

    private suspend fun withAckTimeout(key: Int) = try {
        withTimeout(timeout) {
            suspendCancellableCoroutine { continuation ->
                callbacks[key] = { if (continuation.isActive) continuation.resume(true) }
            }
        }
    } catch (ex: TimeoutCancellationException) {
        false
    } finally {
        callbacks.remove(key)
    }

    fun acknowledge(message: RTCMessage.Control) {
        callbacks[message.txId]?.let {
            it.invoke()
            println("Acknowledged message with txId: ${message.txId}")
        } ?: println("No callback found for txId: ${message.txId}")
    }
}