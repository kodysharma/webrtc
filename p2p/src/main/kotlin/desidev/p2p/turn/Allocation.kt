package desidev.p2p.turn

import desidev.p2p.ExpireTimer
import desidev.p2p.ExpireTimerImpl
import desidev.p2p.ICECandidate
import kotlin.time.Duration

data class Allocation(
    val lifetime: Duration,
    val iceCandidates: List<ICECandidate>
) : ExpireTimer by ExpireTimerImpl(lifetime)


