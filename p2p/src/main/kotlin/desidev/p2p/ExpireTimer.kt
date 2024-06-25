package desidev.p2p

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

interface ExpireTimer {
    fun isExpired(): Boolean
    fun isCloseToExpire(minExpTime: Duration = 1.minutes): Boolean
    fun resetExpireTime(duration: Duration? = null)
    fun expiresIn(): Duration
}