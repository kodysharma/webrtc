package desidev.p2p.turn.attribute

import desidev.p2p.turn.message.TurnMessage.Companion.generateTransactionId

class AttributeKey<T>(val typeCode: Int)

object AttributeKeys {
    val USERNAME = AttributeKey<String>(0x0006)
    val PASSWORD = AttributeKey<String>(0x0007)
    val MESSAGE_INTEGRITY  = AttributeKey<ByteArray>(0x0008)
    val XOR_MAPPED_ADDRESS = AttributeKey<ByteArray>(0x0020)
    val LIFETIME = AttributeKey<Long>(0x000d)
}

class StunMessage {
    var msgType: Int? = null
    var msgLen: Int = 0
    val magicCookie = 0x2112A442
    val transactionId = generateTransactionId()

    val attributes  = mutableMapOf<Int, Any>()
    fun <T : Any> addAttr(key: AttributeKey<T>, value: T) {
        attributes[key.typeCode] = value
    }

    inline fun <reified T : Any> getAttr(key: AttributeKey<T>): T? {
        return attributes[key.typeCode] as? T
    }
}
fun main() {
    val msg = StunMessage()
    msg.addAttr(AttributeKeys.USERNAME, "Neeraj Sharma")
}