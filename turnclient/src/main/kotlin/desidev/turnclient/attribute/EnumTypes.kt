package desidev.turnclient.attribute

enum class InetAF(val code: Int) {
    IPV4(1), IPV6(2)
}

enum class TransportProtocol(val code: Int) {
    UDP(0x11000000), TCP(0x06000000)
}

enum class ValueType {
    INTEGER,
    SHORT,
    STRING,
    XOR_ADDR,
    ByteArray,
    NOTHING
}


enum class AttributeType(val type: UShort, val valueType: ValueType) {
    // Address Attributes
//    MAPPED_ADDRESS(0x0001u, ValueType.Xor_Address),
//    RESPONSE_ADDRESS(0x0002u, ValueType.Xor_Address),
//    SOURCE_ADDRESS(0x0004u, ValueType.Xor_Address),
//    CHANGED_ADDRESS(0x0005u, ValueType.Xor_Address),
    //    REFLECTED_FROM(0x000bu, ),
    XOR_MAPPED_ADDRESS(0x0020u, ValueType.XOR_ADDR),
    XOR_RELAYED_ADDRESS(0x0016u, ValueType.XOR_ADDR),
    XOR_PEER_ADDRESS(0x0012u, ValueType.XOR_ADDR),
//    SECONDARY_ADDRESS(0x0050u, ValueType.Xor_Address),

    // Error Attributes
    ERROR_CODE(0x0009u, ValueType.STRING),
    UNKNOWN_ATTRIBUTES(0x000au, ValueType.ByteArray),

    //    ERROR_REASON(0x000Cu, ValueType.STRING),
    //    UNKNOWN_USERNAME(0x000Du,ValueType.STRING),
    ALLOCATE_ERROR_CODE(0x000eu, ValueType.INTEGER),

    // Request Attributes
    USERNAME(0x0006u, ValueType.STRING),
    PASSWORD(0x0007u, ValueType.STRING),
    MESSAGE_INTEGRITY(0x0008u, ValueType.ByteArray),

    // Allocation Attributes
    REALM(0x0014u, ValueType.STRING),
    NONCE(0x0015u, ValueType.STRING),
    SOFTWARE(0x8022u, ValueType.STRING),
    LIFETIME(0x000du, ValueType.INTEGER),

    //    REQUESTED_ADDRESS_FAMILY(0x802cu),
    REQUESTED_TRANSPORT(0x0019u, ValueType.INTEGER),
    DONT_FRAGMENT(0x001au, ValueType.NOTHING),
    RESERVATION_TOKEN(0x0130u, ValueType.STRING),
    CHANNEL_NUMBER(0x000Cu, valueType = ValueType.SHORT);

    // Data Attributes
//    DATA(0x0013u),
//    DATA_ORIGIN(0x0007u),

    // Channel Attributes
//    LIFETIME_CHANNEL(0x000du),

    // Other Attributes
//    CHANGE_REQUEST(0x0003u),
//    XOR_ONLY(0x0021u),
//    SERVER_NAME(0x0022u),
//    ALTERNATE_SERVER(0x8023u),
//    FINGERPRINT(0x8028u),
//    EVEN_PORT(0x8028u),
//    DATA_ADDRESS(0x0022u),
//    REQUESTED_PORT_PROPS(0x0044u),
//    REQUESTED_PROPS(0x0045u),
//    RESERVED_PROPS(0x0046u),
//    REQUESTED_TRANSPORT_PROPERTIES(0x0047u);

    companion object {
        fun attributeTypeToString(num: UShort): String {
            return values().find { it.type == num }?.name
                ?: throw IllegalArgumentException("Unknown attribute type: $num")
        }
    }
}
