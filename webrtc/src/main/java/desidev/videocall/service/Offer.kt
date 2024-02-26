package desidev.videocall.service

import android.media.MediaFormat
import desidev.turnclient.ICECandidate

data class IncomingCall<P>(
    val id: String,
    val caller: P,
    val candidates: List<ICECandidate>
)

data class OutgoingCall<P>(
    val id: String,
    val callee: P,
    val candidates: List<ICECandidate>
)

data class Offer<P>(
    val id: String,
    val mediaFormat: List<MediaFormat>,
    val candidates: List<ICECandidate>,
    val timestamp: Long,
    val expiryTime: Long,
    val peer: P
)