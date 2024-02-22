package desidev.videocall.service.mediasrc

interface ReceivingPort<out T : Any> {
    val isOpenForReceive: Boolean
    fun receive(): T
    fun tryReceive(): T?
}