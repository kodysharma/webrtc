package desidev.turnclient

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal interface ExpireAble {
    fun isExpired(): Boolean
    fun isCloseToExpire(minExpTime: Duration = 1.minutes): Boolean
    fun resetExpireTime(duration: Duration)
    fun expiresIn(): Duration
}