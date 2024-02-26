package desidev.turnclient.attribute

/**
 * A generalized stun/turn attribute class.
 * This is not in use yet.
 * @param type the attribute type.
 */

sealed class Attribute(val type: AttributeType) {
    data class Username(
        val username: String
    ) : Attribute(AttributeType.USERNAME)

    data class Password(
        val password: String
    ) : Attribute(AttributeType.PASSWORD)

    class MessageIntegrity(
        val messageIntegrity: ByteArray
    ) : Attribute(AttributeType.MESSAGE_INTEGRITY)

    class ErrorCode(
        val errorCode: Int
    ) : Attribute(AttributeType.ERROR_CODE)

    class UnknownAttributes(
        val unknownAttributes: List<AttributeType>
    ) : Attribute(AttributeType.UNKNOWN_ATTRIBUTES)

    class AllocateErrorCode(
        val errorCode: Int
    ) : Attribute(AttributeType.ALLOCATE_ERROR_CODE)

    class Realm(
        val realm: String
    ) : Attribute(AttributeType.REALM)

    class Nonce(
        val nonce: String
    ) : Attribute(AttributeType.NONCE)

    class Software(
        val software: String
    ) : Attribute(AttributeType.SOFTWARE)

    class Lifetime(
        val lifetime: Int
    ) : Attribute(AttributeType.LIFETIME)

    class RequestedTransport(
        val transport: TransportProtocol
    ) : Attribute(AttributeType.REQUESTED_TRANSPORT)

    data object DontFragment : Attribute(AttributeType.DONT_FRAGMENT)

}