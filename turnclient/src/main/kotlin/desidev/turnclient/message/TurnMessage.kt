package desidev.turnclient.message

import desidev.turnclient.attribute.StunAttribute
import desidev.turnclient.util.multipleOfFour
import java.nio.ByteBuffer
import java.security.SecureRandom


/**
 * A STUN/TURN message class.
 */
data class TurnMessage(
    val header: MessageHeader,
    val attributes: List<StunAttribute>,
) {
    val msgClass by lazy {
        val maskedValue = MESSAGE_CLASS_MASK and header.msgType
        MessageClass.entries.find { it.type == maskedValue }!!
    }

    val txId: MessageHeader.TransactionId get() = header.txId

    init {
        val not = MessageType.isValidType(header.msgType).not()
        if (not) {
            throw IllegalArgumentException("Invalid message type")
        }
    }

    fun encodeToByteArray(): ByteArray = ByteBuffer.allocate(20 + attributes.sizeInBytes())
        .apply { // ByteBuffer by default uses the big endian byte order.
            // write stun header
            header.writeTo(this)
            attributes.forEach {
                it.writeTo(this)
                val padding = multipleOfFour(it.sizeInBytes) - it.sizeInBytes
                val newPos = position() + padding
                position(newPos)
            }
        }.array()

    override fun toString(): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append(header.toString())
        attributes.forEach { attribute ->
            stringBuilder.append("\n")
            stringBuilder.append(attribute.toString())
        }

        return stringBuilder.toString()
    }

    fun toHexString(): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append("Message Header:\n")

        val messageBuffer = encodeToByteArray()
        var count = 0
        for (i in 0..19) {
            stringBuilder.append(String.format("%02x", messageBuffer[i])).append(" ")
            if (++count % 4 == 0) {
                stringBuilder.appendLine()
            }
        }

        stringBuilder.appendLine()
        stringBuilder.appendLine("Attributes:")
        for (i in 20..messageBuffer.lastIndex) {
            stringBuilder.append(String.format("%02x", messageBuffer[i])).append(" ")
            if (++count % 4 == 0) {
                stringBuilder.appendLine()
            }
        }
        return stringBuilder.toString()
    }

    companion object {
        const val MAGIC_COCKIE: Int = 0x2112A442
        const val MESSAGE_CLASS_MASK: UShort = 0x0110u

        fun parse(byteArray: ByteArray): TurnMessage {
            if (byteArray.size % 4 != 0) {
                throw InvalidStunMessage("Invalid message. Must be a multiple of 4 bytes")
            }

            // first two bits of the stun message should be zero.
            if ((byteArray[0].toInt() and 0x00C0) != 0) {
                throw InvalidStunMessage("Invalid message!. Stun msg required to have its first two bits set to 0.")
            }

            // Parse header
            return try {
                val headerBytes = byteArray.sliceArray(0 until 20)
                val attributesBytes = byteArray.sliceArray(20 until byteArray.size)

                val header = MessageHeader.decodeFromByteArray(headerBytes)
                val attributes = parseAttributes(attributesBytes)

                // check for valid message type
                if (!MessageType.isValidType(header.msgType)) {
                    throw InvalidStunMessage("Header does not include a valid message type: ${header.msgType}")
                }

                // msg integrity check
                

                TurnMessage(header, attributes)
            } catch (ex: Exception) {
                throw InvalidStunMessage(cause = ex)
            }
        }

        private fun parseAttributes(attributeBytes: ByteArray): List<StunAttribute> {
            val attributes = mutableListOf<StunAttribute>()
            val buffer = ByteBuffer.wrap(attributeBytes)

            while (buffer.hasRemaining()) {
                val type = buffer.short.toUShort()
                val length = buffer.short.toInt()
                val valueBytes = ByteArray(length) // read attribute value
                buffer.get(valueBytes)

                // skip the padding bytes
                val padding = multipleOfFour(length) - length
                val newPos = buffer.position() + padding
                buffer.position(newPos)

                val attr = StunAttribute.createStunAttribute(type, valueBytes)
                attributes.add(attr)
            }

            return attributes
        }

        fun generateTransactionId(): ByteArray {
            val random = SecureRandom()
            val transactionId = ByteArray(12)
            random.nextBytes(transactionId)
            return transactionId
        }
    }
}

/**
 * Total attributes size in bytes. Each attribute is padded to be a multiple of 4 bytes.
 */
fun List<StunAttribute>.sizeInBytes(): Int =
    fold(0) { size, attribute -> size + multipleOfFour(attribute.sizeInBytes) }

class InvalidStunMessage(
    override val message: String? = null,
    override val cause: Throwable? = null
) : RuntimeException()