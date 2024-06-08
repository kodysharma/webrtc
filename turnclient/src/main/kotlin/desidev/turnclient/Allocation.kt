package desidev.turnclient

import kotlin.time.Duration


class Allocation(
    lifetime: Duration,
    val iceCandidates: List<ICECandidate>
) : ExpireAble by ExpireAbleImpl(lifetime)


