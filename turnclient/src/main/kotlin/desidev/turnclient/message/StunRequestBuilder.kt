package desidev.turnclient.message

import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.StunAttribute
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.message.Message.Companion.MAGIC_COCKIE
import desidev.turnclient.message.Message.Companion.generateTransactionId
import desidev.turnclient.util.generateHashCode

class StunRequestBuilder {
    private lateinit var transactionId: ByteArray
    private var requestUpdated = true
    private val stunAttributes = mutableSetOf<StunAttribute>()

    private var messageType: MessageType = MessageType.ALLOCATE_REQUEST
    private var message: Message? = null

    // attributes
    private var requestedTransport: Int = TransportProtocol.UDP.code
    private var lifetime: Int = 600
    private var df: Boolean = true
    private var software: String = "ns.turn.client version 1.0"
    private var nonce: String? = null
    private var realm: String? = null
    private var username: String? = null
    private var password: String? = null
    private var channelNumber: Int = 0
    private var peerAddress: AddressValue? = null


    fun setMessageType(messageType: MessageType): StunRequestBuilder {
        assert(messageType.type and MessageClass.REQUEST.type == MessageClass.REQUEST.type)
        if (this.messageType != messageType) {
            this.messageType = messageType
            requestUpdated = true
        }
        return this
    }

    fun setRealm(realm: String?): StunRequestBuilder {
        if (!this.realm.contentEquals(realm)) {
            this.realm = realm
            requestUpdated = true
        }
        return this
    }

    fun setNonce(nonce: String?): StunRequestBuilder {
        if (!this.nonce.contentEquals(nonce)) {
            this.nonce = nonce
            requestUpdated = true
        }
        return this
    }

    fun setUsername(username: String): StunRequestBuilder {
        if (!this.username.contentEquals(username)) {
            this.username = username
            requestUpdated = true
        }
        return this
    }

    fun setPassword(password: String): StunRequestBuilder {
        if (!this.password.contentEquals(password)) {
            this.password = password
            requestUpdated = true
        }
        return this
    }

    fun setLifetime(lifetime: Int): StunRequestBuilder {
        if (this.lifetime != lifetime) {
            this.lifetime = lifetime
            requestUpdated = true
        }
        return this
    }


    fun setChannelNumber(channelNumber: Int): StunRequestBuilder {
        if (this.channelNumber != channelNumber) {
            this.channelNumber = channelNumber
            requestUpdated = true
        }
        return this
    }

    fun setPeerAddress(peerAddress: AddressValue): StunRequestBuilder {
        if (this.peerAddress != peerAddress) {
            this.peerAddress = peerAddress
            requestUpdated = true
        }
        return this
    }

    fun build(): Message {
        if (requestUpdated) {
            transactionId = generateTransactionId()
            message = null
        } else {
            return message!!
        }

        val attributes = mutableListOf<StunAttribute>()

        when (messageType) {
            MessageType.ALLOCATE_REQUEST -> {
                attributes.apply {
                    add(
                        StunAttribute.createStunAttribute(
                            AttributeType.REQUESTED_TRANSPORT.type,
                            requestedTransport
                        )
                    )
                    add(
                        StunAttribute.createStunAttribute(
                            AttributeType.DONT_FRAGMENT.type,
                            ByteArray(0)
                        )
                    )
                    add(StunAttribute.createStunAttribute(AttributeType.SOFTWARE.type, software))
                    add(StunAttribute.createStunAttribute(AttributeType.LIFETIME.type, lifetime))
                    if (nonce != null) {
                        add(StunAttribute.createStunAttribute(AttributeType.NONCE.type, nonce!!))
                    }
                    if (realm != null) {
                        add(StunAttribute.createStunAttribute(AttributeType.REALM.type, realm!!))
                    }
                    if (username != null) {
                        add(
                            StunAttribute.createStunAttribute(
                                AttributeType.USERNAME.type,
                                username!!
                            )
                        )
                    }
                }
            }

            MessageType.CHANNEL_BIND_REQ -> {
                assert(channelNumber in 0x4000..0x7FFF)

                attributes.apply {
                    add(
                        StunAttribute.createStunAttribute(
                            AttributeType.CHANNEL_NUMBER.type,
                            channelNumber.toShort()
                        )
                    )
                    add(
                        StunAttribute.createStunAttribute(
                            AttributeType.XOR_PEER_ADDRESS.type,
                            peerAddress!!.xorAddress()
                        )
                    )
                    add(StunAttribute.createStunAttribute(AttributeType.USERNAME.type, username!!))

                    add(StunAttribute.createStunAttribute(AttributeType.REALM.type, realm!!))
                    add(StunAttribute.createStunAttribute(AttributeType.NONCE.type, nonce!!))
                }
            }

            MessageType.ALLOCATE_REFRESH_REQUEST -> {
                attributes.apply {
                    add(StunAttribute.createStunAttribute(AttributeType.LIFETIME.type, lifetime))
                    add(StunAttribute.createStunAttribute(AttributeType.USERNAME.type, username!!))
                    add(StunAttribute.createStunAttribute(AttributeType.REALM.type, realm!!))
                    add(StunAttribute.createStunAttribute(AttributeType.NONCE.type, nonce!!))
                }
            }

            else -> {
                throw UnsupportedOperationException("$messageType is not supported.")
            }
        }
        val messageLength = attributes.sizeInBytes()
        var header = MessageHeader(messageType.type, transactionId, MAGIC_COCKIE, messageLength)

        if (realm != null && username != null && password != null) {
            header = header.copy(msgLen = messageLength + 24)
            val messageIntegrity = StunAttribute.createStunAttribute(
                AttributeType.MESSAGE_INTEGRITY.type,
                generateHashCode(
                    input = Message(header, attributes).encodeToByteArray(),
                    key = "$username:$realm:$password"
                )
            )
            attributes.add(messageIntegrity)
        }

        requestUpdated = false
        return Message(header, attributes).also { message = it }
    }
}