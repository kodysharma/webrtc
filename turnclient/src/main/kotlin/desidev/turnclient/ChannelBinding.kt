package desidev.turnclient

import desidev.turnclient.attribute.AddressValue


interface DataCallback {
    fun onReceived(data: ByteArray)
}

interface ChannelBinding {
    val peerAddress: AddressValue
    val channelNumber: Int
    fun sendData(bytes: ByteArray)
    fun setDataCallback(callback: DataCallback)
    fun close()

}