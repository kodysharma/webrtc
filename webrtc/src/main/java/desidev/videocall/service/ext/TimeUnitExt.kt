package desidev.videocall.service.ext


data class MilliSeconds(val value: Double)
data class MicroSeconds(val value: Double)

fun MilliSeconds.toMicroSec(): MicroSeconds = MicroSeconds(value * 1000.0)
fun MicroSeconds.toMilliSec(): MilliSeconds = MilliSeconds(value * 0.001)


val Number.asMilliSec get() = MilliSeconds(this.toDouble())
val Number.asMicroSec get() = MicroSeconds(this.toDouble())

fun MicroSeconds.toLong() = value.toLong()
fun MilliSeconds.toLong() = value.toLong()

fun MicroSeconds.toInt() = value.toInt()
fun MilliSeconds.toInt() = value.toInt()

operator fun MilliSeconds.times(multiplier: Number) = MilliSeconds(value * multiplier.toDouble())
operator fun MilliSeconds.times(other : MilliSeconds) = MilliSeconds(value * other.value)


operator fun MicroSeconds.times(multiplier: Number) = MicroSeconds(value * multiplier.toDouble())
operator fun MicroSeconds.times(other : MicroSeconds) = MicroSeconds(value * other.value)