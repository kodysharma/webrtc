package desidev.turnclient

import desidev.turnclient.attribute.AddressValue

typealias IncomingMessage = (ByteArray) -> Unit

interface ChannelBinding {
    val peerAddress: AddressValue
    val channelNumber: Int
    val timestamp : Long
    fun sendMessage(bytes: ByteArray)
    fun receiveMessage(cb: IncomingMessage)
}