package desidev.turnclient

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ExpireAbleImpl(expireTime: Duration) : ExpireAble {
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

    override fun resetExpireTime(duration: Duration) {
        prevTick = System.currentTimeMillis()
        expiryTime = duration
    }

    override fun expiresIn(): Duration {
        tick()
        return expiryTime
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        expiryTime -= (now - prevTick).milliseconds
        prevTick = now
    }
}