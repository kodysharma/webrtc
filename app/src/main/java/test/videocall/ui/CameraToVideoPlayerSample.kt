package test.videocall.ui

import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import desidev.rtc.media.camera.CameraCapture.CameraState
import desidev.rtc.media.camera.CameraCaptureImpl
import desidev.rtc.media.player.VideoPlayer
import desidev.rtc.rtcmsg.RTCMessage
import desidev.utility.yuv.YuvToRgbConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch


@Composable
fun CameraToVideoPlayer() {
    val context = LocalContext.current
    val cameraCapture = remember { CameraCaptureImpl(context) }
    val cameraState by cameraCapture.state.collectAsState()
    var format by remember { mutableStateOf<MediaFormat?>(null) }
    val scope = rememberCoroutineScope()
    var isCaptureStarted by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(cameraState) {
        if (cameraState == CameraState.INACTIVE) {
            isCaptureStarted = false
        }
    }

    DisposableEffect(key1 = Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d("CameraToVideoPlayer", "ON_RESUME")
                    scope.launch {
                        cameraCapture.openCamera()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d("CameraToVideoPlayer", "ON_PAUSE")
                    scope.launch {
                        cameraCapture.closeCamera()
                    }
                }

                else -> { }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    suspend fun start() {
        cameraCapture.startCapture()
        format = cameraCapture.getMediaFormat().await()
        Log.d("CameraToVideoPlayer", "start")
    }

    suspend fun stop() {
        cameraCapture.stopCapture()
        Log.d("CameraToVideoPlayer", "stop")
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        val halfHeight = (maxHeight.value / 2).dp
        if (isCaptureStarted && format != null) {
            VideoPlayerView(
                format = format!!,
                samples = cameraCapture.compressChannel().receiveAsFlow().map {
                    it.run {
                        RTCMessage.Sample(
                            ptsUs = second.presentationTimeUs, buffer = first, flags = second.flags
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(halfHeight)
                    .align(Alignment.TopCenter)
            )
        }

        // preview
        cameraCapture.PreviewView(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .height(halfHeight)
                .fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch {
                    isCaptureStarted = !isCaptureStarted
                    if (isCaptureStarted) {
                        start()
                    } else {
                        stop()
                    }
                }
            },
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.BottomEnd)
        ) {
            Text(text = if (isCaptureStarted) "Stop" else "Start")
        }

        FilledIconButton(
            onClick = {
                scope.launch {
                    cameraCapture.apply { selectCamera(getCameras().first { it != selectedCamera.value }) }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
        }
    }
}


@Composable
fun VideoPlayerView(
    format: MediaFormat, samples: Flow<RTCMessage.Sample>, modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val yuvToRgbConverter = remember { YuvToRgbConverter(context) }

    val videoPlayer = remember {
        VideoPlayer(yuvToRgbConverter, format).also {
            Log.d("VideoPlayerView", "format: $format")
        }
    }

    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        videoPlayer.play()
        scope.launch(Dispatchers.Default) {
            samples.collect { sample ->
                videoPlayer.inputData(buffer = sample.buffer, info = BufferInfo().apply {
                    size = sample.buffer.size
                    presentationTimeUs = sample.ptsUs
                    flags = sample.flags
                })
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
