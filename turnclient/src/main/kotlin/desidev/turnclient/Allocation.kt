package desidev.turnclient

import kotlin.time.Duration


data class Allocation(
    val lifetime: Duration,
    val iceCandidates: List<ICECandidate>
) : ExpireAble by ExpireAbleImpl(lifetime)


