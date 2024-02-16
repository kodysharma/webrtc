package desidev.videocall.service

interface CallOffer {
    val callId: String
    val candidates: List<ICECandidate>

    /**
     * Accept the call offer
     */
    fun accept(): CallAnswer

    /**
     * Reject the call offer
     */
    fun reject()
}