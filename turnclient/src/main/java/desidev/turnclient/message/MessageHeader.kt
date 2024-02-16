package com.shared.livebaat.turn.message

import desidev.turnclient.util.toHexString
import java.nio.ByteBuffer

data class MessageHeader(
    val msgType: UShort,
    val txId: ByteArray,
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
        buffer.put(txId)
    }

    override fun toString(): String = StringBuilder().apply {
        appendLine("MessageType(${MessageType.messageTypeToString(msgType)})")
        appendLine("MessageLength($msgLen)")
        appendLine("MagicCookie(${String.format("%02x", magicCookie)})")
        appendLine("TransactionId(${txId.toHexString()})")
    }.toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MessageHeader

        if (msgType != other.msgType) return false
        if (!txId.contentEquals(other.txId)) return false
        if (magicCookie != other.magicCookie) return false
        return msgLen == other.msgLen
    }

    override fun hashCode(): Int {
        var result = msgType.hashCode()
        result = 31 * result + txId.contentHashCode()
        result = 31 * result + magicCookie
        result = 31 * result + msgLen
        return result
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
            val transactionId = ByteArray(12)
            buffer.get(transactionId)

            return MessageHeader(type.toUShort(), transactionId, magicCookie, messageLen.toInt())
        }
    }
}