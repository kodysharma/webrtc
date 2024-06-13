package desidev.turnclient.message

import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.AttributeType
import desidev.turnclient.attribute.StunAttribute
import desidev.turnclient.attribute.TransportProtocol
import desidev.turnclient.message.TurnMessage.Companion.MAGIC_COCKIE
import desidev.turnclient.message.TurnMessage.Companion.generateTransactionId
import desidev.turnclient.util.generateHashCode

class TurnRequestBuilder {
    private lateinit var txBytes: ByteArray
    private var requestUpdated = true
    private val stunAttributes = mutableSetOf<StunAttribute>()

    private var messageType: MessageType = MessageType.ALLOCATE_REQUEST
    private var message: TurnMessage? = null

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


    fun setMessageType(messageType: MessageType): TurnRequestBuilder {
        assert(messageType.type and MessageClass.REQUEST.type == MessageClass.REQUEST.type)
        if (this.messageType != messageType) {
            this.messageType = messageType
            requestUpdated = true
        }
        return this
    }

    fun setRealm(realm: String?): TurnRequestBuilder {
        if (!this.realm.contentEquals(realm)) {
            this.realm = realm
            requestUpdated = true
        }
        return this
    }

    fun setNonce(nonce: String?): TurnRequestBuilder {
        if (!this.nonce.contentEquals(nonce)) {
            this.nonce = nonce
            requestUpdated = true
        }
        return this
    }

    fun setUsername(username: String): TurnRequestBuilder {
        if (!this.username.contentEquals(username)) {
            this.username = username
            requestUpdated = true
        }
        return this
    }

    fun setPassword(password: String): TurnRequestBuilder {
        if (!this.password.contentEquals(password)) {
            this.password = password
            requestUpdated = true
        }
        return this
    }

    fun setLifetime(lifetime: Int): TurnRequestBuilder {
        if (this.lifetime != lifetime) {
            this.lifetime = lifetime
            requestUpdated = true
        }
        return this
    }


    fun setChannelNumber(channelNumber: Int): TurnRequestBuilder {
        if (this.channelNumber != channelNumber) {
            this.channelNumber = channelNumber
            requestUpdated = true
        }
        return this
    }

    fun setPeerAddress(peerAddress: AddressValue): TurnRequestBuilder {
        if (this.peerAddress != peerAddress) {
            this.peerAddress = peerAddress
            requestUpdated = true
        }
        return this
    }

    fun build(): TurnMessage {
        if (requestUpdated) {
            txBytes = generateTransactionId()
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

                    username?.let {
                        add(StunAttribute.createStunAttribute(AttributeType.USERNAME.type, it))
                    }

                    realm?.let {
                        add(StunAttribute.createStunAttribute(AttributeType.REALM.type, it))
                    }
                    nonce?.let {
                        add(StunAttribute.createStunAttribute(AttributeType.NONCE.type, it))
                    }
                }
            }

            MessageType.ALLOCATE_REFRESH_REQUEST -> {
                attributes.apply {
                    add(StunAttribute.createStunAttribute(AttributeType.LIFETIME.type, lifetime))
                    add(StunAttribute.createStunAttribute(AttributeType.USERNAME.type, username!!))
                    realm?.let {
                        add(StunAttribute.createStunAttribute(AttributeType.REALM.type, it))
                    }
                    nonce?.let {
                        add(StunAttribute.createStunAttribute(AttributeType.NONCE.type, it))
                    }
                }
            }

            else -> {
                throw UnsupportedOperationException("$messageType is not supported.")
            }
        }
        val messageLength = attributes.sizeInBytes()
        var header = MessageHeader(
            messageType.type,
            MessageHeader.TransactionId(txBytes),
            MAGIC_COCKIE,
            messageLength
        )

        if (realm != null && username != null && password != null) {
            header = header.copy(msgLen = messageLength + 24)
            val messageIntegrity = StunAttribute.createStunAttribute(
                AttributeType.MESSAGE_INTEGRITY.type,
                generateHashCode(
                    input = TurnMessage(header, attributes).encodeToByteArray(),
                    key = "$username:$realm:$password"
                )
            )
            attributes.add(messageIntegrity)
        }

        requestUpdated = false
        return TurnMessage(header, attributes).also { message = it }
    }
}