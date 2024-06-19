package desidev.turnclient

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal interface ExpireTimer {
    fun isExpired(): Boolean
    fun isCloseToExpire(minExpTime: Duration = 1.minutes): Boolean
    fun resetExpireTime(duration: Duration? = null)
    fun expiresIn(): Duration
}