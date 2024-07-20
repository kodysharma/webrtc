package desidev.p2p

import desidev.p2p.util.isReachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.desidev.kotlinutils.ReentrantMutex
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

object NetworkStatus {
    private val remoteServer = InetSocketAddress("8.8.8.8", 53)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val callbacks = mutableListOf<Callback>()
    private val timer = ExpireTimerImpl(20.seconds)
    private val mutex = ReentrantMutex()

    @Volatile
    var isNetworkAvailable: Boolean = false

    init {
        checkNetworkTask()
    }

    private fun checkNetworkTask() {
        scope.launch {
            while (isActive) {
                notifyNetStatusChange()
                timer.resetExpireTime()

                if (!timer.isExpired()) {
                    delay(timer.expiresIn())
                }
            }
        }
    }

    private suspend fun notifyNetStatusChange() {
        mutex.withLock {
            val reachable = isReachable()
            if (isNetworkAvailable != reachable) {
                isNetworkAvailable = reachable
                if (reachable) {
                    callbacks.forEach { it.onNetworkReachable() }
                } else {
                    callbacks.forEach { it.onNetworkUnreachable() }
                }
            }
        }
    }

    private suspend fun isReachable() = withContext(Dispatchers.IO) {
        isReachable(
            remoteServer.hostString,
            remoteServer.port,
            5000
        )
    }

    fun addCallback(callback: Callback) = synchronized(callbacks) {
        callbacks.add(callback)
    }

    fun removeCallback(callback: Callback) = synchronized(callbacks) {
        callbacks.remove(callback)
    }

    interface Callback {
        fun onNetworkReachable() {
            //
        }

        fun onNetworkUnreachable() {}
    }

    fun close() {
        scope.cancel()
    }
}
