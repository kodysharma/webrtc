package desidev.p2p.turn.message

import java.nio.ByteBuffer

data class MessageHeader(
    val msgType: UShort,
    val txId: TransactionId,
    val magicCookie: Int,
    val msgLen: Int
) {
    fun writeTo(buffer: ByteBuffer) {
        if (buffer.remaining() < 20) {
            throw RuntimeException("given buffer has not enough size.")
        }
        buffer.putShort(msgType.toShort())
        buffer.putShort(msgLen.toShort())
        buffer.putInt(magicCookie)
        buffer.put(txId.bytes)
    }

    override fun toString(): String = StringBuilder().apply {
        appendLine("MessageType(${MessageType.messageTypeToString(msgType)})")
        appendLine("MessageLength($msgLen)")
        appendLine("MagicCookie(${String.format("%02x", magicCookie)})")
        appendLine("TransactionId(${txId})")
    }.toString()
    data class TransactionId(val bytes: ByteArray){
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TransactionId

            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            return bytes.contentHashCode()
        }

    }

    companion object {
        /**
         * First 20 bytes of the turn message is reserved for Message Header.
         * This function parses that bytes and return a Message Header object.
         */
        internal fun decodeFromByteArray(bytes: ByteArray): MessageHeader {
            val buffer = ByteBuffer.wrap(bytes)
            val type = buffer.short
            val messageLen = buffer.short
            val magicCookie = buffer.int
            val txBytes = ByteArray(12)
            buffer.get(txBytes)

            return MessageHeader(type.toUShort(), TransactionId(txBytes), magicCookie, messageLen.toInt())
        }
    }
}