package test.videocall.signalclient

import desidev.turnclient.ICECandidate


//** Parameters **//
data class PostOfferParams(
    val receiverId: String,
    val candidates: List<ICECandidate>
)


data class PostAnswerParams(
    val receiverId: String,
    val accepted: Boolean,
    val candidates: List<ICECandidate>?
)


//**** Events ****//

data class OfferEvent(
    val sender: Peer,
    val candidates: List<ICECandidate>
)

data class AnswerEvent(
    val sender: Peer,
    val accepted: Boolean,
    val candidates: List<ICECandidate>?
)
data class OfferCancelledEvent(
    val senderId: String,
)

object SessionClosedEvent

//** data model **//
data class Peer(
    val id: String,
    val name: String,
    val status: Status
) {
    enum class Status {
        Active, Busy
    }
}
