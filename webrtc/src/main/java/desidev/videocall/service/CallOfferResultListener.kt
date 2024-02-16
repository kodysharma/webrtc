package desidev.videocall.service

interface CallOfferResultListener {
    fun onAccept(callOffer: CallOffer)
    fun onReject(callOffer: CallOffer)
}