package desidev.turnclient

import desidev.turnclient.attribute.TransportProtocol

data class ICECandidate(
    val ip: String,
    val port: Int,
    val type: CandidateType,
    val priority: Long,
    val protocol: TransportProtocol
) {
    enum class CandidateType {
        HOST, // Host Candidate
        SRFLX, // Server Reflexive
        RELAY // Relayed Candidate
    }
}
