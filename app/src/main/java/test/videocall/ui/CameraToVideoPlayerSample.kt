package test.videocall.ui

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import desidev.rtc.media.ReceivingPort
import desidev.rtc.media.camera.CameraCapture
import desidev.rtc.media.camera.CameraCaptureImpl
import desidev.videocall.service.rtcmsg.RTCMessage
import desidev.rtc.media.player.VideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


@Composable
fun CameraToVideoPlayer() {
    val context = LocalContext.current
    val cameraCapture = remember { CameraCaptureImpl(context) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun start() {
        cameraCapture.start()
        Log.d("CameraToVideoPlayer", "start")
    }

    suspend fun stop() {
        cameraCapture.stop()
        Log.d("CameraToVideoPlayer", "stop")
    }

    Column {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(), contentAlignment = Alignment.Center
        ) {
            Button(onClick = {
                scope.launch {
                    val value = !isRunning
                    if (value) {
                        start()
                    } else {
                        stop()
                    }
                    isRunning = value
                }
            }) {
                Text(if (isRunning) "Stop" else "Start")
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, Color.Green), contentAlignment = Alignment.Center
        ) {
            if (isRunning) {
                VideoPlayerView(
                    format = runBlocking { cameraCapture.getMediaFormat().await() },
                    samples = cameraCapture.compressedDataChannel().receiveAsFlow().map {
                        it.run {
                            RTCMessage.Sample(
                                ptsUs = second.presentationTimeUs,
                                buffer = first,
                                flags = second.flags
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}


private fun flowOfSamples(port: ReceivingPort<Pair<ByteArray, BufferInfo>>) =
    channelFlow<RTCMessage.Sample> {
        while (port.isOpenForReceive && isActive) {
            try {
                send(port.receive().run {
                    RTCMessage.Sample(
                        ptsUs = second.presentationTimeUs,
                        buffer = first,
                        flags = second.flags
                    )
                })
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }


@Composable
fun VideoPlayerView(
    format: MediaFormat,
    samples: Flow<RTCMessage.Sample>,
    modifier: Modifier = Modifier
) {
    val videoPlayer = remember {
        VideoPlayer(format).also {
            Log.d("VideoPlayerView", "format: $format")
        }
    }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        videoPlayer.play()
        scope.launch(Dispatchers.Default) {
            samples.collect { sample ->
                videoPlayer.inputData(
                    buffer = sample.buffer,
                    info = MediaCodec.BufferInfo().apply {
                        size = sample.buffer.size
                        presentationTimeUs = sample.ptsUs
                        flags = sample.flags
                    }
                )
            }
        }
        onDispose {
            scope.launch {
                videoPlayer.stop()
            }
        }
    }

    videoPlayer.VideoPlayerView(modifier = modifier)
}
