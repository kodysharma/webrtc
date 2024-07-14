package desidev.p2p

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * This class is Thread Safe
 */
class ExpireTimerImpl(private val expireTime: Duration) : ExpireTimer {
    private var expiryTime: Duration = expireTime
    private var prevTick = System.currentTimeMillis()
    override fun isExpired(): Boolean {
        tick()
        return expiryTime <= 0.milliseconds
    }

    override fun isCloseToExpire(minExpTime: Duration): Boolean {
        tick()
        return expiryTime <= minExpTime
    }

    override fun resetExpireTime(duration: Duration?) {
        prevTick = System.currentTimeMillis()
        expiryTime = duration ?: expireTime
    }

    override fun expiresIn(): Duration {
        tick()
        return expiryTime.coerceAtLeast(0.seconds)
    }

    private fun tick() = synchronized(this) {
        val now = System.currentTimeMillis()
        expiryTime -= (now - prevTick).milliseconds
        prevTick = now
    }
}