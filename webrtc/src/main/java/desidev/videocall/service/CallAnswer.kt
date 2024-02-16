package desidev.videocall.service

interface CallAnswer {
    val callId: String
    val candidates: List<ICECandidate>
}