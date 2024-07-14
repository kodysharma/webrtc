package desidev.p2p

import java.io.IOException


/// ****** TURN EXCEPTIONS **************///
sealed class TurnRequestFailure : RuntimeException() {
    class ServerUnreachable : TurnRequestFailure()
    class BadRequestException : TurnRequestFailure()
    class UnauthorizedException(
        val realm: String,
        val nonce: String,
    ) : TurnRequestFailure()

    class AllocationMismatchException(override val message: String?) : TurnRequestFailure()
    class WrongCredException(override val message: String?) : TurnRequestFailure()
    class InsufficientCapacityException(
        override val message: String?
    ) : TurnRequestFailure()

    class MissingAttributeException(attributeName: String) : TurnRequestFailure() {
        override val message: String = "Expected Attribute $attributeName not found."
    }

    class StaleNonceException(val nonce: String) : TurnRequestFailure()

    class OtherReason(errorCode: Int, reason: String) : TurnRequestFailure() {
        override val message: String = "error code: $errorCode reason: $reason"
    }
}

class MtuSizeExceed(size: Int, mtuSize: Int) : IllegalArgumentException() {
    override val message: String = "data size ($size) is greater than the mtu size ($mtuSize)."
}

sealed class SocketFailure : IOException() {

    class SocketIsClosed : SocketFailure()
    class PortIsNotAvailable(port: Int) : SocketFailure() {
        override val message: String = "Port $port is not available"
    }
}


class LineBlockException(message: String) : Exception(message)
