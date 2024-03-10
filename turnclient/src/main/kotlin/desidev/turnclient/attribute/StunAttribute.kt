package desidev.turnclient.attribute

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * A generalized stun/turn attribute class.
 * Each attribute is encoded as a type-length-value (TLV) format.
 * 2 bytes for type, 2 bytes for length ( a short integer represents the size of value in bytes).
 * The value is the actual data of the attribute.
 */

data class StunAttribute(
    val type: UShort,
    private val value: ByteArray // not padded to be a multiple of 4 bytes.
) {
    /**
     * returning attribute size includes the size of attribute type length and value.
     */
    val sizeInBytes = value.size + 4
    fun getValueAsInt(): Int {
        return ByteBuffer.wrap(value).int
    }

    fun getValueAsShort(): Short = ByteBuffer.wrap(value).short

    fun getValueAsString(): String {
        return value.decodeToString()
    }


    fun getValueAsByteArray(): ByteArray = this.value

    fun getValueAsAddress(): AddressValue {
        return AddressValue.from(value)
    }

    fun getAsErrorValue(): ErrorValue {
        return ErrorValue.from(value)
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.putShort(type.toShort())
        buffer.putShort(value.size.toShort())
        buffer.put(value)
    }


    @OptIn(ExperimentalStdlibApi::class)
    override fun toString(): String {
        val enumType = AttributeType.entries.find { it.type == this.type }
        val value: String = when (enumType?.valueType) {
            ValueType.INTEGER -> {
                val valueInt = getValueAsInt()
                "$valueInt (0x${valueInt.toString(16)})"
            }

            ValueType.SHORT -> {
                val valueInt = getValueAsShort()
                "$valueInt (0x${valueInt.toString(16)})"
            }

            ValueType.XOR_ADDR -> {
                val realAddress = getValueAsAddress().xorAddress()
                "${InetAddress.getByAddress(realAddress.address)}: ${realAddress.port}"
            }

            ValueType.STRING -> {
                getValueAsString()
            }

            ValueType.ByteArray -> {
                this.value.toHexString()
            }

            ValueType.Error -> {
                getAsErrorValue().toString()
            }

            ValueType.NOTHING -> {
                "Nothing"
            }

            else -> ""
        }
        return "${enumType?.name} (0x${
            String.format(
                "%02x",
                type.toShort()
            )
        }), Value: $value, Len: ${this.value.size}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StunAttribute

        if (type != other.type) return false
        if (!value.contentEquals(other.value)) return false
        return sizeInBytes == other.sizeInBytes
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + sizeInBytes
        return result
    }

    companion object {
        fun createStunAttribute(type: UShort, value: ByteArray): StunAttribute {
            return StunAttribute(type, value)
        }

        fun createStunAttribute(type: UShort, value: Int): StunAttribute {
            return StunAttribute(type, ByteBuffer.allocate(4).apply { putInt(value) }.array())
        }

        fun createStunAttribute(type: UShort, value: String): StunAttribute {
            return StunAttribute(type, value.encodeToByteArray())
        }

        fun createStunAttribute(type: UShort, peerAddress: AddressValue): StunAttribute {
            return StunAttribute(type, peerAddress.asSTUNAddressBytes())
        }

        fun createStunAttribute(type: UShort, value: Short): StunAttribute {
            return StunAttribute(type, ByteBuffer.allocate(2).apply { putShort(value) }.array())
        }
    }
}

