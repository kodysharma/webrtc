package desidev.turnclient

import desidev.turnclient.util.isReachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

object NetworkStatus
{
    private val remoteServer = InetSocketAddress("8.8.8.8", 53)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val callbacks = mutableListOf<Callback>()
    private val timer = ExpireTimerImpl(5.seconds)
    @Volatile private var isNetworkAvailable: Boolean = isReachable()

    init
    {
        checkNetworkTask()
    }

    private fun checkNetworkTask()
    {
        scope.launch {
            while (isActive)
            {
                if (!timer.isExpired())
                {
                    delay(10)
                    continue
                }
                timer.resetExpireTime()
                val reachable = isReachable()
                if (reachable != isNetworkAvailable)
                {
                    isNetworkAvailable = reachable
                    synchronized(callbacks) {
                        if (isNetworkAvailable)
                        {
                            callbacks.forEach { it.onNetworkReachable() }
                        } else
                        {
                            callbacks.forEach { it.onNetworkUnreachable() }
                        }
                    }
                }
            }
        }
    }

    private fun isReachable() = isReachable(remoteServer.hostString, remoteServer.port, 5000)

    fun addCallback(callback: Callback) = synchronized(callbacks) {
        callbacks.add(callback)
    }

    fun removeCallback(callback: Callback) = synchronized(callbacks) {
        callbacks.remove(callback)
    }

    interface Callback
    {
        fun onNetworkReachable()
        fun onNetworkUnreachable()
    }

    fun close()
    {
        scope.cancel()
    }
}
