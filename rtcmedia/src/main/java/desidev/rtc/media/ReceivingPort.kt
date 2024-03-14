package desidev.rtc.media

interface ReceivingPort<out T : Any> {
    val isOpenForReceive: Boolean
    fun receive(): T
    fun tryReceive(): T?
}