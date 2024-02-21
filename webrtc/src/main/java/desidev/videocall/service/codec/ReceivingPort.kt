package desidev.videocall.service.codec

interface ReceivingPort<out T : Any> {
    val isOpenForReceive: Boolean
    fun receive(): T
    fun tryReceive(): T?
}