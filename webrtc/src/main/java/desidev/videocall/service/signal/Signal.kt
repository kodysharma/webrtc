package desidev.videocall.service.signal

import desidev.videocall.service.CallAnswer
import desidev.videocall.service.IncomingCall
import desidev.videocall.service.OutgoingCall
import kotlinx.coroutines.flow.Flow

interface Signal<C : Any> {
    fun answerFlow(): Flow<CallAnswer>
    fun offerFlow(): Flow<IncomingCall<C>>
    fun onCloseFlow(): Flow<Unit>

    fun sendOffer(offer: OutgoingCall<C>)
    fun sendAnswer(answer: CallAnswer)
    fun sendClose()
    fun sendCancelOffer()
}