package desidev.videocall.service.signal

import desidev.videocall.service.Answer
import desidev.videocall.service.Offer
import kotlinx.coroutines.flow.Flow


sealed interface SignalEvent {
    data class OfferEvent<P : Any>(val offer: Offer<P>) : SignalEvent
    data class AnswerEvent(val answer: Answer) : SignalEvent
    data class SessionCloseEvent(val reason: String) : SignalEvent
    data object CancelOfferEvent : SignalEvent
}

interface Signal<P : Any> {
    val signalFlow: Flow<SignalEvent>
    fun sendOffer(offer: Offer<P>)
    fun sendAnswer(answer: Answer)

    /**
     * Send a cancel offer event to the remote peer.
     */
    fun cancelOffer()

    /**
     * Send a session close event to the remote peer.
     */
    fun cancelSession()
}
