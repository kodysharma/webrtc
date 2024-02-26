package desidev.turnclient.attribute

import desidev.turnclient.message.Message
import java.net.InetAddress
import java.nio.ByteBuffer


data class AddressValue(
    val addressFamily: InetAF, val address: ByteArray, val port: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AddressValue

        if (addressFamily != other.addressFamily) return false
        if (!address.contentEquals(other.address)) return false
        return port == other.port
    }

    override fun hashCode(): Int {
        var result = addressFamily.hashCode()
        result = 31 * result + address.contentHashCode()
        result = 31 * result + port
        return result
    }

    /**
     * Encode the address value into bytes as defined by the rfc 5389 ( mapped address attribute ).
     */
    fun asSTUNAddressBytes(): ByteArray {
        val len = if (addressFamily == InetAF.IPV4) 8 else throw UnsupportedOperationException("address family not supported")
        val buffer = ByteBuffer.allocate(len)
        buffer.put(0)  // first byte set to 0
        buffer.put(InetAF.IPV4.code.toByte())  // address family
        buffer.putShort(port.toShort()) // port
        buffer.put(address) // address bytes

        return buffer.array()
    }

    fun xorAddress(): AddressValue {
        val xorPort = (Message.MAGIC_COCKIE shr 16 xor port) and 0xFFFF
        val addr = ByteBuffer.wrap(address).int

        val xorAddress = addr xor Message.MAGIC_COCKIE
        val xorBuffer = ByteBuffer.allocate(4).apply { putInt(xorAddress) }.array()

        return copy(address = xorBuffer, port = xorPort)
    }

    override fun toString(): String {
        val addr = InetAddress.getByAddress(address)
        return "${addr.hostAddress}:$port"
    }

    companion object {
        fun from(byteArray: ByteArray): AddressValue {
            val afValue = byteArray[1].toInt()
            val family = InetAF.values().find { it.code == afValue }
                ?: throw IllegalArgumentException("Invalid address family")
            if (family == InetAF.IPV4) {
                val port = (byteArray[2].toInt() and 0xFF shl 8) or (byteArray[3].toInt() and 0xFF)
                val address = byteArray.sliceArray(4..7)
                return AddressValue(family, address, port)

            } else {
                throw UnsupportedOperationException("Only IPv4 addresses are supported")
            }
        }

        fun from(inetAddr: InetAddress, port: Int): AddressValue {
            if (inetAddr.address.size > 4) {
                throw UnsupportedOperationException("Only IPv4 addresses are supported")
            }
            return AddressValue(InetAF.IPV4, inetAddr.address, port)
        }
    }
}
