package desidev.videocall.service

class ICECandidate(
    val ip: String,
    val port: Int,
    val type: CandidateType,
    val priority: Long,
) {
    enum class CandidateType {
        HOST, // Host Candidate
        SRFLX, // Server Reflexive
        PRFLX, // Peer Reflexive
        RELAY // Relayed Candidate
    }
}
