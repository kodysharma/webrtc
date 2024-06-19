package desidev.turnclient

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * This class is Thread Safe
 */
class ExpireTimerImpl(private val expireTime: Duration) : ExpireTimer
{
    private var expiryTime: Duration = expireTime
    private var prevTick = System.currentTimeMillis()
    override fun isExpired(): Boolean = synchronized(this)
    {
        tick()
        return expiryTime <= 0.milliseconds
    }

    override fun isCloseToExpire(minExpTime: Duration): Boolean = synchronized(this)
    {
        tick()
        return expiryTime <= minExpTime
    }

    override fun resetExpireTime(duration: Duration?) = synchronized(this)
    {
        prevTick = System.currentTimeMillis()
        expiryTime = duration ?: expireTime
    }

    override fun expiresIn(): Duration = synchronized(this)
    {
        tick()
        return expiryTime
    }

    private fun tick()
    {
        val now = System.currentTimeMillis()
        expiryTime -= (now - prevTick).milliseconds
        prevTick = now
    }
}