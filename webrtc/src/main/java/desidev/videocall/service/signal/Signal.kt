package desidev.videocall.service.signal

import desidev.videocall.service.CallAnswer
import desidev.videocall.service.CallOffer
import desidev.videocall.service.ICECandidate

interface Signal {
    fun sendOffer(offer: CallOffer)
    fun sendAnswer(answer: CallAnswer)
    fun sendIceCandidate(iceCandidate: ICECandidate)
}