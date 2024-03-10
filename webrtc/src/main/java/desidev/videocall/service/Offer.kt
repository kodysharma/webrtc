package desidev.videocall.service

import desidev.turnclient.ICECandidate

data class Offer<P>(
    val id: String,
    val candidates: List<ICECandidate>,
    val timestamp: Long,
    val expiryTime: Long,
    val peer: P
)