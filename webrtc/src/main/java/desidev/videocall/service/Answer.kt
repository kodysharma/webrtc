package desidev.videocall.service

import desidev.turnclient.ICECandidate

data class CallAnswer(
    val id: String,
    val accepted: Boolean,
    val candidates: List<ICECandidate>
)