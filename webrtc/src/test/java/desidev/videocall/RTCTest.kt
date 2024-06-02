package desidev.videocall

import desidev.rtc.rtcclient.RTC
import desidev.turnclient.ICECandidate
import desidev.turnclient.attribute.TransportProtocol
import kotlinx.coroutines.runBlocking
import kotlin.test.Test


class RTCTest {
    @Test
    fun rtc_test(): Unit = runBlocking {
        val ice = listOf(ICECandidate(
            ip = "192.168.0.105",
            port = 44439,
            type = ICECandidate.CandidateType.RELAY,
            priority = 1L,
            protocol = TransportProtocol.UDP
        ))


        val rtc = RTC()
        rtc.createLocalIce()
        rtc.addRemoteIce(ice)
        rtc.createPeerConnection()

        rtc.closePeerConnection()

        rtc.addRemoteIce(ice)
        rtc.createPeerConnection()
    }
}