package desidev.videocall.service

import desidev.turnclient.ICECandidate

data class Answer(
    val id: String,
    val accepted: Boolean,
    val candidates: List<ICECandidate>
)