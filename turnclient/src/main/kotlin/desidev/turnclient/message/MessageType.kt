package desidev.turnclient.message

import java.lang.IllegalArgumentException

enum class MessageType(val type: UShort) {
    // STUN Message Types
    BINDING_REQUEST(0x0001u),
    BINDING_RESPONSE(0x0101u),
    BINDING_ERROR_RESPONSE(0x0111u),

    // TURN Message Types
    ALLOCATE_REQUEST(0x0003u),
    ALLOCATE_RESPONSE(0x0103u),
    ALLOCATE_REFRESH_REQUEST(0x0004u),
    ALLOCATE_REFRESH_RESPONSE(0x0104u),
    ALLOCATE_ERROR_RESPONSE(0x0113u),

    // Additional Message Types
    SEND_INDICATION(0x0016u),
    DATA_INDICATION(0x0017u),

    CREATE_PERMISSION_REQ(0x0018u),

    // Channel Bind message types
    CHANNEL_BIND_REQ(0x0009u),
    CHANNEL_BIND_SUC_RES(0x0109u),
    CHANNEL_BIND_ERR_RES(0x0119u);


    companion object {
        fun isValidType(type: UShort): Boolean {
            return entries.any { it.type == type }
        }

        fun messageTypeToString(type: UShort): String {
            return entries.find { it.type == type }?.name
                ?: throw IllegalArgumentException("Unknown message type: $type")
        }
    }
}

enum class MessageClass(val type: UShort) {
    REQUEST(0x0000u),
    SUCCESS_RESPONSE(0x0100u),
    INDICATION(0x0001u),
    ERROR_RESPONSE(0x0110u)
}
