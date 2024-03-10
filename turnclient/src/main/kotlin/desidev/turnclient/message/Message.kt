package desidev.turnclient.message

import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.StunAttribute
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.util.generateHashCode
import desidev.turnclient.util.multipleOfFour
import java.nio.ByteBuffer
import java.security.SecureRandom


/**
 * A STUN/TURN message class.
 */
data class Message(
    val header: MessageHeader,
    val attributes: List<StunAttribute>,
) {
    val msgClass by lazy {
        val maskedValue = MESSAGE_CLASS_MASK and header.msgType
        MessageClass.entries.find { it.type == maskedValue }!!
    }

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


        fun buildAllocateRequest(
            requestedTransport: Int = TransportProtocol.UDP.code,
            lifetime: Int = 600,
            df: Boolean = true,
            software: String = "ns.turn.client version 1.0",
            nonce: String? = null,
            realm: String? = null,
            username: String,
            password: String
        ): Message {
            val attributes = mutableListOf<StunAttribute>()

            attributes.apply {
                add(
                    StunAttribute.createStunAttribute(
                        AttributeType.REQUESTED_TRANSPORT.type,
                        requestedTransport
                    )
                )
                add(StunAttribute.createStunAttribute(AttributeType.LIFETIME.type, lifetime))
                if (df) {
                    add(
                        StunAttribute.createStunAttribute(
                            type = AttributeType.DONT_FRAGMENT.type,
                            ByteArray(0)
                        )
                    )
                }
                add(
                    StunAttribute.createStunAttribute(
                        type = AttributeType.SOFTWARE.type,
                        value = software
                    )
                )
                nonce?.let {
                    add(
                        StunAttribute.createStunAttribute(
                            type = AttributeType.NONCE.type,
                            value = it
                        )
                    )
                }
                realm?.let {
                    add(
                        StunAttribute.createStunAttribute(
                            type = AttributeType.REALM.type,
                            value = it
                        )
                    )
                }
                add(
                    StunAttribute.createStunAttribute(
                        type = AttributeType.USERNAME.type,
                        value = username
                    )
                )
            }

            val attributesSize = attributes.sizeInBytes()
            val messageLength = attributesSize + if (realm != null) 24 else 0
            val header = MessageHeader(
                MessageType.ALLOCATE_REQUEST.type,
                generateTransactionId(),
                MAGIC_COCKIE,
                messageLength
            )

            if (realm != null) {
                val msg = Message(header, attributes)
                val messageBytes = msg.encodeToByteArray()
                val hash = generateHashCode(input = messageBytes, key = "$username:$realm:$password")
                attributes.add(
                    StunAttribute.createStunAttribute(
                        type = AttributeType.MESSAGE_INTEGRITY.type, value = hash
                    )
                )
            }

            return Message(header, attributes)
        }

        fun buildCreatePermission(): Message {
            TODO()
        }

        /**
         * Builds a CHANNEL-BIND request.
         * This function also xor the address value of xor_peer_address before writing.
         * This also adds the message integrity attribute in the end of the message.
         */
        fun buildChannelBind(
            channelNumber: Int,
            peerAddress: AddressValue,
            user: String,
            password: String,
            realm: String,
            nonce: String
        ): Message {
            val channelNumAttr = StunAttribute.createStunAttribute(
                AttributeType.CHANNEL_NUMBER.type,
                value = channelNumber.toShort() // channel number is of 2 bytes.
            )
            val userAttr = StunAttribute.createStunAttribute(AttributeType.USERNAME.type, value = user)
            val realmAttr = StunAttribute.createStunAttribute(AttributeType.REALM.type, value = realm)
            val nonceAttr = StunAttribute.createStunAttribute(AttributeType.NONCE.type, value = nonce)
            val peerAddressAttr = StunAttribute.createStunAttribute(
                AttributeType.XOR_PEER_ADDRESS.type,
                peerAddress.xorAddress()
            )

            val attrs = listOf(channelNumAttr, peerAddressAttr, userAttr, realmAttr, nonceAttr)
            val hdr = MessageHeader(
                MessageType.CHANNEL_BIND_REQ.type,
                generateTransactionId(),
                MAGIC_COCKIE,
                attrs.sizeInBytes() + 24 // length of attributes + last 24 bytes for message integrity attribute
            )

            val msg = Message(hdr, attrs)

            val key = "$user:$realm:$password"
            val msgIntegrityAttr = StunAttribute.createStunAttribute(
                type = AttributeType.MESSAGE_INTEGRITY.type,
                value = generateHashCode(msg.encodeToByteArray(), key)
            )

            val attributes = attrs + listOf(msgIntegrityAttr)

            return msg.copy(attributes = attributes)
        }

        fun buildRefreshRequest(
            lifetime: Int,
            username: String,
            password: String,
            realm: String,
            nonce: String
        ): Message {
            val attributes = buildList {
                add(StunAttribute.createStunAttribute(AttributeType.LIFETIME.type, lifetime))
                add(StunAttribute.createStunAttribute(AttributeType.USERNAME.type, username))
                add(StunAttribute.createStunAttribute(AttributeType.REALM.type, realm))
                add(StunAttribute.createStunAttribute(AttributeType.NONCE.type, nonce))
            }

            val header = MessageHeader(
                MessageType.ALLOCATE_REFRESH_REQUEST.type,
                generateTransactionId(),
                MAGIC_COCKIE,
                attributes.sizeInBytes() + 24 // length of attributes + last 24 bytes for message integrity attribute
            )

            val msg = Message(header, attributes)
            val key = "$username:$realm:$password"
            val hash = generateHashCode(msg.encodeToByteArray(), key)
            val msgIntegrityAttr = StunAttribute.createStunAttribute(
                type = AttributeType.MESSAGE_INTEGRITY.type,
                value = hash
            )
            val newAttributes = attributes + listOf(msgIntegrityAttr)
            val newMsg = msg.copy(attributes = newAttributes)
            return newMsg
        }

        fun parse(byteArray: ByteArray): Message {
            if (byteArray.size % 4 != 0) {
                throw IllegalArgumentException("Invalid message. Must be a multiple of 4 bytes")
            }

            // Parse header
            val headerBytes = byteArray.sliceArray(0 until 20)
            val header = MessageHeader.decodeFromByteArray(headerBytes)

            val attributesBytes = byteArray.sliceArray(20 until byteArray.size)
            val attributes = parseAttributes(attributesBytes)

            return Message(header, attributes)
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
fun List<StunAttribute>.sizeInBytes(): Int = fold(0) { size, attribute -> size + multipleOfFour(attribute.sizeInBytes) }