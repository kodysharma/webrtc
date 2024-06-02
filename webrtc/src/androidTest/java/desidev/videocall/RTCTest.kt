package desidev.videocall

import android.Manifest
import androidx.test.rule.GrantPermissionRule
import desidev.rtc.rtcclient.RTC
import desidev.rtc.rtcclient.TrackListener
import desidev.rtc.rtcmsg.RTCMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class RTCTest {
    @JvmField
    @Rule
    val requiredPermissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.INTERNET)
    @Test
    fun peerConnectionTest() : Unit = runBlocking(Dispatchers.IO) {
        val rtc1 = RTC()
        val rtc2 = RTC()

        rtc1.createLocalIce()
        rtc2.createLocalIce()

        println("peer1 local ice: ${rtc1.getLocalIce()}")
        println("peer2 local ice: ${rtc2.getLocalIce()}")

        rtc2.addRemoteIce(rtc1.getLocalIce())
        rtc1.addRemoteIce(rtc2.getLocalIce())


        rtc1.setTrackListener(object : TrackListener{
            override fun onVideoStreamAvailable(videoFormat: RTCMessage.Format) {

            }

            override fun onAudioStreamAvailable(audioFormat: RTCMessage.Format) {
                println("rtc1 received: OnAudioStreamAvailable $audioFormat")
            }

            override fun onNextVideoSample(videoSample: RTCMessage.Sample) {
            }

            override fun onNextAudioSample(audioSample: RTCMessage.Sample) {
            }

            override fun onVideoStreamDisable() {
            }

            override fun onAudioStreamDisable() {
            }
        })


        rtc2.setTrackListener(object : TrackListener{
            override fun onVideoStreamAvailable(videoFormat: RTCMessage.Format) {
            }

            override fun onAudioStreamAvailable(audioFormat: RTCMessage.Format) {
                println("rtc2 received: OnAudioStreamAvailable $audioFormat")
            }

            override fun onNextVideoSample(videoSample: RTCMessage.Sample) {
            }

            override fun onNextAudioSample(audioSample: RTCMessage.Sample) {
            }

            override fun onVideoStreamDisable() {
            }

            override fun onAudioStreamDisable() {
            }
        })

        launch {
            rtc1.createPeerConnection()
            println("RTC1 createPeerConnection() success")

            rtc1.enableAudioStream(RTCMessage.Format(
                mapOf("format" to RTCMessage.OneOfValue(string = "audio"))
            ))
            println("audio format sent by the rtc1")
        }

        launch {
            rtc2.createPeerConnection()
            println("RTC2 createPeerConnection() success")

            rtc2.enableAudioStream(RTCMessage.Format(
                mapOf("format" to RTCMessage.OneOfValue(string = "audio"))
            ))

            println("audio format sent by the rtc2")
        }
    }
}